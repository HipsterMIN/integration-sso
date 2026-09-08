package io.github.hipstermin.idem.hub.sso;

import io.github.hipstermin.idem.common.domain.CastToken;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.util.UuidV7;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import io.github.hipstermin.idem.hub.infrastructure.AgencyMetaRepository;
import io.github.hipstermin.idem.hub.infrastructure.QAuthzClient;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Cross-Agency SSO Token (CAST) 서비스 구현체
 *
 * <h3>흐름 요약</h3>
 * <pre>
 * [발급 — issue()]
 *   1. FE 세션 조회 → qimUserId, authLevel, sourceAgencyCode 추출
 *   2. 대상 기관(targetAgency) 등록 여부 확인
 *   3. EdDSA(Ed25519) JWT 서명 생성 (jti=UUID v7, exp=now+5분)
 *   4. Redis SET NX: cast:consumed:{jti} = "ISSUED" TTL 600초 (만료 여유 2x)
 *   5. cast_token_audit INSERT
 *   6. CastToken 반환
 *
 * [검증 — verify()]
 *   1. JWT 파싱 + 서명 검증 (Ed25519 공개키)
 *   2. exp 검증 (만료 시 SSO_CAST_EXPIRED)
 *   3. tgt_agency == targetAgencyCode 검증
 *   4. Redis SET NX: cast:consumed:{jti} = "CONSUMED" (이미 존재 시 SSO_CAST_CONSUMED)
 *   5. cast_token_audit UPDATE (status=CONSUMED, consumed_at=now)
 *   6. sso_session_link INSERT
 *   7. CastToken 반환
 * </pre>
 *
 * @see CastTokenService
 * @see CastKeyConfig
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CastTokenServiceImpl implements CastTokenService {

    private static final String REDIS_ISSUED_VALUE   = "ISSUED";
    private static final String REDIS_CONSUMED_VALUE = "CONSUMED";
    /** Redis TTL = CAST TTL * 2 (만료 후에도 재사용 시도 감지를 위해 여유 유지) */
    private static final long REDIS_TTL_SECONDS = CastToken.TTL_SECONDS * 2;

    private final FeSessionService           feSessionService;
    private final AgencyMetaRepository       agencyMetaRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final JdbcTemplate               jdbcTemplate;
    /** 연합 인가 — 대상 기관 스코프 역할 조회(fail-open) */
    private final QAuthzClient               qAuthzClient;

    /** CastKeyConfig 에서 주입된 Ed25519 KeyPair */
    @Qualifier("castKeyPair")
    private final KeyPair castKeyPair;

    // ── 발급 ──────────────────────────────────────────────────────────────

    @Override
    public CastToken issue(String feSessionId, String targetAgencyCode, String correlationId) {
        log.info("[CastToken] 발급 시작 feSessionId={} targetAgency={} cid={}",
                feSessionId, targetAgencyCode, correlationId);

        // 1. FE 세션 조회
        FeSession feSession = feSessionService.findById(feSessionId)
                .orElseThrow(() -> {
                    log.warn("[CastToken] FE 세션 없음 feSessionId={} cid={}", feSessionId, correlationId);
                    return new PlatformException(PlatformErrorCode.SSO_CAST_SESSION_NOT_FOUND, correlationId);
                });

        // 2. 대상 기관 등록 확인
        agencyMetaRepository.findByCode(targetAgencyCode)
                .filter(a -> a.isActive())
                .orElseThrow(() -> {
                    log.warn("[CastToken] 대상 기관 미등록/비활성 targetAgency={} cid={}", targetAgencyCode, correlationId);
                    return new PlatformException(PlatformErrorCode.AGENCY_NOT_REGISTERED, correlationId);
                });

        // 3. CAST JWT 생성
        String  jti           = UuidV7.generate();
        Instant issuedAt      = Instant.now();
        Instant expiresAt     = issuedAt.plusSeconds(CastToken.TTL_SECONDS);
        String  qimUserId     = feSession.getQimUserId();
        String  authLevel     = feSession.getAuthLevel() != null ? feSession.getAuthLevel() : "LOW";
        // sourceAgency: FE 세션에 저장된 기관 코드 (없으면 ONEPASS)
        String  sourceAgency  = "ONEPASS";

        // 연합 인가: 대상 기관 스코프 유효 역할 조회(fail-open — 장애 시 빈 역할).
        // 플랫폼은 굵은 RBAC 역할만 배송하고, 세밀한 집행은 기관 PEP가 수행한다.
        List<String> roles = qAuthzClient.getEffectiveRoles(qimUserId, targetAgencyCode, correlationId);

        String jwt;
        try {
            jwt = Jwts.builder()
                    .id(jti)
                    .subject(qimUserId)
                    .issuedAt(Date.from(issuedAt))
                    .expiration(Date.from(expiresAt))
                    .claim(CastToken.CLAIM_TARGET_AGENCY, targetAgencyCode)
                    .claim(CastToken.CLAIM_SOURCE_AGENCY, sourceAgency)
                    .claim(CastToken.CLAIM_AUTH_LEVEL,    authLevel)
                    .claim(CastToken.CLAIM_ROLES,         roles)
                    .signWith(castKeyPair.getPrivate(), Jwts.SIG.EdDSA)
                    .header().add("typ", CastToken.TOKEN_TYPE).and()
                    .compact();
        } catch (Exception e) {
            log.error("[CastToken] JWT 서명 실패 cid={} err={}", correlationId, e.getMessage(), e);
            throw new PlatformException(PlatformErrorCode.SSO_CAST_ISSUE_FAILED, correlationId);
        }

        // 4. Redis SET NX — 1회 소비 사전 등록 (TTL = CAST TTL * 2)
        String redisKey = CastToken.REDIS_CONSUMED_PREFIX + jti;
        Boolean setNx = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, REDIS_ISSUED_VALUE, Duration.ofSeconds(REDIS_TTL_SECONDS));
        if (Boolean.FALSE.equals(setNx)) {
            // 이론상 UUID v7 충돌 불가 — 방어 코드
            log.error("[CastToken] Redis SET NX 충돌 (UUID 중복 — 이론상 불가) jti={}", jti);
            throw new PlatformException(PlatformErrorCode.SSO_CAST_ISSUE_FAILED, correlationId);
        }

        // 5. cast_token_audit INSERT
        try {
            jdbcTemplate.update("""
                INSERT INTO ido.cast_token_audit
                    (jti, qim_user_id, source_agency, target_agency, auth_level,
                     issued_at, expires_at, status, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ISSUED', ?)
                """,
                jti, qimUserId, sourceAgency, targetAgencyCode, authLevel,
                java.sql.Timestamp.from(issuedAt), java.sql.Timestamp.from(expiresAt),
                correlationId);
        } catch (Exception e) {
            // DB 기록 실패는 비치명적 — Redis에 이미 등록됨 → 운영 알림만
            log.error("[CastToken] cast_token_audit INSERT 실패 (비치명적) jti={} cid={} err={}",
                    jti, correlationId, e.getMessage());
        }

        CastToken castToken = new CastToken(
                jti, qimUserId, sourceAgency, targetAgencyCode, authLevel,
                issuedAt, expiresAt, jwt, roles);

        log.info("[CastToken] 발급 완료 jti={} targetAgency={} roles={} expires={} cid={}",
                jti, targetAgencyCode, roles.size(), expiresAt, correlationId);
        return castToken;
    }

    // ── 검증 및 소비 ──────────────────────────────────────────────────────

    @Override
    public CastToken verify(String castJwt, String targetAgencyCode, String consumerIp, String correlationId) {
        log.info("[CastToken] 검증 시작 targetAgency={} cid={}", targetAgencyCode, correlationId);

        // 1. JWT 파싱 + 서명 검증
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(castKeyPair.getPublic())
                    .build()
                    .parseSignedClaims(castJwt)
                    .getPayload();
        } catch (SignatureException e) {
            log.warn("[CastToken] 서명 검증 실패 cid={}", correlationId);
            throw new PlatformException(PlatformErrorCode.SSO_CAST_SIGNATURE_INVALID, correlationId);
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            log.warn("[CastToken] JWT 만료 cid={}", correlationId);
            throw new PlatformException(PlatformErrorCode.SSO_CAST_EXPIRED, correlationId);
        } catch (Exception e) {
            log.warn("[CastToken] JWT 파싱 오류 cid={} err={}", correlationId, e.getMessage());
            throw new PlatformException(PlatformErrorCode.SSO_CAST_SIGNATURE_INVALID, correlationId);
        }

        String  jti          = claims.getId();
        String  qimUserId    = claims.getSubject();
        String  tgtAgency    = claims.get(CastToken.CLAIM_TARGET_AGENCY, String.class);
        String  srcAgency    = claims.get(CastToken.CLAIM_SOURCE_AGENCY, String.class);
        String  authLevel    = claims.get(CastToken.CLAIM_AUTH_LEVEL,    String.class);
        List<String> roles   = extractRoles(claims);
        Instant issuedAt     = claims.getIssuedAt().toInstant();
        Instant expiresAt    = claims.getExpiration().toInstant();

        // 2. 만료 검증 (jjwt가 이미 처리하나 방어적 이중 확인)
        if (Instant.now().isAfter(expiresAt)) {
            log.warn("[CastToken] 만료된 CAST 토큰 jti={} cid={}", jti, correlationId);
            throw new PlatformException(PlatformErrorCode.SSO_CAST_EXPIRED, correlationId);
        }

        // 3. 대상 기관 일치 검증
        if (!targetAgencyCode.equals(tgtAgency)) {
            log.warn("[CastToken] 기관 불일치 jti={} expected={} actual={} cid={}",
                    jti, targetAgencyCode, tgtAgency, correlationId);
            throw new PlatformException(PlatformErrorCode.SSO_CAST_AGENCY_MISMATCH, correlationId);
        }

        // 4. Redis SET NX — 1회 소비 원자 연산
        //    "ISSUED" → "CONSUMED" 전환: setIfAbsent("CONSUMED")가 아니라
        //    기존 "ISSUED" 값을 "CONSUMED"로 교체하는 방식 사용
        //    (SET NX는 키가 없을 때만 성공 → ISSUED 상태 키가 이미 있으면 실패 = 이미 소비됨)
        String redisKey   = CastToken.REDIS_CONSUMED_PREFIX + jti;
        Object existing   = redisTemplate.opsForValue().get(redisKey);

        if (existing == null) {
            // Redis TTL 만료 (토큰 만료 후 Redis 키도 삭제됨) — 만료 처리
            log.warn("[CastToken] Redis 키 없음 (만료 또는 미발급) jti={} cid={}", jti, correlationId);
            throw new PlatformException(PlatformErrorCode.SSO_CAST_EXPIRED, correlationId);
        }
        if (REDIS_CONSUMED_VALUE.equals(existing.toString())) {
            log.warn("[CastToken] 이미 소비된 CAST 토큰 jti={} cid={}", jti, correlationId);
            throw new PlatformException(PlatformErrorCode.SSO_CAST_CONSUMED, correlationId);
        }

        // ISSUED → CONSUMED 원자 전환 (GET-and-SET 패턴)
        // TTL은 남은 시간 유지 (재소비 시도 감지를 위해 키 유지)
        Long remainTtl = redisTemplate.getExpire(redisKey);
        Duration remainDuration = (remainTtl != null && remainTtl > 0)
                ? Duration.ofSeconds(remainTtl)
                : Duration.ofSeconds(60L);
        redisTemplate.opsForValue().set(redisKey, REDIS_CONSUMED_VALUE, remainDuration);

        // 5. cast_token_audit UPDATE
        Instant consumedAt = Instant.now();
        try {
            jdbcTemplate.update("""
                UPDATE ido.cast_token_audit
                SET status = 'CONSUMED', consumed_at = ?, consumer_ip = ?
                WHERE jti = ?
                """,
                java.sql.Timestamp.from(consumedAt), consumerIp, jti);
        } catch (Exception e) {
            log.error("[CastToken] cast_token_audit UPDATE 실패 (비치명적) jti={} cid={} err={}",
                    jti, correlationId, e.getMessage());
        }

        // 6. sso_session_link INSERT
        try {
            jdbcTemplate.update("""
                INSERT INTO ido.sso_session_link
                    (cast_jti, qim_user_id, source_agency, target_agency, linked_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                jti, qimUserId, srcAgency, tgtAgency,
                java.sql.Timestamp.from(consumedAt), correlationId);
        } catch (Exception e) {
            log.error("[CastToken] sso_session_link INSERT 실패 (비치명적) jti={} cid={} err={}",
                    jti, correlationId, e.getMessage());
        }

        log.info("[CastToken] 검증 성공 jti={} qimUserId={} sourceAgency={} targetAgency={} cid={}",
                jti, qimUserId, srcAgency, tgtAgency, correlationId);

        return new CastToken(jti, qimUserId, srcAgency, tgtAgency, authLevel, issuedAt, expiresAt, castJwt, roles);
    }

    /** JWT {@code roles} 클레임을 안전하게 List&lt;String&gt;로 추출(없으면 빈 리스트). */
    @SuppressWarnings("unchecked")
    private static List<String> extractRoles(Claims claims) {
        Object raw = claims.get(CastToken.CLAIM_ROLES);
        if (raw instanceof List<?> list) {
            return list.stream().filter(String.class::isInstance)
                    .map(String.class::cast).toList();
        }
        return List.of();
    }
}

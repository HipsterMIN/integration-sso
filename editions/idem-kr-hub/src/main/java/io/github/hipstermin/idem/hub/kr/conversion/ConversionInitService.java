package io.github.hipstermin.idem.hub.kr.conversion;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.domain.AgencyMeta;
import io.github.hipstermin.idem.hub.handoff.validate.CallbackUrlValidator;
import io.github.hipstermin.idem.hub.infrastructure.AgencyCredentialStore;
import io.github.hipstermin.idem.hub.infrastructure.AgencyMetaRepository;
import io.github.hipstermin.idem.hub.kr.conversion.dto.ConversionInitRequest;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 회원 전환 초기화 서비스 — signed_request(JWT HS256) 검증 + ConversionSession 생성
 *
 * <h2>처리 순서</h2>
 * <ol>
 *   <li>agencyCode → agency_meta 조회 (비활성 기관 차단)</li>
 *   <li>기관 authCredentialRef → K8s Secret에서 API Key 원문 조회 (AgencyCredentialStore)</li>
 *   <li>JWT HMAC-SHA256 서명 검증 (sub = agencyCode 일치 확인 포함)</li>
 *   <li>JWT exp → 발급 후 {@code signed-request-max-age-minutes} 이내인지 검증</li>
 *   <li>JWT redirectUri → agency_meta.callback_whitelist 화이트리스트 검증</li>
 *   <li>ConversionSession 생성 → Redis TTL {@code session-ttl-minutes}로 저장</li>
 * </ol>
 *
 * <h2>보안 주의</h2>
 * <ul>
 *   <li>API Key 원문은 K8s Secret 환경변수에서만 조회 — DB/로그에 평문 저장 금지</li>
 *   <li>ConversionSession에 mbrId, redirectUri 보관 → FE URL에 노출하지 않음</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversionInitService {

    private static final String REDIS_KEY_PREFIX = "conversion:session:";

    private final AgencyMetaRepository   agencyMetaRepository;
    private final AgencyCredentialStore  credentialStore;
    private final CallbackUrlValidator   callbackUrlValidator;
    private final RedisTemplate<String, Object> redisTemplate;

    /** ConversionSession Redis TTL (기본 30분) */
    @Value("${idem.hub.conversion.session-ttl-minutes:30}")
    private long sessionTtlMinutes;

    /** signed_request JWT 최대 허용 연령 (기본 5분) */
    @Value("${idem.hub.conversion.signed-request-max-age-minutes:5}")
    private long signedRequestMaxAgeMinutes;

    /**
     * 전환 초기화 — signed_request 검증 후 ConversionSession 반환
     *
     * @param req signed_request(JWT) + agencyCode
     * @param cid 추적 ID
     * @return 생성된 ConversionSession
     * @throws PlatformException AGENCY_NOT_FOUND, CONVERSION_SIGNATURE_INVALID,
     *                           CONVERSION_REQUEST_EXPIRED, AGENCY_CALLBACK_BLOCKED
     */
    public ConversionSession initiate(ConversionInitRequest req, String cid) {

        // ① agency_meta 조회
        AgencyMeta agency = agencyMetaRepository.findByCode(req.getAgencyCode())
                .filter(AgencyMeta::isActive)
                .orElseThrow(() -> {
                    log.warn("[ConversionInit] 기관 없음 또는 비활성: agencyCode={} cid={}",
                            req.getAgencyCode(), cid);
                    return new PlatformException(PlatformErrorCode.AGENCY_NOT_FOUND, cid);
                });

        // ② 기관 API Key 조회 (K8s Secret 환경변수 기반)
        //    authCredentialRef 예: "secrets/agency/BIZINFO_001/api-key"
        //    → 환경변수명: SECRETS_AGENCY_BIZINFO_001_API_KEY
        //    ※ AgencyMeta에 authCredentialRef 필드가 없는 경우 기관코드 기반 기본 경로 사용
        String credentialRef = buildDefaultCredentialRef(req.getAgencyCode());
        String apiKey = credentialStore.findSecret(credentialRef);

        if (apiKey == null || apiKey.isBlank()) {
            log.error("[ConversionInit] 기관 API Key 미등록: agencyCode={} credentialRef={} cid={}",
                    req.getAgencyCode(), credentialRef, cid);
            throw new PlatformException(PlatformErrorCode.AGENCY_KEY_INVALID, cid);
        }

        // ③ JWT 서명 검증 (HMAC-SHA256)
        Claims claims = verifyAndExtractClaims(req.getSignedRequest(), apiKey, req.getAgencyCode(), cid);

        // ④ JWT 발급 시각 검증 (signedRequestMaxAgeMinutes 이내)
        Instant issuedAt = claims.getIssuedAt() != null
                ? claims.getIssuedAt().toInstant()
                : Instant.EPOCH;

        if (issuedAt.plus(Duration.ofMinutes(signedRequestMaxAgeMinutes)).isBefore(Instant.now())) {
            log.warn("[ConversionInit] signed_request 만료: agencyCode={} iat={} cid={}",
                    req.getAgencyCode(), issuedAt, cid);
            throw new PlatformException(PlatformErrorCode.CONVERSION_REQUEST_EXPIRED, cid);
        }

        // ⑤ redirectUri 화이트리스트 검증 (CallbackUrlValidator — agency_meta.callback_whitelist)
        String redirectUri  = claims.get("redirectUri", String.class);
        callbackUrlValidator.validate(redirectUri, agency.getCallbackWhitelist(), cid);

        // ⑥ ConversionSession 생성 → Redis 저장
        String mbrId        = claims.get("mbrId",        String.class);
        String returnClient = claims.get("returnClient",  String.class);
        String userType     = claims.get("userType",      String.class);

        String sessionId = UUID.randomUUID().toString();
        Instant now      = Instant.now();

        ConversionSession session = ConversionSession.builder()
                .sessionId(sessionId)
                .agencyCode(req.getAgencyCode())
                .mbrId(mbrId)
                .redirectUri(redirectUri)
                .returnClient(returnClient)
                .userType(userType)
                .createdAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(sessionTtlMinutes)))
                .build();

        redisTemplate.opsForValue().set(
                REDIS_KEY_PREFIX + sessionId,
                session,
                Duration.ofMinutes(sessionTtlMinutes));

        log.info("[ConversionInit] ConversionSession 생성: sessionId={} agencyCode={} userType={} cid={}",
                sessionId, req.getAgencyCode(), userType, cid);

        return session;
    }

    // ── private ───────────────────────────────────────────────────────────

    /**
     * JWT HMAC-SHA256 서명 검증 + Claims 추출.
     *
     * @throws PlatformException CONVERSION_SIGNATURE_INVALID — 서명 불일치 또는 sub 불일치
     */
    private Claims verifyAndExtractClaims(String signedRequest, String apiKey,
                                          String expectedAgencyCode, String cid) {
        try {
            // jjwt 가 서명을 수행한다(JWT 계층). 키 타입은 jjwt 가 소유하므로 var 로 받는다.
            var key = Keys.hmacShaKeyFor(apiKey.getBytes(StandardCharsets.UTF_8));

            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(signedRequest)
                    .getPayload();

            // sub claim = agencyCode 일치 검증 (기관 사칭 방지)
            if (!expectedAgencyCode.equals(claims.getSubject())) {
                log.warn("[ConversionInit] JWT sub 불일치: expected={} actual={} cid={}",
                        expectedAgencyCode, claims.getSubject(), cid);
                throw new PlatformException(PlatformErrorCode.CONVERSION_SIGNATURE_INVALID, cid);
            }

            return claims;

        } catch (JwtException e) {
            log.warn("[ConversionInit] JWT 서명 검증 실패: agencyCode={} error={} cid={}",
                    expectedAgencyCode, e.getMessage(), cid);
            throw new PlatformException(PlatformErrorCode.CONVERSION_SIGNATURE_INVALID, cid);
        }
    }

    /**
     * 기관 코드로 기본 자격증명 경로 생성.
     * 예: "BIZINFO_001" → "secrets/agency/BIZINFO_001/api-key"
     * → AgencyCredentialStore.toEnvVarName() 적용 → SECRETS_AGENCY_BIZINFO_001_API_KEY
     */
    private String buildDefaultCredentialRef(String agencyCode) {
        return "secrets/agency/" + agencyCode + "/api-key";
    }
}

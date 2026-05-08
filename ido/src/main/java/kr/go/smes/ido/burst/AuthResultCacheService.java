package kr.go.smes.ido.burst;

import kr.go.smes.common.domain.AuthResult;
import kr.go.smes.common.event.AuthEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 인증 완료 이벤트 Redis Pre-warming 서비스
 *
 * <p><b>60,000명 급증 대응 핵심 설계</b>:
 * <pre>
 * 문제: Q-Sign이 인증 완료 → 사용자가 Handoff 요청 → IdO가 DB에서 AuthResult 조회
 *       → 60,000명 동시 요청 시 DB I/O 폭발
 *
 * 해결: Q-Sign Outbox → Kafka(qsign.auth.events) → QsignAuthEventConsumer
 *       → AuthResultCacheService.preWarm() → Redis 캐시 (TTL 300초)
 *       → Handoff 요청 시 DB 조회 없이 Redis 즉시 응답
 * </pre>
 *
 * <p><b>Redis 키 구조</b>:
 * <ul>
 *   <li>{@code ido:auth_result:{correlationId}}  — correlationId 기반 캐시 (TTL 300s)</li>
 *   <li>{@code ido:auth_level:{correlationId}}   — authLevel 빠른 조회용 (TTL 300s)</li>
 * </ul>
 *
 * <p><b>TTL 설계 근거</b>:
 * Handoff Ticket TTL = 60s. 사용자가 인증 완료 후 기관 페이지로 이동하는 데
 * 최대 5분(300s) 이내로 Handoff 요청이 도착한다고 가정.
 * 60,000명 급증 시 DB 대신 Redis Hit Rate 99% 목표.
 *
 * <p><b>메모리 추정</b>:
 * 60,000건 × 약 512 bytes = 약 30 MB → Redis 단일 인스턴스 충분히 수용 가능.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthResultCacheService {

    private static final String AUTH_RESULT_PREFIX = "ido:auth_result:";
    private static final String AUTH_LEVEL_PREFIX  = "ido:auth_level:";

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${ido.burst.auth-result-cache-ttl-seconds:300}")
    private long authResultCacheTtlSeconds;

    // ── Pre-warming (인증 완료 이벤트 수신 즉시 호출) ─────────────────────

    /**
     * 인증 완료 결과를 Redis에 사전 적재 (Pre-warming)
     *
     * <p>QsignAuthEventConsumer.handleAuthCompleted() 에서 호출.
     * 이후 Handoff Issue 요청 시 DB 조회 없이 Redis에서 즉시 응답.
     *
     * @param event AUTH_COMPLETED AuthEvent
     */
    public void preWarm(AuthEvent event) {
        if (event == null || event.getCorrelationId() == null) return;

        String correlationId = event.getCorrelationId();

        try {
            // ① 인증 결과 전체 캐시 (Handoff Payload 구성에 사용)
            Map<String, Object> cacheValue = Map.of(
                    "correlationId",   correlationId,
                    "authResultId",    nullToEmpty(event.getAuthResultId()),
                    "qimUserId",       nullToEmpty(event.getQimUserId()),
                    "authLevel",       event.getAuthLevel() != null ? event.getAuthLevel().name() : "L1",
                    "providerCode",    nullToEmpty(event.getProviderCode()),
                    "cachedAt",        System.currentTimeMillis()
            );

            String resultKey = AUTH_RESULT_PREFIX + correlationId;
            redisTemplate.opsForValue().set(
                    resultKey, cacheValue, Duration.ofSeconds(authResultCacheTtlSeconds)
            );

            // ② authLevel 빠른 조회용 별도 키 (Handoff Issue 정책 체크에 사용)
            String levelKey = AUTH_LEVEL_PREFIX + correlationId;
            redisTemplate.opsForValue().set(
                    levelKey,
                    event.getAuthLevel() != null ? event.getAuthLevel().name() : "L1",
                    Duration.ofSeconds(authResultCacheTtlSeconds)
            );

            log.info("[AuthResultCacheService] Pre-warming 완료: correlationId={} authLevel={} ttl={}s",
                    correlationId, event.getAuthLevel(), authResultCacheTtlSeconds);

        } catch (Exception e) {
            // Redis 실패는 비치명적 — Handoff 시 DB 직접 조회로 폴백
            log.warn("[AuthResultCacheService] Pre-warming 실패 (DB 폴백 허용): correlationId={} error={}",
                    correlationId, e.getMessage());
        }
    }

    // ── 캐시 조회 ──────────────────────────────────────────────────────────

    /**
     * correlationId 로 캐시된 인증 결과 조회
     *
     * @return Optional.empty() → 캐시 미스 (DB 직접 조회 필요)
     */
    @SuppressWarnings("unchecked")
    public Optional<CachedAuthResult> get(String correlationId) {
        if (correlationId == null) return Optional.empty();

        try {
            String key = AUTH_RESULT_PREFIX + correlationId;
            Object raw = redisTemplate.opsForValue().get(key);

            if (raw instanceof Map<?, ?> map) {
                CachedAuthResult result = new CachedAuthResult(
                        (String) map.get("correlationId"),
                        (String) map.get("authResultId"),
                        (String) map.get("qimUserId"),
                        parseAuthLevel((String) map.get("authLevel")),
                        (String) map.get("providerCode")
                );
                log.debug("[AuthResultCacheService] HIT: correlationId={}", correlationId);
                return Optional.of(result);
            }

            log.debug("[AuthResultCacheService] MISS: correlationId={}", correlationId);
            return Optional.empty();

        } catch (Exception e) {
            log.warn("[AuthResultCacheService] 캐시 조회 실패 (MISS 처리): correlationId={} error={}",
                    correlationId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * authLevel 빠른 조회 (Handoff 정책 체크용)
     */
    public Optional<AuthResult.AuthLevel> getAuthLevel(String correlationId) {
        if (correlationId == null) return Optional.empty();

        try {
            String key = AUTH_LEVEL_PREFIX + correlationId;
            Object raw = redisTemplate.opsForValue().get(key);
            if (raw != null) {
                return Optional.of(parseAuthLevel(raw.toString()));
            }
        } catch (Exception e) {
            log.warn("[AuthResultCacheService] authLevel 조회 실패: correlationId={}", correlationId);
        }
        return Optional.empty();
    }

    /**
     * 사용 완료 후 즉시 무효화 (Handoff Ticket CONSUMED 시 호출)
     *
     * <p>보안 원칙: 1회 사용된 인증 결과는 캐시에서 제거하여
     * 재사용 공격 표면 최소화.
     */
    public void invalidate(String correlationId) {
        if (correlationId == null) return;
        try {
            redisTemplate.delete(AUTH_RESULT_PREFIX + correlationId);
            redisTemplate.delete(AUTH_LEVEL_PREFIX + correlationId);
            log.debug("[AuthResultCacheService] 무효화: correlationId={}", correlationId);
        } catch (Exception e) {
            log.warn("[AuthResultCacheService] 무효화 실패: correlationId={} error={}", correlationId, e.getMessage());
        }
    }

    // ── 유틸 ──────────────────────────────────────────────────────────────

    private AuthResult.AuthLevel parseAuthLevel(String level) {
        try {
            return AuthResult.AuthLevel.valueOf(level);
        } catch (Exception e) {
            return AuthResult.AuthLevel.L1;
        }
    }

    private String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    // ── 캐시 조회 결과 레코드 ─────────────────────────────────────────────

    /**
     * Redis에서 읽은 인증 결과 캐시 엔트리
     */
    public record CachedAuthResult(
            String correlationId,
            String authResultId,
            String qimUserId,
            AuthResult.AuthLevel authLevel,
            String providerCode
    ) {}
}

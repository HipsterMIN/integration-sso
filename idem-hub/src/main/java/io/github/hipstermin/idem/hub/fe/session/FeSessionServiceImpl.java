package io.github.hipstermin.idem.hub.fe.session;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * FE 세션 관리 구현체 (Redis 기반)
 * 설계서 §12.3 / §12.4 / §12.5 / §12.6 참조
 *
 * <p>Redis 키 구조:
 * <ul>
 *   <li>{@code fe:session:{feSessionId}}  — 세션 본체 (sliding TTL)</li>
 *   <li>{@code fe:user-sessions:{qimUserId}}  — 사용자별 세션 ID Set (invalidateByQimUserId 용)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeSessionServiceImpl implements FeSessionService {

    private static final String KEY_PREFIX      = "fe:session:";
    private static final String USER_SET_PREFIX = "fe:user-sessions:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${ido.fe.session.sliding-ttl-minutes:30}")
    private long slidingTtlMinutes;

    @Value("${ido.fe.session.absolute-timeout-minutes:480}")
    private long absoluteTimeoutMinutes;

    @Value("${ido.fe.allowed-return-urls:}")
    private List<String> allowedReturnUrls;

    // ── 세션 생성 ─────────────────────────────────────────────────────────

    @Override
    public FeSession create(String qimUserId, String authResultId,
                            String authLevel, String returnUrl) {
        String sessionId = generateSessionId();
        Instant now      = Instant.now();

        FeSession session = FeSession.builder()
                .feSessionId(sessionId)
                .qimUserId(qimUserId)
                .authResultId(authResultId)
                .authLevel(authLevel)
                .createdAt(now)
                .lastActivityAt(now)
                .absoluteExpiresAt(now.plus(Duration.ofMinutes(absoluteTimeoutMinutes)))
                .returnUrl(returnUrl)
                .advisoryFlag(false)
                .build();

        String key = KEY_PREFIX + sessionId;
        redisTemplate.opsForValue().set(key, session, Duration.ofMinutes(slidingTtlMinutes));

        // 사용자 → 세션 역인덱스 (invalidateByQimUserId 지원)
        String userSetKey = USER_SET_PREFIX + qimUserId;
        redisTemplate.opsForSet().add(userSetKey, sessionId);
        redisTemplate.expire(userSetKey, Duration.ofMinutes(absoluteTimeoutMinutes));

        log.info("[FeSession] 세션 생성 feSessionId={} qimUserId={} authLevel={}",
                sessionId, qimUserId, authLevel);
        return session;
    }

    // ── 세션 조회 ─────────────────────────────────────────────────────────

    @Override
    public Optional<FeSession> findById(String feSessionId) {
        Object raw = redisTemplate.opsForValue().get(KEY_PREFIX + feSessionId);
        if (raw == null) return Optional.empty();

        FeSession session = castSession(raw);
        if (session.isAbsoluteExpired()) {
            expire(feSessionId);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    // ── Sliding TTL 갱신 ──────────────────────────────────────────────────

    @Override
    public FeSession refresh(String feSessionId) {
        String key = KEY_PREFIX + feSessionId;
        Object raw = redisTemplate.opsForValue().get(key);
        if (raw == null) {
            throw new NoSuchElementException("FE 세션 없음: " + feSessionId);
        }

        FeSession old     = castSession(raw);
        FeSession updated = FeSession.builder()
                .feSessionId(old.getFeSessionId())
                .qimUserId(old.getQimUserId())
                .authResultId(old.getAuthResultId())
                .authLevel(old.getAuthLevel())
                .createdAt(old.getCreatedAt())
                .lastActivityAt(Instant.now())
                .absoluteExpiresAt(old.getAbsoluteExpiresAt())
                .returnUrl(old.getReturnUrl())
                .advisoryFlag(old.isAdvisoryFlag())
                .build();

        redisTemplate.opsForValue().set(key, updated, Duration.ofMinutes(slidingTtlMinutes));
        return updated;
    }

    // ── 세션 만료 ─────────────────────────────────────────────────────────

    @Override
    public void expire(String feSessionId) {
        String key = KEY_PREFIX + feSessionId;
        Object raw = redisTemplate.opsForValue().get(key);
        if (raw != null) {
            FeSession session = castSession(raw);
            String userSetKey = USER_SET_PREFIX + session.getQimUserId();
            redisTemplate.opsForSet().remove(userSetKey, feSessionId);
        }
        redisTemplate.delete(key);
        log.info("[FeSession] 세션 만료 feSessionId={}", feSessionId);
    }

    // ── returnUrl 화이트리스트 검증 (§12.6) ──────────────────────────────

    @Override
    public boolean isValidReturnUrl(String returnUrl) {
        if (returnUrl == null || returnUrl.isBlank()) return false;
        return allowedReturnUrls.stream()
                .filter(allowed -> allowed != null && !allowed.isBlank()) // 빈 항목이 모든 URL 을 통과시키지 않도록
                .anyMatch(allowed -> returnUrl.startsWith(allowed.trim()));
    }

    // ── 사용자별 세션 일괄 무효화 (MANDATORY_SECURITY) ────────────────────

    @Override
    public void invalidateByQimUserId(String qimUserId, String reason) {
        String userSetKey = USER_SET_PREFIX + qimUserId;
        Set<Object> sessionIds = redisTemplate.opsForSet().members(userSetKey);

        if (sessionIds == null || sessionIds.isEmpty()) {
            log.info("[FeSession] 무효화 대상 없음 qimUserId={}", qimUserId);
            return;
        }

        int count = 0;
        for (Object sid : sessionIds) {
            redisTemplate.delete(KEY_PREFIX + sid.toString());
            count++;
        }
        redisTemplate.delete(userSetKey);

        log.warn("[FeSession] MANDATORY 일괄 무효화 qimUserId={} count={} reason={}",
                qimUserId, count, reason);
    }

    // ── Advisory 플래그 설정 ──────────────────────────────────────────────

    @Override
    public void markAdvisoryFlag(String qimUserId, String reason) {
        String userSetKey = USER_SET_PREFIX + qimUserId;
        Set<Object> sessionIds = redisTemplate.opsForSet().members(userSetKey);

        if (sessionIds == null || sessionIds.isEmpty()) {
            log.debug("[FeSession] Advisory 대상 없음 qimUserId={}", qimUserId);
            return;
        }

        for (Object sid : sessionIds) {
            String key = KEY_PREFIX + sid.toString();
            Object raw = redisTemplate.opsForValue().get(key);
            if (raw != null) {
                FeSession updated = castSession(raw).withAdvisoryFlag(true);
                // TTL 유지 (getExpire 후 재설정)
                Long ttlSec = redisTemplate.getExpire(key);
                Duration ttl = (ttlSec != null && ttlSec > 0)
                        ? Duration.ofSeconds(ttlSec)
                        : Duration.ofMinutes(slidingTtlMinutes);
                redisTemplate.opsForValue().set(key, updated, ttl);
            }
        }

        log.info("[FeSession] Advisory 플래그 설정 qimUserId={} reason={}", qimUserId, reason);
    }

    // ── 내부 유틸 ─────────────────────────────────────────────────────────

    private String generateSessionId() {
        byte[] bytes = new byte[32];   // 256-bit 엔트로피
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @SuppressWarnings("unchecked")
    private FeSession castSession(Object raw) {
        if (raw instanceof FeSession session) return session;
        // LinkedHashMap (Jackson 역직렬화 fallback) 처리
        if (raw instanceof Map<?,?> map) {
            Map<String, Object> m = (Map<String, Object>) map;
            return FeSession.builder()
                    .feSessionId(str(m, "feSessionId"))
                    .qimUserId(str(m, "qimUserId"))
                    .authResultId(str(m, "authResultId"))
                    .authLevel(str(m, "authLevel"))
                    .createdAt(parseInstant(m.get("createdAt")))
                    .lastActivityAt(parseInstant(m.get("lastActivityAt")))
                    .absoluteExpiresAt(parseInstant(m.get("absoluteExpiresAt")))
                    .returnUrl(str(m, "returnUrl"))
                    .advisoryFlag(Boolean.TRUE.equals(m.get("advisoryFlag")))
                    .build();
        }
        throw new IllegalStateException("Redis 역직렬화 실패: " + raw.getClass());
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    private Instant parseInstant(Object v) {
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        return Instant.parse(v.toString());
    }
}

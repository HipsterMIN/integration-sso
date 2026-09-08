package io.github.hipstermin.idem.hub.ratelimit;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis 기반 기관별 Rate Limiter (Sliding Window + Token Bucket 혼합)
 *
 * <p><b>알고리즘</b>: Lua 스크립트 기반 Sliding Window Counter
 * <ul>
 *   <li>1초 윈도우 내 TPS 제한 (기관별 독립)</li>
 *   <li>일별 최대 발급 건수 제한 (daily quota)</li>
 *   <li>Lua 스크립트로 원자적 처리 — Redis 단일 노드에서 race-condition 없음</li>
 * </ul>
 *
 * <p><b>Redis 키 구조</b>:
 * <pre>
 *   ido:rl:tps:{agencyCode}:{epochSecond}   → 초당 카운터 (TTL 2초)
 *   ido:rl:daily:{agencyCode}:{yyyyMMdd}    → 일별 카운터 (TTL 25시간)
 * </pre>
 *
 * <p><b>설계 기준</b>: 기관 1개당 기본 200 TPS / 일 1,000,000건
 * (agency_meta.daily_lookup_limit 개별 설정 가능)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgencyRateLimiter {

    private static final String TPS_KEY_PREFIX   = "ido:rl:tps:";
    private static final String DAILY_KEY_PREFIX = "ido:rl:daily:";

    // Lua: INCR + EXPIRE 원자적 실행 (TPS 슬라이딩 윈도우)
    private static final String TPS_LUA = """
            local key   = KEYS[1]
            local limit = tonumber(ARGV[1])
            local ttl   = tonumber(ARGV[2])
            local cur   = redis.call('INCR', key)
            if cur == 1 then
                redis.call('EXPIRE', key, ttl)
            end
            if cur > limit then
                return 0
            else
                return 1
            end
            """;

    // Lua: INCR + 일별 TTL 설정 원자적 실행
    private static final String DAILY_LUA = """
            local key   = KEYS[1]
            local limit = tonumber(ARGV[1])
            local ttl   = tonumber(ARGV[2])
            local cur   = redis.call('INCR', key)
            if cur == 1 then
                redis.call('EXPIRE', key, ttl)
            end
            if cur > limit then
                return 0
            else
                return 1
            end
            """;

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${ido.rate-limit.default-tps:200}")
    private int defaultTps;

    @Value("${ido.rate-limit.default-daily-limit:1000000}")
    private long defaultDailyLimit;

    @Value("${ido.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    /**
     * TPS + 일별 Rate Limit 통합 검사
     *
     * @param agencyCode    기관 코드
     * @param agencyTpsLimit 기관별 TPS 한도 (null이면 기본값 사용)
     * @param agencyDailyLimit 기관별 일일 한도 (null이면 기본값 사용)
     * @return true=허용, false=차단
     */
    public boolean tryAcquire(String agencyCode, Integer agencyTpsLimit, Long agencyDailyLimit) {
        if (!rateLimitEnabled) return true;

        int  tpsLimit   = agencyTpsLimit   != null ? agencyTpsLimit   : defaultTps;
        long dailyLimit = agencyDailyLimit != null ? agencyDailyLimit : defaultDailyLimit;

        // 1. TPS 검사
        if (!checkTps(agencyCode, tpsLimit)) {
            log.warn("[RateLimit] TPS 초과: agencyCode={} limit={}/s", agencyCode, tpsLimit);
            return false;
        }

        // 2. 일별 쿼터 검사
        if (!checkDaily(agencyCode, dailyLimit)) {
            log.warn("[RateLimit] 일별 한도 초과: agencyCode={} limit={}/day", agencyCode, dailyLimit);
            return false;
        }

        return true;
    }

    /**
     * TPS 검사 (편의 메서드 — 기관 설정 없이 기본값 사용)
     */
    public boolean tryAcquire(String agencyCode) {
        return tryAcquire(agencyCode, null, null);
    }

    /**
     * 현재 TPS 카운터 조회 (모니터링용)
     */
    public long getCurrentTps(String agencyCode) {
        String key = tpsKey(agencyCode);
        Object val = redisTemplate.opsForValue().get(key);
        if (val == null) return 0L;
        return Long.parseLong(val.toString());
    }

    /**
     * 현재 일별 카운터 조회 (모니터링용)
     */
    public long getCurrentDailyCount(String agencyCode) {
        String key = dailyKey(agencyCode);
        Object val = redisTemplate.opsForValue().get(key);
        if (val == null) return 0L;
        return Long.parseLong(val.toString());
    }

    // ── private ──────────────────────────────────────────────────────────────

    private boolean checkTps(String agencyCode, int limit) {
        try {
            String key = tpsKey(agencyCode);
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(TPS_LUA, Long.class);
            Long result = redisTemplate.execute(script,
                    List.of(key),
                    String.valueOf(limit),
                    "2"   // TTL 2초 (슬라이딩 윈도우 1초 + 여유)
            );
            return result != null && result == 1L;
        } catch (Exception e) {
            log.error("[RateLimit] TPS Redis 오류 — 허용 처리 (fail-open): agencyCode={} err={}",
                    agencyCode, e.getMessage());
            return true; // fail-open: Redis 장애 시 허용
        }
    }

    private boolean checkDaily(String agencyCode, long limit) {
        try {
            String key = dailyKey(agencyCode);
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(DAILY_LUA, Long.class);
            Long result = redisTemplate.execute(script,
                    List.of(key),
                    String.valueOf(limit),
                    String.valueOf(Duration.ofHours(25).toSeconds()) // 25시간 TTL
            );
            return result != null && result == 1L;
        } catch (Exception e) {
            log.error("[RateLimit] Daily Redis 오류 — 허용 처리 (fail-open): agencyCode={} err={}",
                    agencyCode, e.getMessage());
            return true;
        }
    }

    private String tpsKey(String agencyCode) {
        long epochSecond = java.time.Instant.now().getEpochSecond();
        return TPS_KEY_PREFIX + agencyCode + ":" + epochSecond;
    }

    private String dailyKey(String agencyCode) {
        String date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        return DAILY_KEY_PREFIX + agencyCode + ":" + date;
    }
}

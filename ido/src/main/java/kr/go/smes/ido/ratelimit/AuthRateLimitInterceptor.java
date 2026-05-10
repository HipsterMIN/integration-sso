package kr.go.smes.ido.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * Auth 엔드포인트 IP 기반 Rate Limit 인터셉터 (S9-T7)
 *
 * <p><b>목적</b>:
 * {@code /api/v1/auth/**} 경로에 대한 IP 단위 남용 방지.
 * Bot, 무차별 대입(brute-force), DoS 공격으로부터 NICE/OACX 인증 API 보호.
 *
 * <p><b>제한 정책</b>:
 * <table border="1">
 *   <tr><th>윈도우</th><th>기본 한도</th><th>환경 변수</th></tr>
 *   <tr><td>초당 (TPS)</td><td>20 req/s</td><td>IDO_AUTH_RL_TPS</td></tr>
 *   <tr><td>분당</td><td>100 req/min</td><td>IDO_AUTH_RL_PER_MIN</td></tr>
 *   <tr><td>일별</td><td>1,000 req/day</td><td>IDO_AUTH_RL_DAILY</td></tr>
 * </table>
 *
 * <p><b>Redis 키 구조</b>:
 * <pre>
 *   ido:auth-rl:tps:{ip}:{epochSecond}     → 초당 카운터 (TTL 2s)
 *   ido:auth-rl:min:{ip}:{epochMinute}     → 분당 카운터 (TTL 70s)
 *   ido:auth-rl:daily:{ip}:{yyyyMMdd}      → 일별 카운터 (TTL 25h)
 * </pre>
 *
 * <p><b>IP 추출 우선순위</b>:
 * X-Forwarded-For → X-Real-IP → RemoteAddr (Nginx 리버스 프록시 환경 고려)
 *
 * <p><b>fail-open 정책</b>:
 * Redis 장애 시 모든 요청을 허용 (서비스 가용성 우선).
 * 장애 발생 시 경고 로그 남김.
 *
 * <p><b>응답 헤더</b>:
 * {@code X-RateLimit-Limit}, {@code X-RateLimit-Remaining},
 * {@code Retry-After} 헤더로 클라이언트에 한도 정보 전달.
 *
 * @see kr.go.smes.ido.ratelimit.AgencyRateLimiter
 * @see kr.go.smes.ido.fe.config.IdoWebMvcConfig
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthRateLimitInterceptor implements HandlerInterceptor {

    // ── Redis 키 접두사 ────────────────────────────────────────────────────
    private static final String TPS_KEY_PREFIX   = "ido:auth-rl:tps:";
    private static final String MIN_KEY_PREFIX   = "ido:auth-rl:min:";
    private static final String DAILY_KEY_PREFIX = "ido:auth-rl:daily:";

    // ── Lua 스크립트: INCR + EXPIRE 원자적 처리 ──────────────────────────
    private static final String RATE_LIMIT_LUA = """
            local key   = KEYS[1]
            local limit = tonumber(ARGV[1])
            local ttl   = tonumber(ARGV[2])
            local cur   = redis.call('INCR', key)
            if cur == 1 then
                redis.call('EXPIRE', key, ttl)
            end
            return cur
            """;

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${ido.auth.rate-limit.enabled:${IDO_RATE_LIMIT_ENABLED:true}}")
    private boolean rateLimitEnabled;

    @Value("${ido.auth.rate-limit.tps:${IDO_RATE_LIMIT_DEFAULT_TPS:20}}")
    private int tpsLimit;

    @Value("${ido.auth.rate-limit.per-minute:100}")
    private int perMinuteLimit;

    @Value("${ido.auth.rate-limit.daily:${IDO_RATE_LIMIT_DEFAULT_DAILY:1000}}")
    private int dailyLimit;

    @Override
    public boolean preHandle(HttpServletRequest request,
                              HttpServletResponse response,
                              Object handler) throws Exception {

        if (!rateLimitEnabled) return true;

        String clientIp = extractClientIp(request);
        String method   = request.getMethod();
        String path     = request.getRequestURI();

        // OPTIONS(CORS preflight)는 Rate Limit 제외
        if ("OPTIONS".equalsIgnoreCase(method)) return true;

        // ① TPS 검사
        int currentTps = incrementAndGet(tpsKeyFor(clientIp), tpsLimit, 2);
        if (currentTps > tpsLimit) {
            log.warn("[AuthRateLimit] TPS 초과: ip={} path={} count={}/s limit={}",
                    clientIp, path, currentTps, tpsLimit);
            writeRateLimitResponse(response, tpsLimit, 0, 1);
            return false;
        }

        // ② 분당 검사
        int currentMin = incrementAndGet(minKeyFor(clientIp), perMinuteLimit, 70);
        if (currentMin > perMinuteLimit) {
            log.warn("[AuthRateLimit] 분당 한도 초과: ip={} path={} count={}/min limit={}",
                    clientIp, path, currentMin, perMinuteLimit);
            writeRateLimitResponse(response, perMinuteLimit, 0, 60);
            return false;
        }

        // ③ 일별 검사
        int currentDaily = incrementAndGet(dailyKeyFor(clientIp), dailyLimit, 90000); // 25h
        if (currentDaily > dailyLimit) {
            log.warn("[AuthRateLimit] 일별 한도 초과: ip={} path={} count={}/day limit={}",
                    clientIp, path, currentDaily, dailyLimit);
            writeRateLimitResponse(response, dailyLimit, 0, 86400);
            return false;
        }

        // ④ Rate Limit 헤더 추가 (정상 응답)
        response.setHeader("X-RateLimit-Limit-Second", String.valueOf(tpsLimit));
        response.setHeader("X-RateLimit-Remaining-Second", String.valueOf(Math.max(0, tpsLimit - currentTps)));
        response.setHeader("X-RateLimit-Limit-Day", String.valueOf(dailyLimit));
        response.setHeader("X-RateLimit-Remaining-Day", String.valueOf(Math.max(0, dailyLimit - currentDaily)));

        return true;
    }

    // ── private 헬퍼 ─────────────────────────────────────────────────────

    /**
     * Lua 스크립트로 원자적 INCR 후 현재 카운터 반환
     *
     * @param key   Redis 키
     * @param limit 한도 (로그용)
     * @param ttl   만료 초
     * @return 현재 카운터 값 (fail-open: Redis 장애 시 0 반환)
     */
    private int incrementAndGet(String key, int limit, int ttl) {
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(RATE_LIMIT_LUA, Long.class);
            Long result = redisTemplate.execute(script,
                    List.of(key),
                    String.valueOf(limit),
                    String.valueOf(ttl));
            return result == null ? 0 : result.intValue();
        } catch (Exception e) {
            log.error("[AuthRateLimit] Redis 오류 — fail-open 허용: key={} err={}", key, e.getMessage());
            return 0; // fail-open: 장애 시 허용
        }
    }

    /**
     * 429 Too Many Requests 응답 작성
     *
     * @param response     HttpServletResponse
     * @param limit        요청 한도
     * @param remaining    남은 요청 수
     * @param retryAfterSec Retry-After 초
     */
    private void writeRateLimitResponse(HttpServletResponse response,
                                         int limit, int remaining,
                                         int retryAfterSec) throws Exception {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", String.valueOf(retryAfterSec));
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));

        response.getWriter().write("""
                {
                  "status": 429,
                  "error": "Too Many Requests",
                  "message": "인증 API 요청 횟수 한도를 초과했습니다. 잠시 후 다시 시도해 주세요.",
                  "retryAfter": %d
                }
                """.formatted(retryAfterSec));
        response.getWriter().flush();
    }

    /**
     * 클라이언트 IP 추출 (Nginx 리버스 프록시 환경 고려)
     *
     * <p>우선순위: X-Forwarded-For (첫 번째 IP) → X-Real-IP → RemoteAddr
     */
    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            // "client, proxy1, proxy2" 형식에서 첫 번째 IP만 추출
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }

    // ── Redis 키 생성 ─────────────────────────────────────────────────────

    private String tpsKeyFor(String ip) {
        long epochSecond = java.time.Instant.now().getEpochSecond();
        return TPS_KEY_PREFIX + ip + ":" + epochSecond;
    }

    private String minKeyFor(String ip) {
        long epochMinute = java.time.Instant.now().getEpochSecond() / 60;
        return MIN_KEY_PREFIX + ip + ":" + epochMinute;
    }

    private String dailyKeyFor(String ip) {
        String date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        return DAILY_KEY_PREFIX + ip + ":" + date;
    }
}

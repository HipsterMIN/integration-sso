package io.github.hipstermin.idem.hub.ratelimit;

import io.github.hipstermin.idem.common.event.AuditLogEvent;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

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
 *   <tr><td>초당 (TPS)</td><td>20 req/s</td><td>IDEM_HUB_AUTH_RL_TPS</td></tr>
 *   <tr><td>분당</td><td>100 req/min</td><td>IDEM_HUB_AUTH_RL_PER_MIN</td></tr>
 *   <tr><td>일별</td><td>1,000 req/day</td><td>IDEM_HUB_AUTH_RL_DAILY</td></tr>
 * </table>
 *
 * <p><b>Redis 키 구조</b>:
 * <pre>
 *   idem:auth-rl:tps:{ip}:{epochSecond}     → 초당 카운터 (TTL 2s)
 *   idem:auth-rl:min:{ip}:{epochMinute}     → 분당 카운터 (TTL 70s)
 *   idem:auth-rl:daily:{ip}:{yyyyMMdd}      → 일별 카운터 (TTL 25h)
 * </pre>
 *
 * <p><b>IP 추출 우선순위</b>:
 * X-Forwarded-For → X-Real-IP → RemoteAddr (Nginx 리버스 프록시 환경 고려)
 *
 * <p><b>장애 정책 (D2 fail-secure)</b>:
 * Redis 를 셀 수 없으면 인증 API 요청을 503 으로 거부하고 감사 기록을 남긴다. 종전의 "장애 시 전부 허용" 은
 * Lua 인자 직렬화 결함과 결합해 레이트리밋을 상시 무력화하고 있었다.
 *
 * <p><b>응답 헤더</b>:
 * {@code X-RateLimit-Limit}, {@code X-RateLimit-Remaining},
 * {@code Retry-After} 헤더로 클라이언트에 한도 정보 전달.
 *
 * @see io.github.hipstermin.idem.hub.ratelimit.AgencyRateLimiter
 * @see io.github.hipstermin.idem.hub.fe.config.IdoWebMvcConfig
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthRateLimitInterceptor implements HandlerInterceptor {

    /** D3: 일 단위 키의 날짜 경계 시간대 — idem.hub.zone (기본 UTC) */
    @org.springframework.beans.factory.annotation.Value("${idem.hub.zone:UTC}")
    private String zoneId = "UTC";

    // ── Redis 키 접두사 ────────────────────────────────────────────────────
    private static final String TPS_KEY_PREFIX   = "idem:auth-rl:tps:";
    private static final String MIN_KEY_PREFIX   = "idem:auth-rl:min:";
    private static final String DAILY_KEY_PREFIX = "idem:auth-rl:daily:";

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

    /**
     * D2: Lua ARGV 는 문자열 그대로 보낸다. RedisTemplate 기본 값 직렬화기(JSON)를 쓰면 "20" 이 {@code "\"20\""} 로 전달되어
     * {@code tonumber(ARGV[2])} 가 nil → EXPIRE 실패 → 예외 → 종전 fail-open 으로 레이트리밋이 상시 무력화되어 있었다
     * (AgencyRateLimiter 는 2026-09-08 에 고쳤으나 이 클래스는 누락).
     */
    private static final RedisSerializer<String> LUA_ARGS_SERIALIZER   = new StringRedisSerializer();
    private static final RedisSerializer<Long>   LUA_RESULT_SERIALIZER = new GenericToStringSerializer<>(Long.class);

    /** Redis 장애 시 카운터를 셀 수 없다는 뜻 — D2: 통과가 아니라 거부(503)+감사 */
    static final class RateLimitBackendUnavailable extends RuntimeException {
        RateLimitBackendUnavailable(String message, Throwable cause) { super(message, cause); }
    }

    private final RedisTemplate<String, Object> redisTemplate;
    private final AuditLogPublisher             auditLogPublisher;

    // F-01: IP Auth Rate Limiting 독립 스위치 (F-02 기관 RL의 IDEM_HUB_RATE_LIMIT_ENABLED와 완전 분리)
    // 로컬/개발: IDEM_HUB_AUTH_RL_ENABLED=false 권장 (반복 테스트 시 자기 IP 차단 방지)
    // 운영: IDEM_HUB_AUTH_RL_ENABLED=true (기본값)
    @Value("${idem.hub.auth.rate-limit.enabled:${IDEM_HUB_AUTH_RL_ENABLED:true}}")
    private boolean rateLimitEnabled;

    @Value("${idem.hub.auth.rate-limit.tps:${IDEM_HUB_RATE_LIMIT_DEFAULT_TPS:20}}")
    private int tpsLimit;

    @Value("${idem.hub.auth.rate-limit.per-minute:100}")
    private int perMinuteLimit;

    @Value("${idem.hub.auth.rate-limit.daily:${IDEM_HUB_RATE_LIMIT_DEFAULT_DAILY:1000}}")
    private int dailyLimit;

    @Override
    public boolean preHandle(HttpServletRequest request,
                              HttpServletResponse response,
                              Object handler) throws Exception {

        if (!rateLimitEnabled) {
            log.debug("[AuthRateLimit] DISABLED — 모든 /api/v1/auth/** 요청 무제한 허용 (IDEM_HUB_AUTH_RL_ENABLED=false)");
            return true;
        }

        String clientIp = extractClientIp(request);
        String method   = request.getMethod();
        String path     = request.getRequestURI();

        // OPTIONS(CORS preflight)는 Rate Limit 제외
        if ("OPTIONS".equalsIgnoreCase(method)) return true;

        int currentTps;
        int currentMin;
        int currentDaily;
        try {
            // ① TPS 검사
            currentTps = incrementAndGet(tpsKeyFor(clientIp), tpsLimit, 2);
        if (currentTps > tpsLimit) {
            log.warn("[AuthRateLimit] TPS 초과: ip={} path={} count={}/s limit={}",
                    clientIp, path, currentTps, tpsLimit);
            writeRateLimitResponse(response, tpsLimit, 0, 1);
            return false;
        }

            // ② 분당 검사
            currentMin = incrementAndGet(minKeyFor(clientIp), perMinuteLimit, 70);
        if (currentMin > perMinuteLimit) {
            log.warn("[AuthRateLimit] 분당 한도 초과: ip={} path={} count={}/min limit={}",
                    clientIp, path, currentMin, perMinuteLimit);
            writeRateLimitResponse(response, perMinuteLimit, 0, 60);
            return false;
        }

            // ③ 일별 검사
            currentDaily = incrementAndGet(dailyKeyFor(clientIp), dailyLimit, 90000); // 25h
        } catch (RateLimitBackendUnavailable e) {
            // D2 fail-secure: Redis 를 못 세면 인증 API 를 열어 두지 않는다 — 503 + 감사 기록
            log.error("[AuthRateLimit] Redis 장애 — 안전 우선 거부(503): ip={} path={} err={}",
                    clientIp, path, e.getMessage());
            auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                    .eventCategory(AuditLogEvent.CATEGORY_SYSTEM)
                    .eventAction("RATE_LIMIT_BACKEND_UNAVAILABLE")
                    .actorType(AuditLogEvent.ACTOR_USER)
                    .actorId(clientIp)
                    .resourceType("AUTH_ENDPOINT")
                    .resourceId(path)
                    .outcome(AuditLogEvent.OUTCOME_FAILURE)
                    .outcomeDetail("Redis unavailable — request denied (fail-secure)")
                    .metadata(Map.of("error", String.valueOf(e.getMessage())))
                    .build());
            writeUnavailableResponse(response);
            return false;
        }
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
     * @return 현재 카운터 값
     * @throws RateLimitBackendUnavailable Redis 장애 (호출부가 503 + 감사)
     */
    private int incrementAndGet(String key, int limit, int ttl) {
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(RATE_LIMIT_LUA, Long.class);
            Long result = redisTemplate.execute(script,
                    LUA_ARGS_SERIALIZER, LUA_RESULT_SERIALIZER,
                    List.of(key),
                    String.valueOf(limit),
                    String.valueOf(ttl));
            if (result == null) {
                throw new RateLimitBackendUnavailable("Redis returned null for " + key, null);
            }
            return result.intValue();
        } catch (RateLimitBackendUnavailable e) {
            throw e;
        } catch (Exception e) {
            throw new RateLimitBackendUnavailable(e.getMessage(), e);
        }
    }

    private void writeUnavailableResponse(HttpServletResponse response) throws Exception {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", "5");
        response.getWriter().write("""
                {
                  "status": 503,
                  "error": "Service Unavailable",
                  "code": "E-IDO-116",
                  "message": "요청 한도를 확인할 수 없어 인증 요청을 처리하지 않습니다. 잠시 후 다시 시도해 주세요."
                }
                """);
        response.getWriter().flush();
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
        String date = java.time.LocalDate.now(java.time.ZoneId.of(zoneId))
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        return DAILY_KEY_PREFIX + ip + ":" + date;
    }
}

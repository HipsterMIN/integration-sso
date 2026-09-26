package io.github.hipstermin.idem.gate.oidcfront;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 공개 OIDC 프런트 IP 레이트리밋 (1.0.1, 3차 점검 M17) — {@code /realms/**} 만. hub 의 {@code AuthRateLimitInterceptor} 와 같은 INCR+EXPIRE Lua.
 * <pre>
 *   idem:gate:front-rl:sec:{ip}:{epochSecond} → 초당 카운터 (TTL 2s)
 *   idem:gate:front-rl:min:{ip}:{epochMinute} → 분당 카운터 (TTL 70s)
 * </pre>
 * 한도 초과는 429(JSON {@code error=rate_limited}, Retry-After). Redis 를 셀 수 없으면 fail-closed 503 — 이 경로는 인증 관문이라 열어 두지 않는다
 * (D2 원칙, hub 와 같다). 끄려면 {@code idem.gate.oidc-front.rate-limit.enabled=false}.
 *
 * <p>빈 등록은 {@link OidcFrontConfig} 의 {@code FilterRegistrationBean} — {@code @Component} 필터로 두면 {@code @WebMvcTest} 슬라이스가
 * Redis 없이 이 필터를 끌어와 컨텍스트가 깨진다.
 */
@Slf4j
@RequiredArgsConstructor
public class OidcFrontRateLimitFilter extends OncePerRequestFilter {

    static final String SEC_PREFIX = "idem:gate:front-rl:sec:";
    static final String MIN_PREFIX = "idem:gate:front-rl:min:";
    private static final String LUA = """
            local cur = redis.call('INCR', KEYS[1])
            if cur == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1])) end
            return cur
            """;
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(LUA, Long.class);

    private final StringRedisTemplate redis;
    private final OidcFrontProperties props;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !props.getRateLimit().isEnabled() || path == null || !path.startsWith("/realms/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        OidcFrontProperties.RateLimit rl = props.getRateLimit();
        String ip = clientIp(request);
        long now = Instant.now().getEpochSecond();
        long sec;
        long min;
        try {
            sec = count(SEC_PREFIX + ip + ":" + now, 2);
            if (sec > rl.getPerSecond()) {
                reject(response, request, ip, 1, "초당");
                return;
            }
            min = count(MIN_PREFIX + ip + ":" + (now / 60), 70);
        } catch (RuntimeException e) {
            log.error("[OIDC-FRONT-RL] Redis 장애 — 안전 우선 거부(503) ip={} path={} err={}", ip, request.getRequestURI(), e.getMessage());
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", "5");
            response.getWriter().write("{\"error\":\"temporarily_unavailable\",\"error_description\":\"rate limit backend unavailable\"}");
            return;
        }
        if (min > rl.getPerMinute()) {
            reject(response, request, ip, 60, "분당");
            return;
        }
        response.setHeader("X-RateLimit-Limit-Second", String.valueOf(rl.getPerSecond()));
        response.setHeader("X-RateLimit-Remaining-Second", String.valueOf(Math.max(0, rl.getPerSecond() - sec)));
        chain.doFilter(request, response);
    }

    private long count(String key, int ttlSeconds) {
        Long r = redis.execute(SCRIPT, List.of(key), String.valueOf(ttlSeconds));
        if (r == null) throw new IllegalStateException("null from INCR " + key);
        return r;
    }

    private void reject(HttpServletResponse response, HttpServletRequest request, String ip, int retryAfter, String which) throws IOException {
        log.warn("[OIDC-FRONT-RL] {} 한도 초과 ip={} path={}", which, ip, request.getRequestURI());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"error\":\"rate_limited\",\"error_description\":\"too many requests\"}");
    }

    /**
     * 클라이언트 IP — 기본은 소켓 주소. {@code trust-forwarded-for=true} 면 {@code X-Forwarded-For} 의 <b>마지막</b> 값(바로 앞 프록시가 붙인 것).
     * 첫 값을 믿으면 호출자가 헤더 하나로 한도를 피해 간다.
     */
    static String clientIp(HttpServletRequest request, boolean trustForwardedFor) {
        if (trustForwardedFor) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] hops = xff.split(",");
                String last = hops[hops.length - 1].trim();
                if (!last.isEmpty()) return last;
            }
        }
        return request.getRemoteAddr();
    }

    private String clientIp(HttpServletRequest request) {
        return clientIp(request, props.getRateLimit().isTrustForwardedFor());
    }
}

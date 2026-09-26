package io.github.hipstermin.idem.gate.oidcfront;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Back-Channel Logout 토큰 재사용 방지 (1.0.1, 3차 점검 M4) — {@code jti} 를 토큰이 살아 있는 동안 Redis 에 기억한다.
 * <pre>
 *   idem:gate:bc-logout:jti:{jti} → "1" (TTL = exp 까지, exp 없으면 max-age)
 * </pre>
 * SET NX 라 여러 gate 인스턴스가 같은 토큰을 동시에 받아도 하나만 처리한다.
 *
 * <p>Redis 장애 시에는 <b>처리한다</b>(fail-open, ERROR 로그): 재사용된 logout 토큰이 할 수 있는 일은 같은 세션을 한 번 더 끝내는 것뿐이고,
 * 반대로 logout 을 버리면 끝나야 할 세션이 남는다 — 이 엔드포인트에서는 후자가 더 위험하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogoutTokenReplayGuard {

    static final String PREFIX = "idem:gate:bc-logout:jti:";

    private final StringRedisTemplate redis;

    /** 처음 보는 jti 면 true(기억하고), 이미 본 jti 면 false. */
    public boolean firstUse(String jti, Duration ttl) {
        try {
            Boolean first = redis.opsForValue().setIfAbsent(PREFIX + jti, "1", ttl.isNegative() || ttl.isZero() ? Duration.ofSeconds(1) : ttl);
            return Boolean.TRUE.equals(first);
        } catch (RuntimeException e) {
            log.error("[BC-LOGOUT] jti 재사용 검사 실패(Redis) — 토큰을 처리한다: {}", e.getMessage());
            return true;
        }
    }
}

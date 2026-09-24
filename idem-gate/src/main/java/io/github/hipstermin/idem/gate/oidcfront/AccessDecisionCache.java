package io.github.hipstermin.idem.gate.oidcfront;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 토큰 교환 시 판정을 (client, sub) 로 캐시 — userinfo 보강이 매번 hub 를 부르지 않게. 장애 시 캐시 없음으로 취급. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessDecisionCache {

    static final String PREFIX = "qsign:oidcrp:access:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public void put(String clientId, String sub, AccessDecision decision, long ttlSeconds) {
        try {
            redis.opsForValue().set(PREFIX + clientId + ":" + sub, objectMapper.writeValueAsString(decision),
                    Duration.ofSeconds(Math.max(1, ttlSeconds)));
        } catch (Exception e) {
            log.warn("[OIDC-FRONT] 판정 캐시 저장 실패 (비치명적): {}", e.getMessage());
        }
    }

    public Optional<AccessDecision> get(String clientId, String sub) {
        try {
            String json = redis.opsForValue().get(PREFIX + clientId + ":" + sub);
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, AccessDecision.class));
        } catch (Exception e) {
            log.warn("[OIDC-FRONT] 판정 캐시 조회 실패 (비치명적): {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void evict(String clientId, String sub) {
        try {
            redis.delete(PREFIX + clientId + ":" + sub);
        } catch (Exception ignored) {
            // 캐시 삭제 실패는 TTL 이 처리한다
        }
    }
}

package io.github.hipstermin.idem.hub.login;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 1.1 로그인 프런트 진입 상태 저장소 — {@code idem:handoff:login:{requestId}} (TTL 10분).
 *
 * <p>진입(entry) 에서 만들고, 제공자 선택·initiate 로 갱신하며, 발급이 끝나면 지운다(1회).
 * 브라우저에는 requestId 만 오간다 — 콜백 URL·기관 코드는 여기 있는 것을 쓰므로 쿼리 조작으로 바꿀 수 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HandoffLoginRequestStore {

    static final String KEY_PREFIX = "idem:handoff:login:";
    static final Duration TTL = Duration.ofMinutes(10);

    private static final ObjectMapper OM = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final StringRedisTemplate redis;

    public static String newRequestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public void save(HandoffLoginRequest req) {
        try {
            redis.opsForValue().set(KEY_PREFIX + req.requestId(), OM.writeValueAsString(req), TTL);
        } catch (Exception e) {
            throw new IllegalStateException("로그인 진입 상태 저장 실패", e);
        }
    }

    public Optional<HandoffLoginRequest> find(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) return Optional.empty();
        String json = redis.opsForValue().get(KEY_PREFIX + requestId);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(OM.readValue(json, HandoffLoginRequest.class));
        } catch (Exception e) {
            log.warn("[HandoffLogin] 진입 상태 해석 실패 — 폐기: req={} err={}", requestId, e.getMessage());
            redis.delete(KEY_PREFIX + requestId);
            return Optional.empty();
        }
    }

    public void delete(String requestId) {
        redis.delete(KEY_PREFIX + requestId);
    }
}

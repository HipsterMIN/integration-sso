package kr.go.smes.qsign.broker.state;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * OIDC Authorization Code Flow — state / nonce Redis 저장소
 *
 * <p>CSRF 방어 및 replay attack 방지를 위해 state, nonce 를 Redis 에
 * 단기 TTL 로 저장한다. callback 수신 시 검증 후 즉시 삭제(1회성).
 *
 * <p>Redis 키 구조:
 * <pre>
 *   oidc:state:{state}  → JSON { nonce, correlationId, returnUrl, requestedLevel }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OidcStateStore {

    private static final String KEY_PREFIX = "oidc:state:";

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${qsign.oidc.state-ttl-seconds:300}")
    private long stateTtlSeconds;

    /** state + nonce 를 생성하고 Redis 에 저장 */
    public OidcStateEntry create(String correlationId, String returnUrl, String requestedLevel) {
        String state = UUID.randomUUID().toString().replace("-", "");
        String nonce = UUID.randomUUID().toString().replace("-", "");

        OidcStateEntry entry = OidcStateEntry.builder()
                .state(state)
                .nonce(nonce)
                .correlationId(correlationId)
                .returnUrl(returnUrl)
                .requestedLevel(requestedLevel)
                .build();

        stringRedisTemplate.opsForValue().set(
                KEY_PREFIX + state,
                entry.toJson(),
                Duration.ofSeconds(stateTtlSeconds)
        );

        log.debug("[OidcStateStore] state 저장: correlationId={} ttl={}s", correlationId, stateTtlSeconds);
        return entry;
    }

    /**
     * callback 수신 시 state 검증 후 1회 소비(삭제)
     * 존재하지 않거나 만료된 경우 empty 반환
     */
    public Optional<OidcStateEntry> consumeAndValidate(String state) {
        String key = KEY_PREFIX + state;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null) {
            log.warn("[OidcStateStore] state 없음 또는 만료: state={}", state);
            return Optional.empty();
        }
        // 1회 소비 — replay attack 방지
        stringRedisTemplate.delete(key);
        OidcStateEntry entry = OidcStateEntry.fromJson(json);
        log.debug("[OidcStateStore] state 소비: correlationId={}", entry.getCorrelationId());
        return Optional.of(entry);
    }
}

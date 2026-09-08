package io.github.hipstermin.idem.hub.broker.state;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * IdO OIDC Authorization Code Flow — state/nonce Redis 저장소 (문서 §5-2)
 *
 * <p>q-sign의 {@code OidcStateStore}와 동일한 책임을 IdO가 담당한다.
 * Keycloak 도입 후 redirect_uri가 ido(/api/v1/broker/callback)이므로
 * IdO가 state를 직접 생성·검증해야 한다.
 *
 * <p>Redis 키 구조:
 * <pre>
 *   oidc:state:{state}  → JSON { state, nonce, correlationId, returnUrl, requestedLevel, provider }
 * </pre>
 *
 * <p>보안 특성:
 * <ul>
 *   <li>state: CSRF 방어 — UUID 32자리 opaque 값</li>
 *   <li>nonce: Replay attack 방어 — Keycloak이 idToken에 nonce를 포함해야 함 (§10-5 설정 필요)</li>
 *   <li>1회 소비(consume) — 콜백 수신 즉시 삭제</li>
 *   <li>TTL: {@code ido.keycloak.state-ttl-seconds} (기본 300초)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdoOidcStateStore {

    private static final String KEY_PREFIX = "oidc:state:";

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * state + nonce 생성 → Redis 저장
     *
     * @param correlationId  흐름 추적 ID
     * @param returnUrl      인증 완료 후 이동할 기관 URL
     * @param requestedLevel 요청 인증 수준
     * @param provider       인증 수단 (kakao / naver 등)
     * @param ttlSeconds     state TTL (초)
     */
    public IdoOidcStateEntry create(String correlationId, String returnUrl,
                                    String requestedLevel, String provider,
                                    long ttlSeconds) {
        String state = UUID.randomUUID().toString().replace("-", "");
        String nonce = UUID.randomUUID().toString().replace("-", "");

        IdoOidcStateEntry entry = IdoOidcStateEntry.builder()
                .state(state)
                .nonce(nonce)
                .correlationId(correlationId)
                .returnUrl(returnUrl)
                .requestedLevel(requestedLevel)
                .provider(provider)
                .build();

        stringRedisTemplate.opsForValue().set(
                KEY_PREFIX + state,
                entry.toJson(),
                Duration.ofSeconds(ttlSeconds)
        );

        log.debug("[IdoOidcStateStore] state 저장: correlationId={} provider={} ttl={}s",
                correlationId, provider, ttlSeconds);
        return entry;
    }

    /**
     * callback 수신 시 state 검증 후 1회 소비(삭제)
     *
     * <p>존재하지 않거나 만료된 경우 {@link Optional#empty()} 반환.
     * CSRF 공격 또는 state 만료 시 인증 거부.
     *
     * @param state Keycloak callback의 state 파라미터
     * @return state 엔트리 (존재하면) 또는 empty (만료/없음)
     */
    public Optional<IdoOidcStateEntry> consumeAndValidate(String state) {
        if (state == null || state.isBlank()) {
            log.warn("[IdoOidcStateStore] state 파라미터 null/blank");
            return Optional.empty();
        }

        String key  = KEY_PREFIX + state;
        String json = stringRedisTemplate.opsForValue().get(key);

        if (json == null) {
            log.warn("[IdoOidcStateStore] state 없음 또는 만료: state(prefix)={}...",
                    state.length() >= 8 ? state.substring(0, 8) : state);
            return Optional.empty();
        }

        // 1회 소비 — replay attack 방지
        stringRedisTemplate.delete(key);

        IdoOidcStateEntry entry = IdoOidcStateEntry.fromJson(json);
        log.debug("[IdoOidcStateStore] state 소비 완료: correlationId={} provider={}",
                entry.getCorrelationId(), entry.getProvider());
        return Optional.of(entry);
    }
}

package io.github.hipstermin.idem.gate.keycloak;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Keycloak OIDC Authorization Code Flow — state / nonce Redis 저장소
 *
 * <p>CSRF 방어 및 replay attack 방지를 위해 state, nonce, provider 등을 Redis 에
 * 단기 TTL 로 저장한다. Keycloak callback 수신 시 검증 후 즉시 삭제(1회 소비).
 *
 * <p>Redis 키 구조:
 * <pre>
 *   qsign:oidc:state:{state}  → JSON (KeycloakStateEntry)
 * </pre>
 *
 * <p>TTL: {@code qsign.keycloak.state-ttl-seconds} (기본 300초)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeycloakStateStore {

    private static final String KEY_PREFIX = "qsign:oidc:state:";

    private final StringRedisTemplate  stringRedisTemplate;
    private final KeycloakProperties   keycloakProperties;

    /**
     * state + nonce 를 생성하고 Redis 에 저장한 뒤 KeycloakStateEntry 를 반환한다.
     *
     * @param correlationId  흐름 추적 ID
     * @param returnUrl      인증 완료 후 이동할 기관 URL
     * @param requestedLevel 요청 인증 수준 (L1 / L2 / L3)
     * @param provider       ido 가 전달한 provider 식별자 (예: "kakao")
     * @return 저장된 KeycloakStateEntry
     */
    public KeycloakStateEntry create(String correlationId, String returnUrl,
                                     String requestedLevel, String provider) {
        String state = CryptoProviders.current().randomHex(16);   // D2-b: CSPRNG 32 hex
        String nonce = CryptoProviders.current().randomHex(16);
        String codeVerifier = CryptoProviders.current().randomToken(64);   // D3: PKCE (43~128 unreserved chars)

        KeycloakStateEntry entry = KeycloakStateEntry.builder()
                .state(state)
                .nonce(nonce)
                .correlationId(correlationId)
                .returnUrl(returnUrl)
                .requestedLevel(requestedLevel != null ? requestedLevel : "L1")
                .provider(provider != null ? provider : "")
                .codeVerifier(codeVerifier)
                .build();

        long ttl = keycloakProperties.getStateTtlSeconds();
        stringRedisTemplate.opsForValue().set(
                KEY_PREFIX + state,
                entry.toJson(),
                Duration.ofSeconds(ttl)
        );

        log.debug("[KeycloakStateStore] state 저장: correlationId={} provider={} ttl={}s",
                correlationId, provider, ttl);
        return entry;
    }

    /**
     * Keycloak callback 수신 시 state 를 검증하고 1회 소비(삭제)한다.
     *
     * <p>존재하지 않거나 이미 소비된(만료된) state 이면 {@link Optional#empty()} 를 반환한다.
     * 반환 즉시 Redis 에서 해당 키를 삭제하여 replay attack 을 방지한다.
     *
     * @param state Keycloak callback 파라미터로 전달된 state 값
     * @return 검증된 KeycloakStateEntry, 없거나 만료 시 empty
     */
    public Optional<KeycloakStateEntry> consumeAndValidate(String state) {
        if (state == null || state.isBlank()) {
            log.warn("[KeycloakStateStore] state 파라미터가 null 또는 빈 값");
            return Optional.empty();
        }

        String key  = KEY_PREFIX + state;
        String json = stringRedisTemplate.opsForValue().get(key);

        if (json == null) {
            log.warn("[KeycloakStateStore] state 없음 또는 만료: state={}", state);
            return Optional.empty();
        }

        // 1회 소비 — replay attack 방지 (GET 후 즉시 DELETE)
        stringRedisTemplate.delete(key);

        KeycloakStateEntry entry = KeycloakStateEntry.fromJson(json);
        log.debug("[KeycloakStateStore] state 소비: correlationId={} provider={}",
                entry.getCorrelationId(), entry.getProvider());
        return Optional.of(entry);
    }
}

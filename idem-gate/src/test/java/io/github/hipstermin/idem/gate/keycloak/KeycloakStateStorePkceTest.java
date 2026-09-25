package io.github.hipstermin.idem.gate.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** D3: gate 자체 Keycloak 로그인도 PKCE(S256) 를 쓴다 — verifier 는 state 엔트리에 묶여 Redis 에만 있다. */
@ExtendWith(MockitoExtension.class)
@DisplayName("KeycloakStateStore — PKCE code_verifier 생성·저장·복원")
class KeycloakStateStorePkceTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;

    @Test
    void createStoresVerifierAndRoundTrips() {
        given(redis.opsForValue()).willReturn(values);
        KeycloakStateStore store = new KeycloakStateStore(redis, new KeycloakProperties());

        KeycloakStateEntry entry = store.create("cid-1", "https://rp/return", "L2", "kakao");

        assertThat(entry.getCodeVerifier()).matches("[A-Za-z0-9\\-._~]{43,128}");   // RFC 7636 §4.1
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("qsign:oidc:state:" + entry.getState()), json.capture(), any(Duration.class));
        assertThat(json.getValue()).contains("\"codeVerifier\":\"" + entry.getCodeVerifier() + "\"");

        KeycloakStateEntry restored = KeycloakStateEntry.fromJson(json.getValue());
        assertThat(restored.getCodeVerifier()).isEqualTo(entry.getCodeVerifier());
        assertThat(restored.getNonce()).isEqualTo(entry.getNonce());
        assertThat(restored.getProvider()).isEqualTo("kakao");
    }

    @Test
    @DisplayName("업그레이드 전에 만든 엔트리(codeVerifier 없음)도 읽힌다 — verifier 는 null")
    void legacyEntryWithoutVerifier() {
        KeycloakStateEntry legacy = KeycloakStateEntry.fromJson(
                "{\"state\":\"s\",\"nonce\":\"n\",\"correlationId\":\"c\",\"returnUrl\":\"\",\"requestedLevel\":\"L1\",\"provider\":\"\"}");
        assertThat(legacy.getCodeVerifier()).isNull();
        assertThat(legacy.getState()).isEqualTo("s");
    }

    @Test
    void consumeReturnsVerifierOnce() {
        given(redis.opsForValue()).willReturn(values);
        KeycloakStateStore store = new KeycloakStateStore(redis, new KeycloakProperties());
        given(values.get("qsign:oidc:state:st")).willReturn(
                KeycloakStateEntry.builder().state("st").nonce("n").correlationId("c").codeVerifier("v".repeat(50)).build().toJson());
        assertThat(store.consumeAndValidate("st")).get().extracting(KeycloakStateEntry::getCodeVerifier).isEqualTo("v".repeat(50));
        verify(redis).delete("qsign:oidc:state:st");
        given(values.get(anyString())).willReturn(null);
        assertThat(store.consumeAndValidate("st")).isEmpty();
    }
}

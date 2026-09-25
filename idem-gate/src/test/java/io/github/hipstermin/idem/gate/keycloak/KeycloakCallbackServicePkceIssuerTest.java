package io.github.hipstermin.idem.gate.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.gate.infrastructure.AuthResultRepository;
import io.github.hipstermin.idem.gate.infrastructure.LockRepository;
import io.github.hipstermin.idem.gate.keycloak.dto.KeycloakIdTokenClaims;
import io.github.hipstermin.idem.gate.keycloak.dto.KeycloakTokenResponse;
import io.github.hipstermin.idem.gate.metrics.AuthMetrics;
import io.github.hipstermin.idem.gate.outbox.QSignOutboxRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * D3: 콜백의 token 교환이 state 에 묶인 code_verifier 를 내고, id_token 의 iss 가 우리 realm(내부 주소 또는 공개 issuer)일 때만
 * 통과한다. 성공하면 잠금 카운터를 초기화한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("KeycloakCallbackService — PKCE code_verifier·issuer 검증")
class KeycloakCallbackServicePkceIssuerTest {

    @Mock KeycloakStateStore stateStore;
    @Mock KeycloakJwksVerifier jwks;
    @Mock AuthResultRepository authResults;
    @Mock LockRepository locks;
    @Mock QSignOutboxRepository outbox;
    @Mock RestTemplate rest;
    @Mock AuthMetrics metrics;

    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    KeycloakProperties props = new KeycloakProperties();
    KeycloakCallbackService sut;
    static final String VERIFIER = "x".repeat(64);

    @BeforeEach
    void setUp() throws Exception {
        props.setBaseUrl("http://keycloak:8080");
        props.setRealm("idem");
        props.setClientId("idem-gate");
        props.setClientSecret("unit-test-secret-0123");
        sut = new KeycloakCallbackService(stateStore, jwks, props, authResults, locks, outbox, rest, mapper, metrics);
        ReflectionTestUtils.setField(sut, "idoBaseUrl", "http://hub");
        ReflectionTestUtils.setField(sut, "internalSigSecret", "sig-secret");
        ReflectionTestUtils.setField(sut, "publicIssuer", "https://sso.example.org/realms/idem");

        given(stateStore.consumeAndValidate("st")).willReturn(Optional.of(KeycloakStateEntry.builder()
                .state("st").nonce("nc").correlationId("cid-1").returnUrl("https://rp/done").requestedLevel("L1")
                .provider("kakao").codeVerifier(VERIFIER).build()));
        given(rest.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(KeycloakTokenResponse.class)))
                .willReturn(ResponseEntity.ok(mapper.readValue("{\"id_token\":\"h.p.s\",\"access_token\":\"at\"}", KeycloakTokenResponse.class)));
        given(rest.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .willReturn(ResponseEntity.ok(Map.of("redirectUrl", "https://rp/done?ok")));
        given(locks.isLocked(anyString(), anyString())).willReturn(false);
    }

    private KeycloakIdTokenClaims claims(String iss) throws Exception {
        long exp = System.currentTimeMillis() / 1000L + 300;
        return mapper.readValue("{\"iss\":\"" + iss + "\",\"sub\":\"kc-sub\",\"aud\":\"idem-gate\",\"nonce\":\"nc\",\"exp\":" + exp
                + ",\"identity_provider\":\"social-kakao\",\"sid\":\"sid-1\"}", KeycloakIdTokenClaims.class);
    }

    @Test
    @DisplayName("token 교환 폼에 code_verifier 가 실리고, 내부 issuer 면 통과·성공 시 unlock")
    void verifierSentAndInternalIssuerAccepted() throws Exception {
        given(jwks.verify("h.p.s", "cid-1")).willReturn(claims("http://keycloak:8080/realms/idem"));

        String redirect = sut.handleCallback("code-1", "st");

        assertThat(redirect).isEqualTo("https://rp/done?ok");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<MultiValueMap<String, String>>> form = ArgumentCaptor.forClass(HttpEntity.class);
        verify(rest).exchange(eq("http://keycloak:8080/realms/idem/protocol/openid-connect/token"), eq(HttpMethod.POST),
                form.capture(), eq(KeycloakTokenResponse.class));
        assertThat(form.getValue().getBody().getFirst("code_verifier")).isEqualTo(VERIFIER);
        assertThat(form.getValue().getBody().getFirst("code")).isEqualTo("code-1");
        verify(locks).unlock(anyString(), eq("KAKAO_OIDC"));
        verify(authResults).save(any());
    }

    @Test
    @DisplayName("공개 issuer(gate 가 내보내는 KC_HOSTNAME_URL 기준)도 받는다")
    void publicIssuerAccepted() throws Exception {
        given(jwks.verify("h.p.s", "cid-1")).willReturn(claims("https://sso.example.org/realms/idem"));
        assertThat(sut.handleCallback("code-1", "st")).isEqualTo("https://rp/done?ok");
    }

    @Test
    @DisplayName("같은 Keycloak 의 다른 realm 토큰은 iss 불일치로 거부 — AuthResult 가 만들어지지 않는다")
    void otherRealmRejected() throws Exception {
        given(jwks.verify("h.p.s", "cid-1")).willReturn(claims("http://keycloak:8080/realms/master"));
        assertThatThrownBy(() -> sut.handleCallback("code-1", "st"))
                .isInstanceOf(PlatformException.class)
                .extracting("errorCode").isEqualTo(PlatformErrorCode.IDP_SIGNATURE_MISMATCH);
        verify(authResults, never()).save(any());
        verify(locks, never()).unlock(anyString(), anyString());
    }

    @Test
    @DisplayName("iss 가 없으면 거부 (fail-closed)")
    void missingIssuerRejected() throws Exception {
        given(jwks.verify("h.p.s", "cid-1")).willReturn(claims(""));
        assertThatThrownBy(() -> sut.handleCallback("code-1", "st")).isInstanceOf(PlatformException.class);
        assertThat(sut.acceptedIssuers()).containsExactly("http://keycloak:8080/realms/idem", "https://sso.example.org/realms/idem");
    }

    @Test
    @DisplayName("옛 state(verifier 없음)면 code_verifier 를 보내지 않는다")
    void legacyStateSendsNoVerifier() throws Exception {
        given(stateStore.consumeAndValidate("st")).willReturn(Optional.of(KeycloakStateEntry.builder()
                .state("st").nonce("nc").correlationId("cid-1").returnUrl("").requestedLevel("L1").provider("kakao").build()));
        given(jwks.verify("h.p.s", "cid-1")).willReturn(claims("http://keycloak:8080/realms/idem"));
        sut.handleCallback("code-1", "st");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<MultiValueMap<String, String>>> form = ArgumentCaptor.forClass(HttpEntity.class);
        verify(rest).exchange(anyString(), eq(HttpMethod.POST), form.capture(), eq(KeycloakTokenResponse.class));
        assertThat(form.getValue().getBody().containsKey("code_verifier")).isFalse();
    }
}

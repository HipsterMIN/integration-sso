package io.github.hipstermin.idem.gate.oidcfront;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.gate.keycloak.KeycloakJwksVerifier;
import io.github.hipstermin.idem.gate.keycloak.KeycloakProperties;
import io.github.hipstermin.idem.gate.keycloak.dto.KeycloakIdTokenClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BackchannelLogoutController — OIDC Back-Channel Logout 수신 (S6 PR-2)")
class BackchannelLogoutControllerTest {

    @Mock KeycloakJwksVerifier jwksVerifier;
    @Mock HubSessionClient hubSessionClient;
    @Mock AccessDecisionCache cache;
    @Mock org.springframework.data.redis.core.StringRedisTemplate redis;
    @Mock org.springframework.data.redis.core.ValueOperations<String, String> values;
    final java.util.Set<String> seenJti = new java.util.HashSet<>();
    MockMvc mvc;
    static final String EVENTS = "\"events\":{\"http://schemas.openid.net/event/backchannel-logout\":{}}";
    /** 1.0.1 필수 클레임: iat(지금)·jti — 테스트마다 다른 jti */
    static String fresh() {
        return "\"iat\":" + (System.currentTimeMillis() / 1000L) + ",\"jti\":\"" + java.util.UUID.randomUUID() + "\",";
    }

    @BeforeEach
    void setUp() {
        KeycloakProperties kc = new KeycloakProperties();
        kc.setBaseUrl("http://keycloak:8080");
        kc.setRealm("idem");
        kc.setClientId("idem-gate");
        OidcFrontProperties props = new OidcFrontProperties();
        props.setIssuer("https://sso.example.org/realms/idem");
        given(redis.opsForValue()).willReturn(values);
        given(values.setIfAbsent(anyString(), anyString(), org.mockito.ArgumentMatchers.any(java.time.Duration.class)))
                .willAnswer(inv -> seenJti.add(inv.getArgument(0, String.class)));
        mvc = MockMvcBuilders.standaloneSetup(new BackchannelLogoutController(jwksVerifier, kc, props, hubSessionClient, cache,
                new LogoutTokenReplayGuard(redis))).build();
        given(hubSessionClient.notifyIdpLogout(anyString(), anyString(), anyString(), anyString())).willReturn(1);
    }

    private static KeycloakIdTokenClaims claims(String json) throws Exception {
        return new ObjectMapper().readValue(json, KeycloakIdTokenClaims.class);
    }

    @Test
    @DisplayName("유효한 logout_token: hub 에 sub·sid 통지, 판정 캐시 삭제, 200")
    void valid() throws Exception {
        given(jwksVerifier.verify(eq("lt"), anyString())).willReturn(claims(
                "{\"iss\":\"https://sso.example.org/realms/idem\",\"aud\":\"idem-gate\",\"sub\":\"kc-sub\",\"sid\":\"sid-9\"," + fresh() + EVENTS + "}"));
        mvc.perform(post("/api/v1/oidc/backchannel-logout").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("logout_token", "lt"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        verify(hubSessionClient).notifyIdpLogout(eq("kc-sub"), eq("sid-9"), eq("BACKCHANNEL_LOGOUT"), anyString());
        verify(cache).evictBySub("kc-sub");
    }

    @Test
    @DisplayName("내부 issuer(Keycloak 내부 주소) 도 허용하고, aud 가 idem-hub 여도 받는다")
    void internalIssuerAndIdoClient() throws Exception {
        given(jwksVerifier.verify(eq("lt"), anyString())).willReturn(claims(
                "{\"iss\":\"http://keycloak:8080/realms/idem\",\"aud\":[\"idem-svc-AG1\",\"idem-hub\"],\"sub\":\"kc-sub\"," + fresh() + EVENTS + "}"));
        mvc.perform(post("/api/v1/oidc/backchannel-logout").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("logout_token", "lt"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("거부: events 없음 · nonce 있음 · 다른 aud · 다른 iss · sub·sid 없음 · 서명 실패 — hub 를 부르지 않는다")
    void rejected() throws Exception {
        String base = "\"iss\":\"https://sso.example.org/realms/idem\",\"aud\":\"idem-gate\",\"sub\":\"kc-sub\",\"sid\":\"s\"," + fresh();
        long now = System.currentTimeMillis() / 1000L;
        String[] bad = {
                "{" + base + "\"x\":1}",                                   // events 없음
                "{" + base + "\"nonce\":\"n\"," + EVENTS + "}",    // nonce
                "{\"iss\":\"https://sso.example.org/realms/idem\",\"aud\":\"idem-svc-AG1\",\"sub\":\"x\"," + fresh() + EVENTS + "}", // aud
                "{\"iss\":\"https://sso.example.org/realms/idem\",\"aud\":\"idem-gate-foo\",\"sub\":\"x\"," + fresh() + EVENTS + "}", // aud 부분 일치(1.0.1)
                "{\"iss\":\"https://sso.example.org/realms/idem\",\"aud\":[\"idem-gate-foo\",\"xidem-hub\"],\"sub\":\"x\"," + fresh() + EVENTS + "}",
                "{\"iss\":\"https://evil/realms/idem\",\"aud\":\"idem-gate\",\"sub\":\"x\"," + fresh() + EVENTS + "}",     // iss
                "{\"iss\":\"https://sso.example.org/realms/idem\",\"aud\":\"idem-gate\"," + fresh() + EVENTS + "}",          // sub·sid 없음
                "{\"iss\":\"https://sso.example.org/realms/idem\",\"aud\":\"idem-gate\",\"sub\":\"x\",\"jti\":\"j1\"," + EVENTS + "}", // iat 없음(1.0.1)
                "{\"iss\":\"https://sso.example.org/realms/idem\",\"aud\":\"idem-gate\",\"sub\":\"x\",\"iat\":" + now + "," + EVENTS + "}", // jti 없음
                "{\"iss\":\"https://sso.example.org/realms/idem\",\"aud\":\"idem-gate\",\"sub\":\"x\",\"iat\":" + (now - 1000) + ",\"exp\":" + (now - 500) + ",\"jti\":\"j2\"," + EVENTS + "}", // exp 지남
                "{\"iss\":\"https://sso.example.org/realms/idem\",\"aud\":\"idem-gate\",\"sub\":\"x\",\"iat\":" + (now - 1000) + ",\"jti\":\"j3\"," + EVENTS + "}", // exp 없고 iat 오래됨
                "{\"iss\":\"https://sso.example.org/realms/idem\",\"aud\":\"idem-gate\",\"sub\":\"x\",\"iat\":" + (now + 1000) + ",\"jti\":\"j4\"," + EVENTS + "}", // iat 미래
        };
        for (String json : bad) {
            given(jwksVerifier.verify(eq("lt"), anyString())).willReturn(claims(json));
            mvc.perform(post("/api/v1/oidc/backchannel-logout").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("logout_token", "lt"))
                    .andExpect(status().isBadRequest());
        }
        given(jwksVerifier.verify(eq("lt"), anyString())).willThrow(new RuntimeException("bad sig"));
        mvc.perform(post("/api/v1/oidc/backchannel-logout").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("logout_token", "lt"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_signature"));
        verify(hubSessionClient, never()).notifyIdpLogout(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("1.0.1: 같은 jti 의 logout_token 은 두 번째부터 400 replayed — hub 는 한 번만 부른다")
    void replayRejected() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        given(jwksVerifier.verify(eq("lt"), anyString())).willReturn(claims(
                "{\"iss\":\"https://sso.example.org/realms/idem\",\"aud\":\"idem-gate\",\"sub\":\"kc-sub\",\"sid\":\"s1\",\"iat\":" + now + ",\"exp\":" + (now + 120)
                        + ",\"jti\":\"same-jti\"," + EVENTS + "}"));
        mvc.perform(post("/api/v1/oidc/backchannel-logout").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("logout_token", "lt"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/oidc/backchannel-logout").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("logout_token", "lt"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("replayed"));
        verify(hubSessionClient, org.mockito.Mockito.times(1)).notifyIdpLogout(anyString(), anyString(), anyString(), anyString());
        verify(values, org.mockito.Mockito.times(2)).setIfAbsent(eq(LogoutTokenReplayGuard.PREFIX + "same-jti"), eq("1"), org.mockito.ArgumentMatchers.any(java.time.Duration.class));
    }
}

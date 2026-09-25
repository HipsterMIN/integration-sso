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
    MockMvc mvc;
    static final String EVENTS = "\"events\":{\"http://schemas.openid.net/event/backchannel-logout\":{}}";

    @BeforeEach
    void setUp() {
        KeycloakProperties kc = new KeycloakProperties();
        kc.setBaseUrl("http://keycloak:8080");
        kc.setRealm("onepass");
        kc.setClientId("q-sign-client");
        OidcFrontProperties props = new OidcFrontProperties();
        props.setIssuer("https://sso.example.org/realms/onepass");
        mvc = MockMvcBuilders.standaloneSetup(new BackchannelLogoutController(jwksVerifier, kc, props, hubSessionClient, cache)).build();
        given(hubSessionClient.notifyIdpLogout(anyString(), anyString(), anyString(), anyString())).willReturn(1);
    }

    private static KeycloakIdTokenClaims claims(String json) throws Exception {
        return new ObjectMapper().readValue(json, KeycloakIdTokenClaims.class);
    }

    @Test
    @DisplayName("유효한 logout_token: hub 에 sub·sid 통지, 판정 캐시 삭제, 200")
    void valid() throws Exception {
        given(jwksVerifier.verify(eq("lt"), anyString())).willReturn(claims(
                "{\"iss\":\"https://sso.example.org/realms/onepass\",\"aud\":\"q-sign-client\",\"sub\":\"kc-sub\",\"sid\":\"sid-9\",\"iat\":1," + EVENTS + "}"));
        mvc.perform(post("/api/v1/oidc/backchannel-logout").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("logout_token", "lt"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        verify(hubSessionClient).notifyIdpLogout(eq("kc-sub"), eq("sid-9"), eq("BACKCHANNEL_LOGOUT"), anyString());
        verify(cache).evictBySub("kc-sub");
    }

    @Test
    @DisplayName("내부 issuer(Keycloak 내부 주소) 도 허용하고, aud 가 ido-client 여도 받는다")
    void internalIssuerAndIdoClient() throws Exception {
        given(jwksVerifier.verify(eq("lt"), anyString())).willReturn(claims(
                "{\"iss\":\"http://keycloak:8080/realms/onepass\",\"aud\":[\"ido-client\"],\"sub\":\"kc-sub\"," + EVENTS + "}"));
        mvc.perform(post("/api/v1/oidc/backchannel-logout").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("logout_token", "lt"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("거부: events 없음 · nonce 있음 · 다른 aud · 다른 iss · sub·sid 없음 · 서명 실패 — hub 를 부르지 않는다")
    void rejected() throws Exception {
        String base = "\"iss\":\"https://sso.example.org/realms/onepass\",\"aud\":\"q-sign-client\",\"sub\":\"kc-sub\",\"sid\":\"s\"";
        String[] bad = {
                "{" + base + "}",                                   // events 없음
                "{" + base + ",\"nonce\":\"n\"," + EVENTS + "}",    // nonce
                "{\"iss\":\"https://sso.example.org/realms/onepass\",\"aud\":\"idem-svc-AG1\",\"sub\":\"x\"," + EVENTS + "}", // aud
                "{\"iss\":\"https://evil/realms/onepass\",\"aud\":\"q-sign-client\",\"sub\":\"x\"," + EVENTS + "}",     // iss
                "{\"iss\":\"https://sso.example.org/realms/onepass\",\"aud\":\"q-sign-client\"," + EVENTS + "}"          // sub·sid 없음
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
}

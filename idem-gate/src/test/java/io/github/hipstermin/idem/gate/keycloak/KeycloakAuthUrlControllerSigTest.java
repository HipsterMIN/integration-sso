package io.github.hipstermin.idem.gate.keycloak;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.hipstermin.idem.gate.api.InternalSigVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** S6 점검에서 잡은 결함: 내부 API 가 X-Internal-Sig 를 받기만 하고 검증하지 않았다. */
@ExtendWith(MockitoExtension.class)
@DisplayName("KeycloakAuthUrlController — X-Internal-Sig 검증 (S6 수정)")
class KeycloakAuthUrlControllerSigTest {

    @Mock KeycloakStateStore stateStore;
    @Mock InternalSigVerifier verifier;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        KeycloakProperties props = new KeycloakProperties();
        props.setBaseUrl("http://kc");
        props.setRealm("onepass");
        mvc = MockMvcBuilders.standaloneSetup(new KeycloakAuthUrlController(stateStore, props, verifier)).build();
    }

    @Test
    @DisplayName("서명이 틀리면 403 이고 state 를 만들지 않는다")
    void badSig_forbidden() throws Exception {
        given(verifier.verify(any(), anyString())).willReturn(false);
        mvc.perform(post("/api/v1/oidc/kakao/auth-url").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Sig", "bad").content("{\"correlationId\":\"cid-1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INTERNAL_SIG_INVALID"));
        verify(stateStore, never()).create(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("서명이 맞으면 kc_idp_hint 가 붙은 Keycloak URL 을 돌려준다")
    void goodSig_ok() throws Exception {
        given(verifier.verify("good", "cid-1")).willReturn(true);
        KeycloakStateEntry entry = KeycloakStateEntry.builder().state("st").nonce("nc").build();
        given(stateStore.create(anyString(), anyString(), anyString(), anyString())).willReturn(entry);
        mvc.perform(post("/api/v1/oidc/kakao/auth-url").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Sig", "good").content("{\"correlationId\":\"cid-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl").value(org.hamcrest.Matchers.containsString("kc_idp_hint=social-kakao")));
    }

    @Test
    @DisplayName("[D3] state 에 code_verifier 가 있으면 code_challenge(S256) 가 URL 에 붙는다")
    void pkceChallengeInUrl() throws Exception {
        given(verifier.verify("good", "cid-1")).willReturn(true);
        String verifierValue = "v".repeat(64);
        KeycloakStateEntry entry = KeycloakStateEntry.builder().state("st").nonce("nc").codeVerifier(verifierValue).build();
        given(stateStore.create(anyString(), anyString(), anyString(), anyString())).willReturn(entry);
        String expected = KeycloakAuthUrlController.codeChallengeOf(verifierValue);
        mvc.perform(post("/api/v1/oidc/kakao/auth-url").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Sig", "good").content("{\"correlationId\":\"cid-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl").value(org.hamcrest.Matchers.containsString("&code_challenge=" + expected + "&code_challenge_method=S256")));
        // RFC 7636 §4.2: BASE64URL(SHA-256(verifier)) — 패딩 없음, 43자
        org.assertj.core.api.Assertions.assertThat(expected).hasSize(43).doesNotContain("=", "+", "/");
    }

    @Test
    @DisplayName("옛 state(verifier 없음)면 code_challenge 를 붙이지 않는다")
    void legacyStateWithoutPkce() throws Exception {
        given(verifier.verify("good", "cid-1")).willReturn(true);
        given(stateStore.create(anyString(), anyString(), anyString(), anyString()))
                .willReturn(KeycloakStateEntry.builder().state("st").nonce("nc").build());
        mvc.perform(post("/api/v1/oidc/kakao/auth-url").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Sig", "good").content("{\"correlationId\":\"cid-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("code_challenge"))));
    }
}

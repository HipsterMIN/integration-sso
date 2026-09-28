package io.github.hipstermin.idem.gate.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.hipstermin.idem.gate.keycloak.KeycloakLogoutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 1.1: Keycloak 세션 종료 실패를 204 로 감추지 않는다 — hub 가 재시도 큐에 넣을 수 있게 502. */
class InternalSessionControllerTest {

    private MockMvc mvc;
    private KeycloakLogoutService logout;

    @BeforeEach
    void setUp() {
        InternalSigVerifier verifier = Mockito.mock(InternalSigVerifier.class);
        when(verifier.verify(any(), any())).thenReturn(true);
        logout = Mockito.mock(KeycloakLogoutService.class);
        mvc = MockMvcBuilders.standaloneSetup(new InternalSessionController(verifier, logout)).build();
    }

    @Test
    @DisplayName("성공·이미 없음은 204 + outcome 헤더")
    void success204() throws Exception {
        when(logout.revoke(any(), any(), any())).thenReturn(KeycloakLogoutService.Outcome.REVOKED_SESSION);
        mvc.perform(post("/api/v1/internal/session/logout").contentType(MediaType.APPLICATION_JSON).header("X-Internal-Sig", "x")
                        .content("{\"sub\":\"s\",\"sid\":\"d\"}"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("X-Idp-Logout-Outcome", "REVOKED_SESSION"));
    }

    @Test
    @DisplayName("Keycloak 실패는 502 + outcome=FAILED")
    void failed502() throws Exception {
        when(logout.revoke(any(), any(), any())).thenReturn(KeycloakLogoutService.Outcome.FAILED);
        mvc.perform(post("/api/v1/internal/session/logout").contentType(MediaType.APPLICATION_JSON).header("X-Internal-Sig", "x")
                        .content("{\"sid\":\"d\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(header().string("X-Idp-Logout-Outcome", "FAILED"));
    }
}

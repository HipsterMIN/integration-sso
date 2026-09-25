package io.github.hipstermin.idem.hub.slo;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.hipstermin.idem.hub.api.GlobalExceptionHandler;
import io.github.hipstermin.idem.hub.broker.InternalSigVerifier;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdpSessionLogoutController — gate 의 IdP 로그아웃 통지 (S6 PR-2)")
class IdpSessionLogoutControllerTest {

    @Mock InternalSigVerifier verifier;
    @Mock FeSessionService feSessionService;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new IdpSessionLogoutController(verifier, feSessionService))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void signed_expiresSessions() throws Exception {
        given(verifier.verify("sig", "cid-1")).willReturn(true);
        given(feSessionService.invalidateByIdpSession("kc-sub", "sid-1", "BACKCHANNEL_LOGOUT")).willReturn(2);
        mvc.perform(post("/api/internal/v1/session/idp-logout").contentType(MediaType.APPLICATION_JSON).header("X-Internal-Sig", "sig")
                        .content("{\"sub\":\"kc-sub\",\"sid\":\"sid-1\",\"reason\":\"BACKCHANNEL_LOGOUT\",\"correlationId\":\"cid-1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expired").value(2));
    }

    @Test
    void unsigned_or_empty_rejected() throws Exception {
        given(verifier.verify(any(), anyString())).willReturn(false);
        mvc.perform(post("/api/internal/v1/session/idp-logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sub\":\"kc-sub\",\"correlationId\":\"cid-1\"}"))
                .andExpect(status().is4xxClientError());
        given(verifier.verify(eq("sig"), anyString())).willReturn(true);
        mvc.perform(post("/api/internal/v1/session/idp-logout").contentType(MediaType.APPLICATION_JSON).header("X-Internal-Sig", "sig")
                        .content("{\"correlationId\":\"cid-2\"}"))
                .andExpect(status().isBadRequest());
        verify(feSessionService, never()).invalidateByIdpSession(any(), any(), any());
    }
}

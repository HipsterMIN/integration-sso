package io.github.hipstermin.idem.hub.protocol.oidcrp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.hub.api.GlobalExceptionHandler;
import io.github.hipstermin.idem.hub.broker.InternalSigVerifier;
import java.util.List;
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
@DisplayName("OidcRpAccessController — 내부 서명 필수, 거부도 200 본문으로")
class OidcRpAccessControllerTest {

    @Mock InternalSigVerifier verifier;
    @Mock OidcRpAccessService service;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new OidcRpAccessController(verifier, service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    static final String BODY = "{\"clientId\":\"idem-svc-AG1\",\"sub\":\"kc-sub\",\"identityProvider\":\"social-kakao\",\"acr\":\"1\",\"correlationId\":\"cid-1\"}";

    @Test
    @DisplayName("서명이 맞으면 판정 본문을 돌려준다 (허용·거부 모두 200)")
    void signed_ok() throws Exception {
        given(verifier.verify("sig", "cid-1")).willReturn(true);
        given(service.evaluate(any(), eq("cid-1"))).willReturn(new OidcRpAccessResponse(true, null, null, null, "AG1", "qim-1",
                HandoffPayload.HandoffState.APPROVED, "pw-1", "PAIRWISE_HMAC", List.of("VIEWER"), true, "L1", "KAKAO_OIDC", null));
        mvc.perform(post("/api/internal/v1/oidc-rp/access").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Sig", "sig").content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.state").value("APPROVED"))
                .andExpect(jsonPath("$.roles[0]").value("VIEWER"));

        given(service.evaluate(any(), eq("cid-1"))).willReturn(OidcRpAccessResponse.denied(PlatformErrorCode.IDO_ASSIGNMENT_REQUIRED, "미할당", "ASSIGNMENT", "AG1"));
        mvc.perform(post("/api/internal/v1/oidc-rp/access").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Sig", "sig").content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.denyCode").value("E-IDO-120"));
    }

    @Test
    @DisplayName("서명이 없거나 틀리면 판정하지 않는다")
    void unsigned_rejected() throws Exception {
        given(verifier.verify(any(), anyString())).willReturn(false);
        mvc.perform(post("/api/internal/v1/oidc-rp/access").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is4xxClientError());
    }
}

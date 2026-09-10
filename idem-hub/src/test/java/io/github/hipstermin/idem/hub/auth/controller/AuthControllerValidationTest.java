package io.github.hipstermin.idem.hub.auth.controller;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.hipstermin.idem.hub.api.GlobalExceptionHandler;
import io.github.hipstermin.idem.hub.auth.dto.CiCheckRequest;
import io.github.hipstermin.idem.hub.auth.dto.CiCheckResponse;
import io.github.hipstermin.idem.hub.auth.dto.OacxEasysignRequest;
import io.github.hipstermin.idem.hub.auth.dto.OacxEasysignResponse;
import io.github.hipstermin.idem.hub.auth.service.AuthService;
import io.github.hipstermin.idem.hub.auth.service.NiceAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@link AuthController} 요청 검증 계약 — k6 스모크({@code k6/scenarios/smoke.js})가 기대하는 응답을 고정한다.
 *
 * <p>빈 {@code ci} 는 {@code @Valid} 에서 걸려 {@link GlobalExceptionHandler} 가
 * 400 + {@code E-IDO-400} 을 돌려준다. 서비스의 {@code resultCode=4000} 분기는 HTTP 로는 도달하지 않는다.
 */
class AuthControllerValidationTest {

    private MockMvc mvc;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        NiceAuthService niceAuthService = mock(NiceAuthService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(authService, niceAuthService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /nice/ci-check — 빈 ci 는 Bean Validation 400 E-IDO-400 (서비스 미호출)")
    void ciCheck_blankCi_400() throws Exception {
        mvc.perform(post("/api/v1/auth/nice/ci-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ci\":\"\",\"mbrDvsnCd\":\"A101\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E-IDO-400"))
                .andExpect(jsonPath("$.message").value(startsWith("ci")));
        verify(authService, never()).checkNiceCi(any());
    }

    @Test
    @DisplayName("POST /nice/ci-check — 형식이 맞으면 서비스로 위임")
    void ciCheck_valid_delegates() throws Exception {
        when(authService.checkNiceCi(any(CiCheckRequest.class)))
                .thenReturn(CiCheckResponse.builder().resultCode("2000").resultMsg("기존 회원").result(true).build());
        mvc.perform(post("/api/v1/auth/nice/ci-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ci\":\"abcdefgh12345678\",\"mbrDvsnCd\":\"A101\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("2000"));
    }

    @Test
    @DisplayName("POST /oacx/easysign — fn 이 있으면 검증 통과, 서비스가 4000 을 200 으로 반환")
    void oacxEasysign_invalidFn_200_4000() throws Exception {
        when(authService.handleOacxEasysign(any(OacxEasysignRequest.class)))
                .thenReturn(OacxEasysignResponse.builder().resultCode("4000").resultMsg("유효하지 않은 fn").build());
        mvc.perform(post("/api/v1/auth/oacx/easysign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fn\":\"INVALID\",\"status\":\"success\",\"res\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("4000"));
    }
}

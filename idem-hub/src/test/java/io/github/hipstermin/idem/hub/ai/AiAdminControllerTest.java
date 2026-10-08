package io.github.hipstermin.idem.hub.ai;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.admin.auth.AdminAuthFilter;
import io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal;
import io.github.hipstermin.idem.hub.admin.auth.AdminPrincipalArgumentResolver;
import io.github.hipstermin.idem.hub.admin.auth.AdminRole;
import io.github.hipstermin.idem.hub.api.GlobalExceptionHandler;
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
@DisplayName("AiAdminController — status 는 꺼져도 200, 나머지는 꺼지면 404 E-IDO-140, 초안 응답 모양")
class AiAdminControllerTest {

    @Mock AiAssistantService service;
    MockMvc mvc;
    static final AdminPrincipal ADMIN = new AdminPrincipal("a1", "admin", AdminRole.POLICY_ADMIN, null, "sid", false);

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AiAdminController(service))
                .setCustomArgumentResolvers(new AdminPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void statusAlways200() throws Exception {
        given(service.status()).willReturn(new AiAssistantService.Status(false, null, null, "idem.hub.ai.enabled=false"));
        mvc.perform(get("/api/v1/admin/ai/status").requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.reason").value("idem.hub.ai.enabled=false"));
        mvc.perform(get("/api/v1/admin/ai/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void disabledIs404WithDetail() throws Exception {
        given(service.summarizeIncident(any(), anyString())).willThrow(new PlatformException(PlatformErrorCode.AI_DISABLED, "c", "idem.hub.ai.enabled=false"));
        mvc.perform(get("/api/v1/admin/ai/incident-summary").requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, ADMIN))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("E-IDO-140")).andExpect(jsonPath("$.message").value("idem.hub.ai.enabled=false"));
    }

    @Test
    void draftPassesBodyAndReturnsShape() throws Exception {
        ObjectMapper om = new ObjectMapper();
        given(service.draftProfile(any(), eq(ADMIN), anyString())).willReturn(new AiAssistantService.Draft(
                om.readTree("{\"service\":{\"code\":\"AG1\"}}"), List.of("policy: required"), "m", "note"));
        mvc.perform(post("/api/v1/admin/ai/profile-draft").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"OIDC 기관\",\"serviceCode\":\"AG1\"}").requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.service.code").value("AG1"))
                .andExpect(jsonPath("$.violations[0]").value("policy: required"))
                .andExpect(jsonPath("$.model").value("m"));
    }
}

package io.github.hipstermin.idem.hub.audit.anomaly;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.admin.auth.AdminAuthFilter;
import io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal;
import io.github.hipstermin.idem.hub.admin.auth.AdminPrincipalArgumentResolver;
import io.github.hipstermin.idem.hub.admin.auth.AdminRole;
import io.github.hipstermin.idem.hub.api.GlobalExceptionHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnomalyAdminController — 목록·통계·검토 바인딩, 테넌트 범위 403, 검토 verdict 전달")
class AnomalyAdminControllerTest {

    @Mock AnomalyAdminService service;
    MockMvc mvc;
    static final AdminPrincipal AUDITOR = new AdminPrincipal("a3", "aud", AdminRole.AUDITOR, null, "sid", false);

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AnomalyAdminController(service))
                .setCustomArgumentResolvers(new AdminPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listBindsFiltersAndClampsNothingHere() throws Exception {
        given(service.list(any(), eq(AUDITOR))).willReturn(new AnomalyAdminService.Page(List.of(Map.of("flagId", "f1", "rule", "TICKET_REPLAY")), 0, 50, 1));
        mvc.perform(get("/api/v1/admin/anomalies?rule=TICKET_REPLAY&review=UNREVIEWED&severity=HIGH").requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, AUDITOR))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].flagId").value("f1")).andExpect(jsonPath("$.total").value(1));
        ArgumentCaptor<AnomalyAdminService.Query> q = ArgumentCaptor.forClass(AnomalyAdminService.Query.class);
        org.mockito.Mockito.verify(service).list(q.capture(), eq(AUDITOR));
        org.assertj.core.api.Assertions.assertThat(q.getValue().rule()).isEqualTo("TICKET_REPLAY");
        org.assertj.core.api.Assertions.assertThat(q.getValue().review()).isEqualTo("UNREVIEWED");
        org.assertj.core.api.Assertions.assertThat(q.getValue().size()).isEqualTo(50);
        mvc.perform(get("/api/v1/admin/anomalies")).andExpect(status().isUnauthorized());
    }

    @Test
    void tenantScopeViolationIs403() throws Exception {
        given(service.stats(eq(30), eq(AUDITOR), eq(null))).willThrow(new PlatformException(PlatformErrorCode.ADMIN_FORBIDDEN, null, "agencyCode 필요"));
        mvc.perform(get("/api/v1/admin/anomalies/stats?days=30").requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, AUDITOR))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("E-IDO-131"));
    }

    @Test
    void reviewPassesVerdictAndNote() throws Exception {
        given(service.review(eq("f1"), eq("FALSE_POSITIVE"), eq("점검 작업"), eq(AUDITOR), anyString()))
                .willReturn(Map.of("flagId", "f1", "review", "FALSE_POSITIVE", "reviewedBy", "aud"));
        mvc.perform(post("/api/v1/admin/anomalies/f1/review").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verdict\":\"FALSE_POSITIVE\",\"note\":\"점검 작업\"}").requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, AUDITOR))
                .andExpect(status().isOk()).andExpect(jsonPath("$.review").value("FALSE_POSITIVE")).andExpect(jsonPath("$.reviewedBy").value("aud"));
    }
}

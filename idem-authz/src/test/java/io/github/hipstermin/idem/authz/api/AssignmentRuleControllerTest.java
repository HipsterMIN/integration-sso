package io.github.hipstermin.idem.authz.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.hipstermin.idem.authz.application.AssignmentRuleService;
import io.github.hipstermin.idem.authz.domain.AssignmentRuleType;
import io.github.hipstermin.idem.authz.domain.AuthzAssignmentRuleEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 1.1 규칙 할당 컨트롤러 — standalone 슬라이스. */
class AssignmentRuleControllerTest {

    private MockMvc mvc;
    private AssignmentRuleService service;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(AssignmentRuleService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AssignmentRuleController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private static AuthzAssignmentRuleEntity rule() {
        return AuthzAssignmentRuleEntity.builder().id(UUID.randomUUID()).agencyCode("AG1").ruleType(AssignmentRuleType.ATTRIBUTE)
                .matchKey("authLevel").matchValues("L2,L3").enabled(true).createdBy("ops").createdAt(Instant.now()).build();
    }

    @Test
    void create_returns201() throws Exception {
        when(service.create(any(), eq("ops"), any(), eq("cid"))).thenReturn(rule());
        mvc.perform(post("/api/v1/internal/authz/assignment-rules").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor", "ops").header("X-Correlation-Id", "cid")
                        .content("{\"agencyCode\":\"AG1\",\"ruleType\":\"ATTRIBUTE\",\"matchKey\":\"authLevel\",\"matchValues\":[\"L2\",\"L3\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleType").value("ATTRIBUTE"))
                .andExpect(jsonPath("$.matchValues[1]").value("L3"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void create_missingField_returns400() throws Exception {
        mvc.perform(post("/api/v1/internal/authz/assignment-rules").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agencyCode\":\"AG1\",\"ruleType\":\"ATTRIBUTE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_returnsRules() throws Exception {
        when(service.list("AG1")).thenReturn(List.of(rule()));
        mvc.perform(get("/api/v1/internal/authz/assignment-rules").param("agencyCode", "AG1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].matchKey").value("authLevel"));
    }

    @Test
    void disable_returnsRevokedCount_andBadIdIs400() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.disable(eq(id), eq("SYSTEM"), any(), any())).thenReturn(3);
        mvc.perform(delete("/api/v1/internal/authz/assignment-rules/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(3))
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(delete("/api/v1/internal/authz/assignment-rules/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void evaluate_postAccess_passesAttributesAndReturnsServiceAccessShape() throws Exception {
        when(service.evaluate(eq("u1"), eq("AG1"), any(), any(), eq("cid")))
                .thenReturn(new AssignmentRuleService.Access(true, "RULE", List.of("VIEWER")));
        mvc.perform(post("/api/v1/internal/authz/users/u1/access").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "cid")
                        .content("{\"agencyCode\":\"AG1\",\"attributes\":{\"authLevel\":\"L2\",\"providerCode\":\"X\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qimUserId").value("u1"))
                .andExpect(jsonPath("$.assigned").value(true))
                .andExpect(jsonPath("$.assignmentSource").value("RULE"))
                .andExpect(jsonPath("$.roles[0]").value("VIEWER"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> attrs = ArgumentCaptor.forClass(Map.class);
        verify(service).evaluate(eq("u1"), eq("AG1"), attrs.capture(), any(), eq("cid"));
        org.assertj.core.api.Assertions.assertThat(attrs.getValue()).containsEntry("authLevel", "L2");
    }

    @Test
    void evaluate_missingAgency_returns400() throws Exception {
        mvc.perform(post("/api/v1/internal/authz/users/u1/access").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}

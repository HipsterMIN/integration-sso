package io.github.hipstermin.idem.hub.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import io.github.hipstermin.idem.hub.infrastructure.QAuthzClient;
import java.time.Instant;
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
@DisplayName("AssignmentAdminController — 바인딩·상태 코드·authz 거부 매핑")
class AssignmentAdminControllerTest {

    @Mock AssignmentAdminService service;
    MockMvc mvc;
    static final AdminPrincipal POLICY = new AdminPrincipal("a2", "pol", AdminRole.POLICY_ADMIN, "T1", "sid", false);

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AssignmentAdminController(service))
                .setCustomArgumentResolvers(new AdminPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listAndAssignAndUnassign() throws Exception {
        given(service.list(eq("AG1"), eq(1), eq(20), eq(POLICY), anyString()))
                .willReturn(new AssignmentAdminService.Page(List.of(new QAuthzClient.AssignmentRecord("u1", "AG1", "ACTIVE", "CONSOLE", null, "pol", null)), 1, 20, 21, false));
        mvc.perform(get("/api/v1/admin/services/AG1/assignments?page=1&size=20").requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, POLICY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].qimUserId").value("u1")).andExpect(jsonPath("$.total").value(21)).andExpect(jsonPath("$.hasNext").value(false));

        given(service.assign(eq("AG1"), eq("u1"), eq(Instant.parse("2026-12-31T00:00:00Z")), eq("onboard"), eq(POLICY), anyString()))
                .willReturn(new QAuthzClient.AssignmentRecord("u1", "AG1", "ACTIVE", "CONSOLE", null, "pol", "2026-12-31T00:00:00Z"));
        mvc.perform(post("/api/v1/admin/services/AG1/assignments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qimUserId\":\"u1\",\"expiresAt\":\"2026-12-31T00:00:00Z\",\"reason\":\"onboard\"}").requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, POLICY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"));

        mvc.perform(delete("/api/v1/admin/services/AG1/assignments/u1?reason=bye").requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, POLICY))
                .andExpect(status().isNoContent());
        verify(service).unassign(eq("AG1"), eq("u1"), eq("bye"), eq(POLICY), anyString());

        mvc.perform(get("/api/v1/admin/services/AG1/assignments")).andExpect(status().isUnauthorized());
    }

    @Test
    void rolesAndGrants() throws Exception {
        given(service.roles(eq("AG1"), eq(POLICY), anyString())).willReturn(List.of(new QAuthzClient.RoleRecord("AG1", "MANAGER", "관리자", null, true, null)));
        mvc.perform(get("/api/v1/admin/services/AG1/roles").requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, POLICY))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].roleCode").value("MANAGER"));
        given(service.grantRole(eq("AG1"), eq("u1"), eq("MANAGER"), isNull(), isNull(), eq(POLICY), anyString()))
                .willReturn(new QAuthzClient.UserRoleRecord("r1", "u1", "AG1", "MANAGER", "ACTIVE", null, "pol", null, "CONSOLE"));
        mvc.perform(post("/api/v1/admin/services/AG1/assignments/u1/roles").contentType(MediaType.APPLICATION_JSON).content("{\"roleCode\":\"MANAGER\"}")
                        .requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, POLICY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value("r1"));
        mvc.perform(delete("/api/v1/admin/services/AG1/assignments/u1/roles/MANAGER").requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, POLICY))
                .andExpect(status().isNoContent());
        verify(service).revokeRole(eq("AG1"), eq("u1"), eq("MANAGER"), isNull(), eq(POLICY), anyString());
    }

    @Test
    void authzRejectionsKeepStatus() throws Exception {
        given(service.assign(eq("AG1"), eq("u1"), any(), any(), eq(POLICY), anyString()))
                .willThrow(new PlatformException(PlatformErrorCode.IDO_AUTHZ_CONFLICT, "c", "authz 409 {\"error\":\"E-AUTHZ-409-DUP\"}"));
        mvc.perform(post("/api/v1/admin/services/AG1/assignments").contentType(MediaType.APPLICATION_JSON).content("{\"qimUserId\":\"u1\"}")
                        .requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, POLICY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("E-IDO-128"));
        given(service.roles(eq("NOPE"), eq(POLICY), anyString()))
                .willThrow(new PlatformException(PlatformErrorCode.AGENCY_NOT_FOUND, null, "없음"));
        mvc.perform(get("/api/v1/admin/services/NOPE/roles").requestAttr(AdminAuthFilter.ATTR_PRINCIPAL, POLICY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("E-AGENCY-307"));
    }
}

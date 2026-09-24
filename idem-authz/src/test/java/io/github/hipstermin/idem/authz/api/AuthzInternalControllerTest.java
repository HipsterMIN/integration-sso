package io.github.hipstermin.idem.authz.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.authz.application.AuthzService;
import io.github.hipstermin.idem.authz.domain.AssignmentStatus;
import io.github.hipstermin.idem.authz.domain.AuthzUserRoleEntity;
import io.github.hipstermin.idem.authz.domain.GrantSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 컨트롤러 슬라이스 테스트 — standalone 구성으로 인터셉터/DB 없이 검증.
 */
class AuthzInternalControllerTest {

    private MockMvc mockMvc;
    private AuthzService authzService;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        authzService = Mockito.mock(AuthzService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthzInternalController(authzService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private AuthzUserRoleEntity sampleAssignment() {
        return AuthzUserRoleEntity.builder()
                .id(UUID.randomUUID()).qimUserId("user-1").agencyCode("GOV_SMES").roleCode("MANAGER")
                .status(AssignmentStatus.ACTIVE).grantedAt(Instant.now()).grantedBy("admin@onepass")
                .source(GrantSource.API).build();
    }

    @Test
    void grant_returns201WithAssignment() throws Exception {
        when(authzService.grantRole(any(), any(), any())).thenReturn(sampleAssignment());

        String body = om.writeValueAsString(java.util.Map.of(
                "qimUserId", "user-1", "agencyCode", "GOV_SMES",
                "roleCode", "MANAGER", "grantedBy", "admin@onepass"));

        mockMvc.perform(post("/api/v1/internal/authz/grants")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roleCode").value("MANAGER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void grant_missingRequiredField_returns400() throws Exception {
        String body = om.writeValueAsString(java.util.Map.of(
                "qimUserId", "user-1", "agencyCode", "GOV_SMES")); // roleCode/grantedBy 누락

        mockMvc.perform(post("/api/v1/internal/authz/grants")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("E-AUTHZ-400"));
    }

    @Test
    void effectiveRoles_returnsRoleList() throws Exception {
        when(authzService.effectiveRoleCodes(eq("user-1"), eq("GOV_SMES")))
                .thenReturn(List.of("AUDITOR", "MANAGER"));

        mockMvc.perform(get("/api/v1/internal/authz/users/user-1/effective-roles")
                        .param("agencyCode", "GOV_SMES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("AUDITOR"))
                .andExpect(jsonPath("$.roles[1]").value("MANAGER"));
    }

    @Test
    void revoke_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/internal/authz/grants")
                        .param("qimUserId", "user-1")
                        .param("agencyCode", "GOV_SMES")
                        .param("roleCode", "MANAGER")
                        .param("revokedBy", "admin@onepass"))
                .andExpect(status().isNoContent());
    }

    // ── S8-b 할당 ────────────────────────────────────────────────────────────
    private io.github.hipstermin.idem.authz.domain.AuthzAssignmentEntity sampleServiceAssignment() {
        return io.github.hipstermin.idem.authz.domain.AuthzAssignmentEntity.builder()
                .id(UUID.randomUUID()).qimUserId("user-1").agencyCode("GOV_SMES")
                .status(AssignmentStatus.ACTIVE).source(io.github.hipstermin.idem.authz.domain.AssignmentSource.CONSOLE)
                .grantedAt(Instant.now()).grantedBy("admin@onepass").build();
    }

    @Test
    void assign_returns201() throws Exception {
        when(authzService.assign(any(), any(), any())).thenReturn(sampleServiceAssignment());
        String body = om.writeValueAsString(java.util.Map.of(
                "qimUserId", "user-1", "agencyCode", "GOV_SMES", "grantedBy", "admin@onepass", "source", "CONSOLE"));
        mockMvc.perform(post("/api/v1/internal/authz/assignments")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agencyCode").value("GOV_SMES"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.source").value("CONSOLE"));
    }

    @Test
    void assign_missingGrantedBy_returns400() throws Exception {
        String body = om.writeValueAsString(java.util.Map.of("qimUserId", "user-1", "agencyCode", "GOV_SMES"));
        mockMvc.perform(post("/api/v1/internal/authz/assignments")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unassign_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/internal/authz/assignments")
                        .param("qimUserId", "user-1").param("agencyCode", "GOV_SMES").param("revokedBy", "admin"))
                .andExpect(status().isNoContent());
        verify(authzService).unassign(eq("user-1"), eq("GOV_SMES"), eq("admin"), any(), isNull(), isNull());
    }

    @Test
    void access_returnsAssignedAndRoles() throws Exception {
        when(authzService.effectiveAssignment("user-1", "GOV_SMES")).thenReturn(Optional.of(sampleServiceAssignment()));
        when(authzService.effectiveRoleCodes("user-1", "GOV_SMES")).thenReturn(List.of("MANAGER"));
        mockMvc.perform(get("/api/v1/internal/authz/users/user-1/access").param("agencyCode", "GOV_SMES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigned").value(true))
                .andExpect(jsonPath("$.assignmentSource").value("CONSOLE"))
                .andExpect(jsonPath("$.roles[0]").value("MANAGER"));
    }

    @Test
    void access_unassigned_returnsFalse() throws Exception {
        when(authzService.effectiveAssignment("user-2", "GOV_SMES")).thenReturn(Optional.empty());
        when(authzService.effectiveRoleCodes("user-2", "GOV_SMES")).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/internal/authz/users/user-2/access").param("agencyCode", "GOV_SMES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigned").value(false))
                .andExpect(jsonPath("$.roles").isEmpty());
    }
}

package kr.go.smes.authz.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.authz.application.AuthzService;
import kr.go.smes.authz.domain.AssignmentStatus;
import kr.go.smes.authz.domain.AuthzUserRoleEntity;
import kr.go.smes.authz.domain.GrantSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
}

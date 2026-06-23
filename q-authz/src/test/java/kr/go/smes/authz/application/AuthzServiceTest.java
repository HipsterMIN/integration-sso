package kr.go.smes.authz.application;

import kr.go.smes.authz.api.AuthzErrorCode;
import kr.go.smes.authz.api.AuthzException;
import kr.go.smes.authz.api.dto.GrantRoleRequest;
import kr.go.smes.authz.domain.AssignmentStatus;
import kr.go.smes.authz.domain.AuthzRoleEntity;
import kr.go.smes.authz.domain.AuthzRoleId;
import kr.go.smes.authz.domain.AuthzUserRoleEntity;
import kr.go.smes.authz.infrastructure.AuthzRoleRepository;
import kr.go.smes.authz.infrastructure.AuthzUserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthzServiceTest {

    @Mock AuthzRoleRepository roleRepository;
    @Mock AuthzUserRoleRepository userRoleRepository;
    @Mock AuthzAuditService auditService;

    @InjectMocks AuthzService service;

    private static final String USER = "user-1";
    private static final String AGENCY = "GOV_SMES";
    private static final String ROLE = "MANAGER";

    private AuthzRoleEntity assignableRole;

    @BeforeEach
    void setUp() {
        assignableRole = AuthzRoleEntity.builder()
                .agencyCode(AGENCY).roleCode(ROLE).assignable(true)
                .createdAt(Instant.now()).build();
    }

    private GrantRoleRequest grantReq(Instant expiresAt) {
        return new GrantRoleRequest(USER, AGENCY, ROLE, "admin@onepass", expiresAt, "API", "test");
    }

    @Test
    void grant_newAssignment_persistsActiveAndAudits() {
        when(roleRepository.findById(new AuthzRoleId(AGENCY, ROLE)))
                .thenReturn(Optional.of(assignableRole));
        when(userRoleRepository.findByQimUserIdAndAgencyCodeAndRoleCode(USER, AGENCY, ROLE))
                .thenReturn(Optional.empty());
        when(userRoleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AuthzUserRoleEntity result = service.grantRole(grantReq(null), "1.2.3.4", "cid-1");

        assertThat(result.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(result.getRoleCode()).isEqualTo(ROLE);
        verify(auditService).record(eq(kr.go.smes.authz.domain.AuditEvent.GRANT),
                eq(USER), eq(AGENCY), eq(ROLE), any(), any(), any(), any());
    }

    @Test
    void grant_roleNotInCatalog_throwsRoleNotFound() {
        when(roleRepository.findById(new AuthzRoleId(AGENCY, ROLE))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.grantRole(grantReq(null), "1.2.3.4", "cid"))
                .isInstanceOf(AuthzException.class)
                .extracting(e -> ((AuthzException) e).getErrorCode())
                .isEqualTo(AuthzErrorCode.ROLE_NOT_FOUND);
        verify(userRoleRepository, never()).save(any());
    }

    @Test
    void grant_notAssignableRole_throwsConflict() {
        assignableRole.setAssignable(false);
        when(roleRepository.findById(new AuthzRoleId(AGENCY, ROLE)))
                .thenReturn(Optional.of(assignableRole));

        assertThatThrownBy(() -> service.grantRole(grantReq(null), "1.2.3.4", "cid"))
                .isInstanceOf(AuthzException.class)
                .extracting(e -> ((AuthzException) e).getErrorCode())
                .isEqualTo(AuthzErrorCode.ROLE_NOT_ASSIGNABLE);
    }

    @Test
    void grant_alreadyActive_isIdempotent_noNewAudit() {
        AuthzUserRoleEntity existing = AuthzUserRoleEntity.builder()
                .id(UUID.randomUUID()).qimUserId(USER).agencyCode(AGENCY).roleCode(ROLE)
                .status(AssignmentStatus.ACTIVE).grantedAt(Instant.now()).grantedBy("admin")
                .source(kr.go.smes.authz.domain.GrantSource.API).build();
        when(roleRepository.findById(new AuthzRoleId(AGENCY, ROLE)))
                .thenReturn(Optional.of(assignableRole));
        when(userRoleRepository.findByQimUserIdAndAgencyCodeAndRoleCode(USER, AGENCY, ROLE))
                .thenReturn(Optional.of(existing));
        when(userRoleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.grantRole(grantReq(null), "1.2.3.4", "cid");

        // 멱등: GRANT 감사 미발생
        verify(auditService, never()).record(eq(kr.go.smes.authz.domain.AuditEvent.GRANT),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void revoke_existing_setsRevokedAndAudits() {
        AuthzUserRoleEntity existing = AuthzUserRoleEntity.builder()
                .id(UUID.randomUUID()).qimUserId(USER).agencyCode(AGENCY).roleCode(ROLE)
                .status(AssignmentStatus.ACTIVE).grantedAt(Instant.now()).grantedBy("admin")
                .source(kr.go.smes.authz.domain.GrantSource.API).build();
        when(userRoleRepository.findByQimUserIdAndAgencyCodeAndRoleCode(USER, AGENCY, ROLE))
                .thenReturn(Optional.of(existing));

        service.revokeRole(USER, AGENCY, ROLE, "admin@onepass", "1.2.3.4", "policy", "cid");

        ArgumentCaptor<AuthzUserRoleEntity> cap = ArgumentCaptor.forClass(AuthzUserRoleEntity.class);
        verify(userRoleRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(AssignmentStatus.REVOKED);
        assertThat(cap.getValue().getRevokedAt()).isNotNull();
        verify(auditService).record(eq(kr.go.smes.authz.domain.AuditEvent.REVOKE),
                eq(USER), eq(AGENCY), eq(ROLE), any(), any(), any(), any());
    }

    @Test
    void revoke_missing_throwsAssignmentNotFound() {
        when(userRoleRepository.findByQimUserIdAndAgencyCodeAndRoleCode(USER, AGENCY, ROLE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revokeRole(USER, AGENCY, ROLE, "admin", "ip", "r", "cid"))
                .isInstanceOf(AuthzException.class)
                .extracting(e -> ((AuthzException) e).getErrorCode())
                .isEqualTo(AuthzErrorCode.ASSIGNMENT_NOT_FOUND);
    }

    @Test
    void effectiveRoleCodes_excludesExpired_andSorts() {
        AuthzUserRoleEntity active = AuthzUserRoleEntity.builder()
                .id(UUID.randomUUID()).qimUserId(USER).agencyCode(AGENCY).roleCode("MANAGER")
                .status(AssignmentStatus.ACTIVE).expiresAt(null).build();
        AuthzUserRoleEntity expired = AuthzUserRoleEntity.builder()
                .id(UUID.randomUUID()).qimUserId(USER).agencyCode(AGENCY).roleCode("REVIEWER")
                .status(AssignmentStatus.ACTIVE)
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS)).build();
        AuthzUserRoleEntity future = AuthzUserRoleEntity.builder()
                .id(UUID.randomUUID()).qimUserId(USER).agencyCode(AGENCY).roleCode("AUDITOR")
                .status(AssignmentStatus.ACTIVE)
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();
        when(userRoleRepository.findByQimUserIdAndAgencyCodeAndStatus(USER, AGENCY, AssignmentStatus.ACTIVE))
                .thenReturn(List.of(active, expired, future));

        List<String> roles = service.effectiveRoleCodes(USER, AGENCY);

        assertThat(roles).containsExactly("AUDITOR", "MANAGER"); // 만료 REVIEWER 제외, 정렬
    }
}

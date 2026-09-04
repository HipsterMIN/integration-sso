package kr.go.smes.authz.application;

import kr.go.smes.authz.api.AuthzErrorCode;
import kr.go.smes.authz.api.AuthzException;
import kr.go.smes.authz.api.dto.GrantRoleRequest;
import kr.go.smes.authz.api.scim.ScimGroup;
import kr.go.smes.authz.api.scim.ScimMember;
import kr.go.smes.authz.api.scim.ScimPatchOp;
import kr.go.smes.authz.domain.AssignmentStatus;
import kr.go.smes.authz.domain.AuthzRoleId;
import kr.go.smes.authz.domain.AuthzUserRoleEntity;
import kr.go.smes.authz.infrastructure.AuthzRoleRepository;
import kr.go.smes.authz.infrastructure.AuthzUserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScimGroupServiceTest {

    @Mock AuthzRoleRepository roleRepository;
    @Mock AuthzUserRoleRepository userRoleRepository;
    @Mock AuthzService authzService;

    ScimGroupService service;

    private static final String AGENCY = "GOV_SMES";
    private static final String ROLE = "MANAGER";
    private static final String GROUP_ID = AGENCY + ":" + ROLE;

    @BeforeEach
    void setUp() {
        service = new ScimGroupService(roleRepository, userRoleRepository, authzService);
        lenient().when(roleRepository.existsById(new AuthzRoleId(AGENCY, ROLE))).thenReturn(true);
    }

    private AuthzUserRoleEntity member(String userId) {
        return AuthzUserRoleEntity.builder()
                .id(UUID.randomUUID()).qimUserId(userId).agencyCode(AGENCY).roleCode(ROLE)
                .status(AssignmentStatus.ACTIVE).build();
    }

    private void currentMembers(String... userIds) {
        when(userRoleRepository.findByAgencyCodeAndRoleCodeAndStatus(AGENCY, ROLE, AssignmentStatus.ACTIVE))
                .thenReturn(java.util.Arrays.stream(userIds).map(this::member).toList());
    }

    @Test
    void getGroup_unknownRole_throwsNotFound() {
        when(roleRepository.existsById(new AuthzRoleId(AGENCY, ROLE))).thenReturn(false);
        assertThatThrownBy(() -> service.getGroup(GROUP_ID))
                .isInstanceOf(AuthzException.class)
                .extracting(e -> ((AuthzException) e).getErrorCode())
                .isEqualTo(AuthzErrorCode.ROLE_NOT_FOUND);
    }

    @Test
    void getGroup_malformedId_throwsInvalid() {
        assertThatThrownBy(() -> service.getGroup("no-separator"))
                .isInstanceOf(AuthzException.class)
                .extracting(e -> ((AuthzException) e).getErrorCode())
                .isEqualTo(AuthzErrorCode.INVALID_REQUEST);
    }

    @Test
    void replaceMembers_reconciles_grantsMissing_revokesExtra() {
        // 현재: u1, u2 / 원하는: u2, u3  → grant u3, revoke u1
        currentMembers("u1", "u2");

        ScimGroup result = service.replaceMembers(GROUP_ID,
                List.of(ScimMember.of("u2"), ScimMember.of("u3")), "1.2.3.4");

        // grant u3
        verify(authzService).grantRole(
                argThatGrant("u3"), any(), any());
        // revoke u1
        verify(authzService).revokeRole(eq("u1"), eq(AGENCY), eq(ROLE), eq("SCIM"), any(), any(), any());
        // u2 변동 없음 (grant/revoke 호출 안 됨)
        verify(authzService, never()).revokeRole(eq("u2"), any(), any(), any(), any(), any(), any());
        assertThat(result.id()).isEqualTo(GROUP_ID);
    }

    @Test
    void patch_addAndRemove() {
        currentMembers("u1");
        ScimPatchOp patch = new ScimPatchOp(List.of(ScimPatchOp.SCHEMA), List.of(
                new ScimPatchOp.Operation("add", "members", List.of(ScimMember.of("u9"))),
                new ScimPatchOp.Operation("remove", "members", List.of(ScimMember.of("u1")))
        ));

        service.patch(GROUP_ID, patch, "1.2.3.4");

        verify(authzService).grantRole(argThatGrant("u9"), any(), any());
        verify(authzService).revokeRole(eq("u1"), eq(AGENCY), eq(ROLE), eq("SCIM"), any(), any(), any());
    }

    @Test
    void patch_removeViaPathFilter() {
        ScimPatchOp patch = new ScimPatchOp(List.of(ScimPatchOp.SCHEMA), List.of(
                new ScimPatchOp.Operation("remove", "members[value eq \"u7\"]", null)
        ));

        service.patch(GROUP_ID, patch, "1.2.3.4");

        verify(authzService).revokeRole(eq("u7"), eq(AGENCY), eq(ROLE), eq("SCIM"), any(), any(), any());
    }

    @Test
    void patch_unsupportedOp_throws() {
        ScimPatchOp patch = new ScimPatchOp(List.of(ScimPatchOp.SCHEMA), List.of(
                new ScimPatchOp.Operation("explode", "members", List.of(ScimMember.of("u1")))
        ));
        assertThatThrownBy(() -> service.patch(GROUP_ID, patch, "ip"))
                .isInstanceOf(AuthzException.class)
                .extracting(e -> ((AuthzException) e).getErrorCode())
                .isEqualTo(AuthzErrorCode.INVALID_REQUEST);
    }

    @Test
    void replaceMembers_emptyDesired_revokesAll() {
        currentMembers("u1", "u2");

        service.replaceMembers(GROUP_ID, List.of(), "ip");

        verify(authzService, times(2)).revokeRole(any(), eq(AGENCY), eq(ROLE), eq("SCIM"), any(), any(), any());
        verify(authzService, never()).grantRole(any(), any(), any());
    }

    private GrantRoleRequest argThatGrant(String userId) {
        return org.mockito.ArgumentMatchers.argThat(r ->
                r != null && userId.equals(r.qimUserId())
                        && AGENCY.equals(r.agencyCode()) && ROLE.equals(r.roleCode())
                        && "SCIM".equals(r.source()));
    }
}

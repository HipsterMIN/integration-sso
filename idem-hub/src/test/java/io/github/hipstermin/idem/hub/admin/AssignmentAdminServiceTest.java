package io.github.hipstermin.idem.hub.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal;
import io.github.hipstermin.idem.hub.admin.auth.AdminRole;
import io.github.hipstermin.idem.hub.admin.auth.AdminTenantScope;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.infrastructure.QAuthzClient;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssignmentAdminService — 서비스 존재·테넌트 범위·입력 검증·authz 위임·감사")
class AssignmentAdminServiceTest {

    @Mock QAuthzClient authz;
    @Mock AdminTenantScope tenantScope;
    @Mock AuditLogPublisher audit;
    @Mock ServiceProfileService serviceProfileService;
    @InjectMocks AssignmentAdminService sut;

    static final AdminPrincipal ADMIN = new AdminPrincipal("a1", "pol", AdminRole.POLICY_ADMIN, null, "sid", false);

    @BeforeEach
    void setUp() {
        given(serviceProfileService.find("AG1")).willReturn(Optional.of(ServiceProfile.builder().build()));
    }

    @Test
    @DisplayName("없는 서비스는 404 E-AGENCY-307, 테넌트 범위 밖은 403 — authz 를 부르지 않는다")
    void scopeChecks() {
        given(serviceProfileService.find("NOPE")).willReturn(Optional.empty());
        assertThatThrownBy(() -> sut.list("NOPE", 0, 50, ADMIN, "c"))
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode()).isEqualTo(PlatformErrorCode.AGENCY_NOT_FOUND));
        doThrow(new PlatformException(PlatformErrorCode.ADMIN_FORBIDDEN, null, "다른 Tenant")).when(tenantScope).checkService(ADMIN, "AG1");
        assertThatThrownBy(() -> sut.assign("AG1", "u1", null, null, ADMIN, "c"))
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode()).isEqualTo(PlatformErrorCode.ADMIN_FORBIDDEN));
        verify(authz, never()).listAgencyAssignmentRecords(any(), anyInt(), anyInt(), any());
        verify(authz, never()).assign(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("목록: size 는 1~200 으로 자른다, authz 페이지를 그대로")
    void listClamps() {
        given(authz.listAgencyAssignmentRecords("AG1", 2, 200, "c")).willReturn(new QAuthzClient.AssignmentListPage(List.of(), 401, true));
        AssignmentAdminService.Page p = sut.list("AG1", 2, 999, ADMIN, "c");
        assertThat(p.size()).isEqualTo(200);
        assertThat(p.total()).isEqualTo(401);
        assertThat(p.hasNext()).isTrue();
    }

    @Test
    @DisplayName("할당: 관리자 사용자명이 grantedBy, 감사 ADMIN/ASSIGNMENT_GRANTED(qimUserId·expiresAt·reason); 이상한 qimUserId 는 400 E-IDO-129")
    void assignAndAudit() {
        Instant exp = Instant.parse("2026-12-31T00:00:00Z");
        given(authz.assign("AG1", "u1", "pol", exp, "onboard", "c")).willReturn(new QAuthzClient.AssignmentRecord("u1", "AG1", "ACTIVE", "CONSOLE", null, "pol", exp.toString()));

        QAuthzClient.AssignmentRecord rec = sut.assign("AG1", "u1", exp, "onboard", ADMIN, "c");

        assertThat(rec.status()).isEqualTo("ACTIVE");
        ArgumentCaptor<AuditLogPublisher.AuditEntry> entry = ArgumentCaptor.forClass(AuditLogPublisher.AuditEntry.class);
        verify(audit).publish(entry.capture());
        assertThat(entry.getValue().eventAction()).isEqualTo("ASSIGNMENT_GRANTED");
        assertThat(entry.getValue().actorId()).isEqualTo("pol");
        assertThat(entry.getValue().agencyCode()).isEqualTo("AG1");
        assertThat(entry.getValue().metadata()).containsEntry("qimUserId", "u1").containsEntry("expiresAt", exp.toString()).containsEntry("reason", "onboard");

        assertThatThrownBy(() -> sut.assign("AG1", "bad id!", null, null, ADMIN, "c"))
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode()).isEqualTo(PlatformErrorCode.IDO_AUTHZ_REJECTED));
        verify(authz, never()).assign(eq("AG1"), eq("bad id!"), any(), any(), any(), any());
    }

    @Test
    @DisplayName("해제·역할 부여·회수·역할 생성도 authz 에 위임하고 각각 감사를 남긴다")
    void otherOperations() {
        given(authz.grantRole("AG1", "u1", "MANAGER", "pol", null, null, "c")).willReturn(new QAuthzClient.UserRoleRecord("r1", "u1", "AG1", "MANAGER", "ACTIVE", null, "pol", null, "CONSOLE"));
        given(authz.createRole("AG1", "VIEWER", "열람", null, "pol", "c")).willReturn(new QAuthzClient.RoleRecord("AG1", "VIEWER", "열람", null, true, null));

        sut.unassign("AG1", "u1", "bye", ADMIN, "c");
        sut.grantRole("AG1", "u1", "MANAGER", null, null, ADMIN, "c");
        sut.revokeRole("AG1", "u1", "MANAGER", null, ADMIN, "c");
        sut.createRole("AG1", "VIEWER", "열람", null, ADMIN, "c");

        verify(authz).unassign("AG1", "u1", "pol", "bye", "c");
        verify(authz).revokeRole("AG1", "u1", "MANAGER", "pol", null, "c");
        ArgumentCaptor<AuditLogPublisher.AuditEntry> entry = ArgumentCaptor.forClass(AuditLogPublisher.AuditEntry.class);
        verify(audit, org.mockito.Mockito.times(4)).publish(entry.capture());
        assertThat(entry.getAllValues().stream().map(AuditLogPublisher.AuditEntry::eventAction).toList())
                .containsExactly("ASSIGNMENT_REVOKED", "ROLE_GRANTED", "ROLE_REVOKED", "ROLE_CREATED");

        assertThatThrownBy(() -> sut.createRole("AG1", "VIEWER", " ", null, ADMIN, "c"))
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode()).isEqualTo(PlatformErrorCode.IDO_AUTHZ_REJECTED));
        verify(authz, never()).createRole(any(), any(), eq(" "), isNull(), anyString(), anyString());
    }
}

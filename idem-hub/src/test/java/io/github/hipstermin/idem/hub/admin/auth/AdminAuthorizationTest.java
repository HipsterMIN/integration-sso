package io.github.hipstermin.idem.hub.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AdminAuthorization — 역할 매트릭스 (docs/admin-auth.md)")
class AdminAuthorizationTest {

    final AdminAuthorization sut = new AdminAuthorization();
    final AdminPrincipal system = new AdminPrincipal("1", "sys", AdminRole.SYSTEM_ADMIN, null, "s", false);
    final AdminPrincipal policy = new AdminPrincipal("2", "pol", AdminRole.POLICY_ADMIN, null, "s", false);
    final AdminPrincipal auditor = new AdminPrincipal("3", "aud", AdminRole.AUDITOR, null, "s", false);
    final AdminPrincipal tenantSys = new AdminPrincipal("4", "tsys", AdminRole.SYSTEM_ADMIN, "T1", "s", false);

    @Test
    void auditorIsReadOnlyExceptSelfService() {
        assertThat(sut.allowed(auditor, "GET", "/api/v1/admin/services/A/profile")).isTrue();
        assertThat(sut.allowed(auditor, "PUT", "/api/v1/admin/services/A/profile")).isFalse();
        assertThat(sut.allowed(auditor, "GET", "/api/v1/admin/audit")).isTrue();
        assertThat(sut.allowed(auditor, "POST", "/api/v1/admin/auth/logout")).isTrue();
        assertThat(sut.allowed(auditor, "POST", "/api/v1/admin/auth/password")).isTrue();
        assertThat(sut.allowed(auditor, "GET", "/api/v1/admin/admins")).isFalse();
        assertThat(sut.allowed(auditor, "DELETE", "/api/v1/handoff/t1")).isFalse();
    }

    @Test
    void policyAdminManagesServicesNotAdmins() {
        assertThat(sut.allowed(policy, "PUT", "/api/v1/admin/services/A/profile")).isTrue();
        assertThat(sut.allowed(policy, "POST", "/api/v1/admin/agencies/A/rotate-key")).isTrue();
        assertThat(sut.allowed(policy, "DELETE", "/api/v1/handoff/t1")).isTrue();
        assertThat(sut.allowed(policy, "GET", "/api/v1/admin/tenants")).isTrue();
        assertThat(sut.allowed(policy, "PUT", "/api/v1/admin/tenants/T1")).isFalse();
        assertThat(sut.allowed(policy, "GET", "/api/v1/admin/admins")).isFalse();
        assertThat(sut.allowed(policy, "POST", "/api/v1/admin/admins")).isFalse();
        assertThat(sut.allowed(policy, "GET", "/actuator/features")).isFalse();
    }

    @Test
    void systemAdminEverythingButTenantScopedNotAdmins() {
        assertThat(sut.allowed(system, "POST", "/api/v1/admin/admins")).isTrue();
        assertThat(sut.allowed(system, "PUT", "/api/v1/admin/tenants/T1")).isTrue();
        assertThat(sut.allowed(system, "GET", "/actuator/features")).isTrue();
        assertThat(sut.allowed(tenantSys, "POST", "/api/v1/admin/admins")).isFalse();
        assertThat(sut.allowed(tenantSys, "PUT", "/api/v1/admin/tenants/T1")).isFalse();
        assertThat(sut.allowed(tenantSys, "GET", "/api/v1/admin/tenants")).isTrue();
        assertThat(sut.allowed(tenantSys, "PUT", "/api/v1/admin/services/A/profile")).isTrue();
    }
}

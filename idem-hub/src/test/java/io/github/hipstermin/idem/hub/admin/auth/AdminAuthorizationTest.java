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
        // 1.1 관찰 모드: 이상 플래그는 읽고 검토(POST)할 수 있다 — 감사자의 일. AI 초안(POST)은 쓰기 권한자만
        assertThat(sut.allowed(auditor, "GET", "/api/v1/admin/anomalies")).isTrue();
        assertThat(sut.allowed(auditor, "POST", "/api/v1/admin/anomalies/f1/review")).isTrue();
        assertThat(sut.allowed(auditor, "GET", "/api/v1/admin/ai/status")).isTrue();
        assertThat(sut.allowed(auditor, "POST", "/api/v1/admin/ai/profile-draft")).isFalse();
        // 1.1 동의 카탈로그: "그 외" 규칙 — 목록은 전 역할, 발행·종료는 쓰기 권한자만 (플랫폼 공통의 전역 제한은 컨트롤러가 본다)
        assertThat(sut.allowed(auditor, "GET", "/api/v1/admin/services/A/consents")).isTrue();
        assertThat(sut.allowed(auditor, "POST", "/api/v1/admin/services/A/consents")).isFalse();
        assertThat(sut.allowed(auditor, "GET", "/api/v1/admin/consents")).isTrue();
        assertThat(sut.allowed(auditor, "POST", "/api/v1/admin/consents/v1/retire")).isFalse();
        // 1.1.1 할당 관리: "그 외" 규칙 — 목록은 전 역할, 할당·역할 부여는 쓰기 권한자만
        assertThat(sut.allowed(auditor, "GET", "/api/v1/admin/services/A/assignments")).isTrue();
        assertThat(sut.allowed(auditor, "POST", "/api/v1/admin/services/A/assignments")).isFalse();
        assertThat(sut.allowed(auditor, "DELETE", "/api/v1/admin/services/A/assignments/u1/roles/R")).isFalse();
    }

    @Test
    void policyAdminManagesServicesNotAdmins() {
        assertThat(sut.allowed(policy, "PUT", "/api/v1/admin/services/A/profile")).isTrue();
        assertThat(sut.allowed(policy, "POST", "/api/v1/admin/agencies/A/rotate-key")).isTrue();
        assertThat(sut.allowed(policy, "POST", "/api/v1/admin/services/A/assignments")).isTrue();
        assertThat(sut.allowed(policy, "DELETE", "/api/v1/admin/services/A/assignments/u1")).isTrue();
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

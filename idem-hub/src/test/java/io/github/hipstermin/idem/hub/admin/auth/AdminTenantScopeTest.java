package io.github.hipstermin.idem.hub.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 시험 항목 B-8 "테넌트 범위" 의 단위 부분 (G2-13, 2026-10-16) — 끝-끝은 {@code TenantScopeIntegrationTest} */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminTenantScope — Tenant 관리자는 자기 Tenant 의 Service 만 (B-8)")
class AdminTenantScopeTest {

    @Mock ServiceProfileService profiles;

    private static final AdminPrincipal GLOBAL = new AdminPrincipal("1", "admin", AdminRole.SYSTEM_ADMIN, null, "s", false);
    private static final AdminPrincipal T2     = new AdminPrincipal("2", "padmin", AdminRole.POLICY_ADMIN, "T2", "s", false);

    private static ServiceProfile profileOf(String code, String tenant) {
        return new ServiceProfile(1, new ServiceProfile.Service(code, code, ServiceProfile.ServiceStatus.ACTIVE, tenant),
                null, null, null, null, null, null);
    }

    @Test
    @DisplayName("다른 Tenant(DEFAULT)의 Service 는 403 ADMIN_FORBIDDEN, 자기 Tenant 는 통과, 없는 Service 는 통과(호출자가 404)")
    void checkService() {
        AdminTenantScope scope = new AdminTenantScope(profiles);
        given(profiles.find("SVC_DEF")).willReturn(Optional.of(profileOf("SVC_DEF", null)));
        given(profiles.find("SVC_T2")).willReturn(Optional.of(profileOf("SVC_T2", "T2")));
        given(profiles.find("NOPE")).willReturn(Optional.empty());

        assertThatThrownBy(() -> scope.checkService(T2, "SVC_DEF"))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode()).isEqualTo(PlatformErrorCode.ADMIN_FORBIDDEN));
        assertThatCode(() -> scope.checkService(T2, "SVC_T2")).doesNotThrowAnyException();
        assertThatCode(() -> scope.checkService(T2, "NOPE")).doesNotThrowAnyException();
        assertThat(scope.inScope(T2, "SVC_DEF")).isFalse();
        assertThat(scope.inScope(T2, "SVC_T2")).isTrue();
        assertThat(scope.inScope(T2, "NOPE")).isFalse();
    }

    @Test
    @DisplayName("글로벌 관리자는 모든 Tenant 를 본다")
    void globalSeesAll() {
        AdminTenantScope scope = new AdminTenantScope(profiles);
        assertThatCode(() -> scope.checkService(GLOBAL, "ANY")).doesNotThrowAnyException();
        assertThatCode(() -> scope.checkTenant(GLOBAL, "T9")).doesNotThrowAnyException();
        assertThat(scope.inScope(GLOBAL, "ANY")).isTrue();
    }

    @Test
    @DisplayName("본문의 service.tenant 위장 — 비어 있으면 DEFAULT 로 보고 Tenant 관리자는 403, 자기 Tenant 만 통과")
    void checkTenant() {
        AdminTenantScope scope = new AdminTenantScope(profiles);
        assertThatThrownBy(() -> scope.checkTenant(T2, "DEFAULT")).isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> scope.checkTenant(T2, null)).isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> scope.checkTenant(T2, "")).isInstanceOf(PlatformException.class);
        assertThatCode(() -> scope.checkTenant(T2, "T2")).doesNotThrowAnyException();
    }
}

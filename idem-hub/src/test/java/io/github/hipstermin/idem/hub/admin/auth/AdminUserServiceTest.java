package io.github.hipstermin.idem.hub.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminUserService — 생성(임시 비밀번호 1회)·마지막 SYSTEM_ADMIN 보호·자기 강등 금지")
class AdminUserServiceTest {

    @Mock AdminUserRepository users;
    @Mock AdminSessionStore sessions;
    @Mock AdminAuthService authService;
    @Mock AdminAuditor auditor;
    PasswordPolicy policy = new PasswordPolicy(new AdminProperties());
    AdminUserService sut;
    final AdminPrincipal actor = new AdminPrincipal("a1", "sys", AdminRole.SYSTEM_ADMIN, null, "s", false);

    @BeforeEach
    void setUp() {
        sut = new AdminUserService(users, sessions, authService, policy, auditor);
        given(users.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(users.findByUsername(anyString())).willReturn(Optional.empty());
    }

    @Test
    void createReturnsTemporaryPasswordOnce() {
        Map<String, Object> r = sut.create("bob", "Bob", AdminRole.AUDITOR, "", actor, "ip");
        String temp = (String) r.get("temporaryPassword");
        assertThat(temp).isNotBlank();
        AdminUserService.AdminView v = (AdminUserService.AdminView) r.get("admin");
        assertThat(v.role()).isEqualTo(AdminRole.AUDITOR);
        assertThat(v.mustChangePassword()).isTrue();
        assertThat(v.tenantCode()).isNull();
        assertThatThrownBy(() -> sut.create("Bad Name", null, null, null, actor, "ip"))
                .isInstanceOf(PlatformException.class).extracting("errorCode").isEqualTo(PlatformErrorCode.ADMIN_PASSWORD_POLICY);
        given(users.findByUsername("bob")).willReturn(Optional.of(AdminUserEntity.builder().adminId("x").username("bob").role(AdminRole.AUDITOR).passwordHash("h").build()));
        assertThatThrownBy(() -> sut.create("bob", null, null, null, actor, "ip"))
                .isInstanceOf(PlatformException.class).extracting("errorCode").isEqualTo(PlatformErrorCode.ADMIN_CONFLICT);
    }

    @Test
    void cannotDemoteLastSystemAdminOrSelf() {
        AdminUserEntity other = AdminUserEntity.builder().adminId("a2").username("other").role(AdminRole.SYSTEM_ADMIN).passwordHash("h").build();
        given(users.findById("a2")).willReturn(Optional.of(other));
        given(users.findById("a1")).willReturn(Optional.of(AdminUserEntity.builder().adminId("a1").username("sys").role(AdminRole.SYSTEM_ADMIN).passwordHash("h").build()));
        given(users.countByRoleAndStatus(AdminRole.SYSTEM_ADMIN, AdminStatus.ACTIVE)).willReturn(1L);
        assertThatThrownBy(() -> sut.update("a2", AdminRole.AUDITOR, null, null, null, actor, "ip"))
                .isInstanceOf(PlatformException.class).extracting("errorCode").isEqualTo(PlatformErrorCode.ADMIN_LAST_SYSTEM_ADMIN);
        assertThatThrownBy(() -> sut.update("a1", null, AdminStatus.DISABLED, null, null, actor, "ip"))
                .isInstanceOf(PlatformException.class).extracting("errorCode").isEqualTo(PlatformErrorCode.ADMIN_LAST_SYSTEM_ADMIN);

        given(users.countByRoleAndStatus(AdminRole.SYSTEM_ADMIN, AdminStatus.ACTIVE)).willReturn(2L);
        AdminUserService.AdminView v = sut.update("a2", AdminRole.AUDITOR, AdminStatus.DISABLED, "T1", null, actor, "ip");
        assertThat(v.role()).isEqualTo(AdminRole.AUDITOR);
        assertThat(v.status()).isEqualTo(AdminStatus.DISABLED);
        assertThat(v.tenantCode()).isEqualTo("T1");
        verify(sessions).deleteAllOf("a2");
    }

    @Test
    void unlockAndResetMfa() {
        AdminUserEntity u = AdminUserEntity.builder().adminId("a2").username("other").role(AdminRole.AUDITOR).passwordHash("h")
                .status(AdminStatus.LOCKED).failedAttempts((short) 5).totpEnrolled(true).totpSecretEnc("v1:x:y").build();
        given(users.findById("a2")).willReturn(Optional.of(u));
        assertThat(sut.unlock("a2", actor, "ip").status()).isEqualTo(AdminStatus.ACTIVE);
        assertThat(u.getFailedAttempts()).isZero();
        assertThat(sut.resetMfa("a2", actor, "ip").totpEnrolled()).isFalse();
        assertThat(u.getTotpSecretEnc()).isNull();
        verify(sessions).deleteAllOf("a2");
        assertThatThrownBy(() -> sut.get("missing")).isInstanceOf(PlatformException.class).extracting("errorCode").isEqualTo(PlatformErrorCode.ADMIN_NOT_FOUND);
    }
}

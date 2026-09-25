package io.github.hipstermin.idem.hub.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/** S7: 로그인 → 2단계 등록/검증 → 세션, 실패 5회 잠금, 비밀번호 변경. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminAuthService")
class AdminAuthServiceTest {

    @Mock AdminUserRepository users;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    @Mock AdminAuditor auditor;
    @Mock JdbcTemplate jdbc;
    final Map<String, String> kv = new HashMap<>();
    AdminProperties props = new AdminProperties();
    PasswordPolicy policy = new PasswordPolicy(props);
    TotpService totp = new TotpService(props);
    AdminSecretCipher cipher;
    AdminSessionStore sessions;
    AdminAuthService sut;
    AdminUserEntity user;

    @BeforeEach
    void setUp() {
        props.setAllowDerivedSecretKey(true);
        cipher = new AdminSecretCipher(props, new MockEnvironment());
        cipher.init();
        given(redis.opsForValue()).willReturn(values);
        given(values.get(anyString())).willAnswer(inv -> kv.get(inv.getArgument(0, String.class)));
        org.mockito.Mockito.doAnswer(inv -> { kv.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(java.time.Duration.class));
        given(values.getAndDelete(anyString())).willAnswer(inv -> kv.remove(inv.getArgument(0, String.class)));
        given(redis.delete(anyString())).willAnswer(inv -> kv.remove(inv.getArgument(0, String.class)) != null);
        sessions = new AdminSessionStore(redis, new ObjectMapper().findAndRegisterModules(), props);
        sut = new AdminAuthService(users, sessions, totp, cipher, policy, props, auditor, jdbc);
        user = AdminUserEntity.builder().adminId("a1").username("alice").role(AdminRole.POLICY_ADMIN)
                .passwordHash(policy.hash("Correct-Horse-9")).mustChangePassword(false).build();
        given(users.findByUsername("alice")).willReturn(Optional.of(user));
        given(users.findById("a1")).willReturn(Optional.of(user));
        given(users.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), anyInt())).willReturn(List.of());
    }

    @Test
    @DisplayName("첫 로그인: 비밀번호 통과 → TOTP 등록 요구 → 코드 검증 → 세션 (등록 비밀은 봉인 저장)")
    void firstLoginEnrollsTotp() {
        AdminAuthService.LoginResult r = sut.login("alice", "Correct-Horse-9", "1.1.1.1");
        assertThat(r.status()).isEqualTo(AdminAuthService.LoginStatus.MFA_ENROLL_REQUIRED);
        assertThat(r.secret()).isNotBlank();
        assertThat(r.otpauthUri()).contains("alice");
        assertThat(r.session()).isNull();

        String code = totp.currentCode(r.secret(), Instant.now());
        AdminSessionStore.AdminSession s = sut.verifyMfa(r.mfaToken(), code, "1.1.1.1");
        assertThat(s.username()).isEqualTo("alice");
        assertThat(user.isTotpEnrolled()).isTrue();
        assertThat(cipher.open(user.getTotpSecretEnc())).isEqualTo(r.secret());
        assertThat(user.getLastLoginAt()).isNotNull();
        verify(auditor).success(eq(AdminAuthService.ACTION_MFA_ENROLLED), eq("alice"), anyString(), anyString(), anyString());
        verify(auditor).success(eq(AdminAuthService.ACTION_LOGIN_SUCCESS), eq("alice"), anyString(), anyString(), anyString());

        // 다음 로그인은 검증만
        AdminAuthService.LoginResult r2 = sut.login("alice", "Correct-Horse-9", "1.1.1.1");
        assertThat(r2.status()).isEqualTo(AdminAuthService.LoginStatus.MFA_REQUIRED);
        assertThat(r2.secret()).isNull();
        assertThatThrownBy(() -> sut.verifyMfa(r2.mfaToken(), "000000", "1.1.1.1"))
                .isInstanceOf(PlatformException.class).extracting("errorCode").isEqualTo(PlatformErrorCode.ADMIN_MFA_REQUIRED);
        assertThat(user.getFailedAttempts()).isEqualTo((short) 1);
    }

    @Test
    @DisplayName("mfa.required=false 면 비밀번호만으로 세션")
    void noMfaWhenNotRequired() {
        props.getMfa().setRequired(false);
        AdminAuthService.LoginResult r = sut.login("alice", "Correct-Horse-9", "ip");
        assertThat(r.status()).isEqualTo(AdminAuthService.LoginStatus.OK);
        assertThat(r.session()).isNotNull();
        assertThat(sessions.touch(r.session().sessionId())).isPresent();
    }

    @Test
    @DisplayName("틀린 비밀번호 5회 → LOCKED(15분), 잠긴 동안은 맞는 비밀번호도 E-IDO-133; 모르는 사용자는 같은 E-IDO-132")
    void lockoutAfterFiveFailures() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> sut.login("alice", "wrong", "ip"))
                    .isInstanceOf(PlatformException.class).extracting("errorCode").isEqualTo(PlatformErrorCode.ADMIN_LOGIN_FAILED);
        }
        assertThat(user.getStatus()).isEqualTo(AdminStatus.LOCKED);
        assertThat(user.getLockedUntil()).isAfter(Instant.now().plusSeconds(14 * 60));
        verify(auditor).failure(eq(AdminAuthService.ACTION_LOCKED), eq("alice"), anyString(), anyString(), anyString(), anyString());
        assertThatThrownBy(() -> sut.login("alice", "Correct-Horse-9", "ip"))
                .isInstanceOf(PlatformException.class).extracting("errorCode").isEqualTo(PlatformErrorCode.ADMIN_LOCKED);
        assertThatThrownBy(() -> sut.login("nobody", "x", "ip"))
                .isInstanceOf(PlatformException.class).extracting("errorCode").isEqualTo(PlatformErrorCode.ADMIN_LOGIN_FAILED);

        // 잠금 시한이 지나면 자동 해제
        user.setLockedUntil(Instant.now().minusSeconds(1));
        props.getMfa().setRequired(false);
        assertThat(sut.login("alice", "Correct-Horse-9", "ip").status()).isEqualTo(AdminAuthService.LoginStatus.OK);
        assertThat(user.getStatus()).isEqualTo(AdminStatus.ACTIVE);
        assertThat(user.getFailedAttempts()).isZero();
    }

    @Test
    void disabledUserCannotLogin() {
        user.setStatus(AdminStatus.DISABLED);
        assertThatThrownBy(() -> sut.login("alice", "Correct-Horse-9", "ip"))
                .isInstanceOf(PlatformException.class).extracting("errorCode").isEqualTo(PlatformErrorCode.ADMIN_LOGIN_FAILED);
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("비밀번호 변경: 현재 비밀번호 확인·정책·이력, 세션의 '변경 필요' 해제")
    void changePassword() {
        props.getMfa().setRequired(false);
        user.setMustChangePassword(true);
        AdminSessionStore.AdminSession s = sut.login("alice", "Correct-Horse-9", "ip").session();
        AdminPrincipal p = s.principal();
        assertThat(p.mustChangePassword()).isTrue();

        assertThatThrownBy(() -> sut.changePassword(p, "wrong", "New-Password-77", "ip"))
                .isInstanceOf(PlatformException.class).extracting("errorCode").isEqualTo(PlatformErrorCode.ADMIN_LOGIN_FAILED);
        assertThatThrownBy(() -> sut.changePassword(p, "Correct-Horse-9", "short", "ip"))
                .isInstanceOf(PlatformException.class).extracting("errorCode").isEqualTo(PlatformErrorCode.ADMIN_PASSWORD_POLICY);
        assertThatThrownBy(() -> sut.changePassword(p, "Correct-Horse-9", "Correct-Horse-9", "ip"))
                .as("현재와 같은 비밀번호").isInstanceOf(PlatformException.class).extracting("errorCode").isEqualTo(PlatformErrorCode.ADMIN_PASSWORD_POLICY);

        sut.changePassword(p, "Correct-Horse-9", "New-Password-77", "ip");
        assertThat(policy.matches("New-Password-77", user.getPasswordHash())).isTrue();
        assertThat(user.isMustChangePassword()).isFalse();
        verify(jdbc).update(eq("INSERT INTO ido.admin_password_history (admin_id, password_hash) VALUES (?, ?)"), eq("a1"), anyString());
        assertThat(sessions.touch(s.sessionId()).orElseThrow().mustChangePassword()).isFalse();
    }

    @Test
    void logoutEndsSession() {
        props.getMfa().setRequired(false);
        AdminSessionStore.AdminSession s = sut.login("alice", "Correct-Horse-9", "ip").session();
        sut.logout(s.principal(), "ip");
        assertThat(sessions.touch(s.sessionId())).isEmpty();
    }
}

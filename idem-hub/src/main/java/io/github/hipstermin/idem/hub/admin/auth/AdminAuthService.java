package io.github.hipstermin.idem.hub.admin.auth;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 로그인 — 비밀번호 → (2단계 TOTP 등록/검증) → 세션. 실패 임계치 잠금, 감사.
 *
 * <p>실패 응답은 사용자명 존재 여부를 구분하지 않는다(E-IDO-132 하나). 잠금은 별도 코드(E-IDO-133)로 알린다 —
 * 잠긴 계정에 계속 시도하는 것은 정상 사용자에게도 알려야 하는 상태이기 때문이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    public static final String ACTION_LOGIN_SUCCESS = "ADMIN_LOGIN_SUCCESS";
    public static final String ACTION_LOGIN_FAILED = "ADMIN_LOGIN_FAILED";
    public static final String ACTION_LOCKED = "ADMIN_LOCKED";
    public static final String ACTION_MFA_ENROLLED = "ADMIN_MFA_ENROLLED";
    public static final String ACTION_MFA_FAILED = "ADMIN_MFA_FAILED";
    public static final String ACTION_LOGOUT = "ADMIN_LOGOUT";
    public static final String ACTION_PASSWORD_CHANGED = "ADMIN_PASSWORD_CHANGED";

    private final AdminUserRepository users;
    private final AdminSessionStore sessions;
    private final TotpService totp;
    private final AdminSecretCipher cipher;
    private final PasswordPolicy passwordPolicy;
    private final AdminProperties props;
    private final AdminAuditor auditor;
    private final JdbcTemplate jdbcTemplate;

    public enum LoginStatus { OK, MFA_REQUIRED, MFA_ENROLL_REQUIRED }

    /** @param secret ENROLL 일 때만 — 인증 앱에 등록할 base32 비밀과 otpauth URI */
    public record LoginResult(LoginStatus status, String mfaToken, String secret, String otpauthUri,
                              AdminSessionStore.AdminSession session) {}

    /** 실패 카운터·잠금은 예외를 던져도 남아야 한다 — PlatformException 은 롤백하지 않는다 */
    @Transactional(noRollbackFor = PlatformException.class)
    public LoginResult login(String username, String password, String ip) {
        Instant now = Instant.now();
        AdminUserEntity user = users.findByUsername(username == null ? "" : username.trim()).orElse(null);
        if (user == null) {
            auditor.failure(ACTION_LOGIN_FAILED, username, "ADMIN", null, ip, "unknown user");
            throw new PlatformException(PlatformErrorCode.ADMIN_LOGIN_FAILED, null);
        }
        if (user.getStatus() == AdminStatus.DISABLED) {
            auditor.failure(ACTION_LOGIN_FAILED, username, "ADMIN", user.getAdminId(), ip, "disabled");
            throw new PlatformException(PlatformErrorCode.ADMIN_LOGIN_FAILED, null);
        }
        if (user.isEffectivelyLocked(now)) {
            auditor.failure(ACTION_LOGIN_FAILED, username, "ADMIN", user.getAdminId(), ip, "locked");
            throw new PlatformException(PlatformErrorCode.ADMIN_LOCKED, null);
        }
        if (user.getStatus() == AdminStatus.LOCKED) {
            // 잠금 시한이 지났다 — 자동 해제
            user.setStatus(AdminStatus.ACTIVE);
            user.setFailedAttempts((short) 0);
            user.setLockedUntil(null);
        }
        if (!passwordPolicy.matches(password, user.getPasswordHash())) {
            registerFailure(user, ip, "bad password", now);
            throw new PlatformException(PlatformErrorCode.ADMIN_LOGIN_FAILED, null);
        }

        // 비밀번호 통과 — 2단계
        if (props.getMfa().isRequired() || user.isTotpEnrolled()) {
            if (user.isTotpEnrolled()) {
                String token = sessions.createMfaToken(new AdminSessionStore.PendingMfa(user.getAdminId(), "VERIFY", null, ip));
                return new LoginResult(LoginStatus.MFA_REQUIRED, token, null, null, null);
            }
            String secret = totp.generateSecret();
            String token = sessions.createMfaToken(new AdminSessionStore.PendingMfa(user.getAdminId(), "ENROLL", cipher.seal(secret), ip));
            return new LoginResult(LoginStatus.MFA_ENROLL_REQUIRED, token, secret, totp.otpauthUri(user.getUsername(), secret), null);
        }
        return new LoginResult(LoginStatus.OK, null, null, null, completeLogin(user, ip, now));
    }

    @Transactional(noRollbackFor = PlatformException.class)
    public AdminSessionStore.AdminSession verifyMfa(String mfaToken, String code, String ip) {
        Instant now = Instant.now();
        AdminSessionStore.PendingMfa pending = sessions.consumeMfaToken(mfaToken)
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.ADMIN_MFA_REQUIRED, null, "2단계 인증 대기 토큰이 없거나 만료"));
        AdminUserEntity user = users.findById(pending.adminId())
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.ADMIN_LOGIN_FAILED, null));
        if (user.getStatus() == AdminStatus.DISABLED || user.isEffectivelyLocked(now)) {
            throw new PlatformException(PlatformErrorCode.ADMIN_LOCKED, null);
        }
        String secret;
        boolean enrolling = "ENROLL".equals(pending.purpose());
        if (enrolling) {
            secret = cipher.open(pending.secretSealed());
        } else {
            if (user.getTotpSecretEnc() == null) throw new PlatformException(PlatformErrorCode.ADMIN_MFA_REQUIRED, null, "TOTP 미등록");
            secret = cipher.open(user.getTotpSecretEnc());
        }
        if (!totp.verify(secret, code, now)) {
            registerFailure(user, ip, enrolling ? "bad enroll code" : "bad totp", now);
            auditor.failure(ACTION_MFA_FAILED, user.getUsername(), "ADMIN", user.getAdminId(), ip, enrolling ? "enroll" : "verify");
            throw new PlatformException(PlatformErrorCode.ADMIN_MFA_REQUIRED, null, "2단계 인증 코드 불일치");
        }
        if (enrolling) {
            user.setTotpSecretEnc(cipher.seal(secret));
            user.setTotpEnrolled(true);
            auditor.success(ACTION_MFA_ENROLLED, user.getUsername(), "ADMIN", user.getAdminId(), ip);
        }
        return completeLogin(user, ip, now);
    }

    public Optional<AdminSessionStore.AdminSession> resolve(String sid) {
        return sessions.touch(sid);
    }

    public void logout(AdminPrincipal principal, String ip) {
        sessions.delete(principal.sessionId());
        auditor.success(ACTION_LOGOUT, principal.username(), "ADMIN", principal.adminId(), ip);
    }

    @Transactional
    public void changePassword(AdminPrincipal principal, String current, String next, String ip) {
        AdminUserEntity user = users.findById(principal.adminId())
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.ADMIN_UNAUTHENTICATED, null));
        if (!passwordPolicy.matches(current, user.getPasswordHash())) {
            auditor.failure(ACTION_PASSWORD_CHANGED, user.getUsername(), "ADMIN", user.getAdminId(), ip, "current password mismatch");
            throw new PlatformException(PlatformErrorCode.ADMIN_LOGIN_FAILED, null, "현재 비밀번호 불일치");
        }
        applyNewPassword(user, next, false);
        auditor.success(ACTION_PASSWORD_CHANGED, user.getUsername(), "ADMIN", user.getAdminId(), ip);
        // 현재 세션의 '변경 필요' 표시 해제 (다른 세션은 concurrent=1 이라 없다)
        sessions.touch(principal.sessionId()).ifPresent(s -> sessions.update(s.withMustChangePassword(false)));
    }

    /** 정책 검사 + 이력 기록 + 해시 저장. {@code mustChange} 는 관리자 재설정(임시 비밀번호)일 때 true */
    void applyNewPassword(AdminUserEntity user, String next, boolean mustChange) {
        List<String> recent = jdbcTemplate.query(
                "SELECT password_hash FROM ido.admin_password_history WHERE admin_id = ? ORDER BY changed_at DESC LIMIT ?",
                (rs, i) -> rs.getString(1), user.getAdminId(), props.getPassword().getHistory());
        List<String> v = passwordPolicy.violations(next, user.getUsername(), recent);
        if (!recent.contains(user.getPasswordHash()) && passwordPolicy.matches(next, user.getPasswordHash())) {
            v.add("현재 비밀번호와 같을 수 없음");
        }
        if (!v.isEmpty()) {
            throw new PlatformException(PlatformErrorCode.ADMIN_PASSWORD_POLICY, null, String.join(", ", v));
        }
        jdbcTemplate.update("INSERT INTO ido.admin_password_history (admin_id, password_hash) VALUES (?, ?)",
                user.getAdminId(), user.getPasswordHash());
        user.setPasswordHash(passwordPolicy.hash(next));
        user.setPasswordChangedAt(Instant.now());
        user.setMustChangePassword(mustChange);
        user.setUpdatedAt(Instant.now());
        users.save(user);
    }

    private AdminSessionStore.AdminSession completeLogin(AdminUserEntity user, String ip, Instant now) {
        user.setFailedAttempts((short) 0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        user.setLastLoginIp(ip);
        user.setUpdatedAt(now);
        users.save(user);
        AdminSessionStore.AdminSession s = sessions.create(user, ip);
        auditor.success(ACTION_LOGIN_SUCCESS, user.getUsername(), "ADMIN", user.getAdminId(), ip);
        log.info("[AdminAuth] 로그인 username={} role={} tenant={} ip={}", user.getUsername(), user.getRole(), user.getTenantCode(), ip);
        return s;
    }

    private void registerFailure(AdminUserEntity user, String ip, String reason, Instant now) {
        short attempts = (short) (user.getFailedAttempts() + 1);
        user.setFailedAttempts(attempts);
        user.setUpdatedAt(now);
        if (attempts >= props.getLock().getMaxAttempts()) {
            user.setStatus(AdminStatus.LOCKED);
            user.setLockedUntil(now.plus(Duration.ofMinutes(props.getLock().getDurationMinutes())));
            sessions.deleteAllOf(user.getAdminId());
            auditor.failure(ACTION_LOCKED, user.getUsername(), "ADMIN", user.getAdminId(), ip, "attempts=" + attempts);
            log.warn("[AdminAuth] 잠금 username={} attempts={} until={}", user.getUsername(), attempts, user.getLockedUntil());
        }
        users.save(user);
        auditor.failure(ACTION_LOGIN_FAILED, user.getUsername(), "ADMIN", user.getAdminId(), ip, reason + " attempts=" + attempts);
    }
}

package io.github.hipstermin.idem.hub.admin.auth;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.util.UuidV7;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 계정 관리 (SYSTEM_ADMIN 전용) — 생성·역할/상태/테넌트 변경·비밀번호 재설정·잠금 해제·2단계 초기화. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    public static final String ACTION_CREATED = "ADMIN_CREATED";
    public static final String ACTION_UPDATED = "ADMIN_UPDATED";
    public static final String ACTION_PASSWORD_RESET = "ADMIN_PASSWORD_RESET";
    public static final String ACTION_UNLOCKED = "ADMIN_UNLOCKED";
    public static final String ACTION_MFA_RESET = "ADMIN_MFA_RESET";

    private final AdminUserRepository users;
    private final AdminSessionStore sessions;
    private final AdminAuthService authService;
    private final PasswordPolicy passwordPolicy;
    private final AdminAuditor auditor;

    public record AdminView(String adminId, String username, String displayName, AdminRole role, String tenantCode,
                            AdminStatus status, boolean totpEnrolled, boolean mustChangePassword, Instant lastLoginAt,
                            Instant lockedUntil, Instant createdAt) {
        static AdminView of(AdminUserEntity u) {
            return new AdminView(u.getAdminId(), u.getUsername(), u.getDisplayName(), u.getRole(), u.getTenantCode(), u.getStatus(),
                    u.isTotpEnrolled(), u.isMustChangePassword(), u.getLastLoginAt(), u.getLockedUntil(), u.getCreatedAt());
        }
    }

    public List<AdminView> list() {
        return users.findAllByOrderByUsernameAsc().stream().map(AdminView::of).toList();
    }

    public AdminView get(String adminId) {
        return AdminView.of(find(adminId));
    }

    /** @return 생성된 계정과 1회만 보이는 임시 비밀번호 */
    @Transactional
    public Map<String, Object> create(String username, String displayName, AdminRole role, String tenantCode,
                                      AdminPrincipal actor, String ip) {
        if (username == null || !username.matches("[a-z0-9._-]{3,64}")) {
            throw new PlatformException(PlatformErrorCode.ADMIN_PASSWORD_POLICY, null, "username 은 소문자·숫자·._- 3~64자");
        }
        if (users.findByUsername(username).isPresent()) {
            throw new PlatformException(PlatformErrorCode.ADMIN_CONFLICT, null, "이미 있는 관리자: " + username);
        }
        String temp = temporaryPassword();
        AdminUserEntity u = AdminUserEntity.builder()
                .adminId(UuidV7.generate()).username(username).displayName(displayName)
                .passwordHash(passwordPolicy.hash(temp)).role(role == null ? AdminRole.AUDITOR : role)
                .tenantCode(blankToNull(tenantCode)).status(AdminStatus.ACTIVE)
                .mustChangePassword(true).createdBy(actor.username()).build();
        users.save(u);
        auditor.record(ACTION_CREATED, actor.username(), "ADMIN", u.getAdminId(), ip, "SUCCESS", null,
                Map.of("username", username, "role", u.getRole().name(), "tenant", tenantCode == null ? "" : tenantCode));
        return Map.of("admin", AdminView.of(u), "temporaryPassword", temp,
                "warning", "임시 비밀번호는 지금만 표시됩니다. 첫 로그인에서 변경을 요구합니다.");
    }

    @Transactional
    public AdminView update(String adminId, AdminRole role, AdminStatus status, String tenantCode, String displayName,
                            AdminPrincipal actor, String ip) {
        AdminUserEntity u = find(adminId);
        boolean self = u.getAdminId().equals(actor.adminId());
        if (self && ((role != null && role != AdminRole.SYSTEM_ADMIN) || (status != null && status != AdminStatus.ACTIVE))) {
            throw new PlatformException(PlatformErrorCode.ADMIN_LAST_SYSTEM_ADMIN, null, "자기 자신의 역할을 낮추거나 비활성화할 수 없습니다");
        }
        if (u.getRole() == AdminRole.SYSTEM_ADMIN && u.getStatus() == AdminStatus.ACTIVE
                && ((role != null && role != AdminRole.SYSTEM_ADMIN) || (status != null && status != AdminStatus.ACTIVE))
                && users.countByRoleAndStatus(AdminRole.SYSTEM_ADMIN, AdminStatus.ACTIVE) <= 1) {
            throw new PlatformException(PlatformErrorCode.ADMIN_LAST_SYSTEM_ADMIN, null);
        }
        boolean scopeChanged = false;   // 1.0.1 (3차 점검 M2): 역할·테넌트가 바뀌면 살아 있는 세션도 끝낸다 — 세션이 역할을 캐시한다
        if (role != null) {
            scopeChanged |= role != u.getRole();
            u.setRole(role);
        }
        if (status != null) {
            u.setStatus(status);
            if (status == AdminStatus.ACTIVE) { u.setFailedAttempts((short) 0); u.setLockedUntil(null); }
            if (status != AdminStatus.ACTIVE) scopeChanged = true;
        }
        if (tenantCode != null) {
            String next = blankToNull(tenantCode);
            scopeChanged |= !java.util.Objects.equals(next, u.getTenantCode());
            u.setTenantCode(next);
        }
        if (scopeChanged) sessions.deleteAllOf(u.getAdminId());
        if (displayName != null) u.setDisplayName(displayName);
        u.setUpdatedAt(Instant.now());
        users.save(u);
        auditor.record(ACTION_UPDATED, actor.username(), "ADMIN", u.getAdminId(), ip, "SUCCESS", null,
                Map.of("role", u.getRole().name(), "status", u.getStatus().name(), "tenant", u.getTenantCode() == null ? "" : u.getTenantCode()));
        return AdminView.of(u);
    }

    @Transactional
    public Map<String, Object> resetPassword(String adminId, AdminPrincipal actor, String ip) {
        AdminUserEntity u = find(adminId);
        String temp = temporaryPassword();
        authService.applyNewPassword(u, temp, true);
        sessions.deleteAllOf(u.getAdminId());
        auditor.success(ACTION_PASSWORD_RESET, actor.username(), "ADMIN", u.getAdminId(), ip);
        return Map.of("adminId", u.getAdminId(), "temporaryPassword", temp, "warning", "임시 비밀번호는 지금만 표시됩니다.");
    }

    @Transactional
    public AdminView unlock(String adminId, AdminPrincipal actor, String ip) {
        AdminUserEntity u = find(adminId);
        if (u.getStatus() == AdminStatus.LOCKED) u.setStatus(AdminStatus.ACTIVE);
        u.setFailedAttempts((short) 0);
        u.setLockedUntil(null);
        u.setUpdatedAt(Instant.now());
        users.save(u);
        auditor.success(ACTION_UNLOCKED, actor.username(), "ADMIN", u.getAdminId(), ip);
        return AdminView.of(u);
    }

    @Transactional
    public AdminView resetMfa(String adminId, AdminPrincipal actor, String ip) {
        AdminUserEntity u = find(adminId);
        u.setTotpSecretEnc(null);
        u.setTotpEnrolled(false);
        u.setUpdatedAt(Instant.now());
        users.save(u);
        sessions.deleteAllOf(u.getAdminId());
        auditor.success(ACTION_MFA_RESET, actor.username(), "ADMIN", u.getAdminId(), ip);
        return AdminView.of(u);
    }

    private AdminUserEntity find(String adminId) {
        return users.findById(adminId).orElseThrow(() -> new PlatformException(PlatformErrorCode.ADMIN_NOT_FOUND, null, adminId));
    }

    static String temporaryPassword() {
        // 정책(길이 10+, 3종류)을 항상 만족: 무작위 토큰 + 고정 접두
        return "Tmp-" + CryptoProviders.current().randomToken(12) + "9";
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}

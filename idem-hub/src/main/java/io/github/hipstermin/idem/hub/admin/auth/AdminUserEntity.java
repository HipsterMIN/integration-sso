package io.github.hipstermin.idem.hub.admin.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** {@code idem_hub.admin_user} (V25). */
@Entity
@Table(name = "admin_user")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserEntity {

    @Id
    @Column(name = "admin_id", length = 36, nullable = false)
    private String adminId;

    @Column(name = "username", length = 64, nullable = false, unique = true)
    private String username;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "password_hash", length = 400, nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 30, nullable = false)
    private AdminRole role;

    @Column(name = "tenant_code", length = 50)
    private String tenantCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private AdminStatus status = AdminStatus.ACTIVE;

    @Column(name = "totp_secret_enc", length = 400)
    private String totpSecretEnc;

    @Column(name = "totp_enrolled", nullable = false)
    @Builder.Default
    private boolean totpEnrolled = false;

    @Column(name = "must_change_password", nullable = false)
    @Builder.Default
    private boolean mustChangePassword = true;

    @Column(name = "failed_attempts", nullable = false)
    @Builder.Default
    private short failedAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "password_changed_at", nullable = false)
    @Builder.Default
    private Instant passwordChangedAt = Instant.now();

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "last_login_ip", length = 45)
    private String lastLoginIp;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /** 잠금 상태이되 잠금 시한이 지났으면 풀린 것으로 본다 */
    public boolean isEffectivelyLocked(Instant now) {
        if (status != AdminStatus.LOCKED) return false;
        return lockedUntil == null || lockedUntil.isAfter(now);
    }
}

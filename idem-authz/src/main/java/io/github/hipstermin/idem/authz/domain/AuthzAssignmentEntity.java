package io.github.hipstermin.idem.authz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 사용자 ↔ Service 할당 (S8-b, authz.authz_assignment).
 *
 * <p>역할({@link AuthzUserRoleEntity})은 "무엇을 할 수 있나", 할당은 "이 Service 의 사용자인가" 다.
 * hub 의 ASSIGNMENT 정책 규칙과 Handoff/CAST 발급이 이 표를 정본으로 읽는다.
 */
@Entity
@Table(name = "authz_assignment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthzAssignmentEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "qim_user_id", nullable = false, length = 36)
    private String qimUserId;

    @Column(name = "agency_code", nullable = false, length = 50)
    private String agencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AssignmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 24)
    private AssignmentSource source;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "granted_by", nullable = false, length = 128)
    private String grantedBy;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "reason")
    private String reason;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by", length = 128)
    private String revokedBy;

    public boolean isEffectiveAt(Instant now) {
        return status == AssignmentStatus.ACTIVE && (expiresAt == null || expiresAt.isAfter(now));
    }
}

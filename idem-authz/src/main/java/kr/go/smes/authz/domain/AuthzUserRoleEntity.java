package kr.go.smes.authz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * 사용자 역할 부여(assignment) 엔티티 — 연합 인가의 중앙 SoR.
 *
 * <p>이 레코드의 ACTIVE 집합이 곧 토큰 {@code roles[]} 클레임의 원천이다.
 * 참조 무결성(역할 존재)은 DB FK + 서비스 검증으로 이중 보장한다.
 */
@Entity
@Table(name = "authz_user_role")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthzUserRoleEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "qim_user_id", nullable = false, length = 36)
    private String qimUserId;

    @Column(name = "agency_code", nullable = false, length = 50)
    private String agencyCode;

    @Column(name = "role_code", nullable = false, length = 64)
    private String roleCode;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "granted_by", nullable = false, length = 128)
    private String grantedBy;

    /** JIT/한시 권한 만료 시각. NULL = 무기한. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AssignmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 24)
    private GrantSource source;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by", length = 128)
    private String revokedBy;

    /** 만료 여부(상태가 ACTIVE라도 expires_at 경과면 유효하지 않음). */
    public boolean isEffectiveAt(Instant now) {
        return status == AssignmentStatus.ACTIVE
                && (expiresAt == null || expiresAt.isAfter(now));
    }
}

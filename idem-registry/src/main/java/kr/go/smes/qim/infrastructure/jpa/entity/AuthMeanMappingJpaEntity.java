package kr.go.smes.qim.infrastructure.jpa.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 인증수단 매핑 JPA 엔터티 — MariaDB qim.auth_mean_mapping 테이블 매핑
 * 설계서 §10.3 identifierHash → qimUserId 단방향 매핑 SoR
 *
 * [DB] NHN Cloud RDS for MariaDB (PoC: Docker MariaDB 11.x)
 */
@Entity
@Table(name = "auth_mean_mapping")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthMeanMappingJpaEntity {

    @Id
    @Column(name = "mapping_id", length = 36, nullable = false)
    private String mappingId;

    /** 외래키 — qim_user */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "qim_user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_mapping_qim_user"))
    private QimUserJpaEntity user;

    @Column(name = "provider_code", length = 50, nullable = false)
    private String providerCode;

    /** SHA-256(CI / idToken.sub) — PII 비노출 */
    @Column(name = "identifier_hash", length = 300, nullable = false, unique = true)
    private String identifierHash;

    /** ACTIVE / REVOKED */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason", length = 200)
    private String revokeReason;

    @PrePersist
    protected void onCreate() {
        if (linkedAt == null) linkedAt = Instant.now();
        if (status == null) status = "ACTIVE";
    }
}

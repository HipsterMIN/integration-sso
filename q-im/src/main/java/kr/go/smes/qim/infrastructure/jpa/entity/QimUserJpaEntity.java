package kr.go.smes.qim.infrastructure.jpa.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Q-IM 사용자 JPA 엔터티 — MariaDB qim.qim_user 테이블 매핑
 * 설계서 §10.2 사용자 오브젝트 SoR
 *
 * [DB] NHN Cloud RDS for MariaDB (PoC: Docker MariaDB 11.x)
 *      스키마 없음 — qim DB 자체가 단일 스키마
 */
@Entity
@Table(name = "qim_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QimUserJpaEntity {

    @Id
    @Column(name = "qim_user_id", length = 36, nullable = false)
    private String qimUserId;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "withdrawal_reason", length = 200)
    private String withdrawalReason;

    @Column(name = "event_version", nullable = false)
    private Long eventVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    /** 인증수단 매핑 목록 (1:N) */
    @OneToMany(
        mappedBy = "user",
        cascade = CascadeType.ALL,
        orphanRemoval = true,
        fetch = FetchType.LAZY
    )
    @Builder.Default
    private List<AuthMeanMappingJpaEntity> authMeanMappings = new ArrayList<>();

    /** 사용자 프로필 (1:1) */
    @OneToOne(
        mappedBy = "user",
        cascade = CascadeType.ALL,
        orphanRemoval = true,
        fetch = FetchType.LAZY
    )
    private UserProfileJpaEntity profile;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (eventVersion == null) eventVersion = 1L;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}

package io.github.hipstermin.idem.registry.infrastructure.jpa.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.*;
import lombok.AccessLevel;
import org.springframework.data.domain.Persistable;

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
public class QimUserJpaEntity implements Persistable<String> {

    @Id
    @Column(name = "qim_user_id", length = 36, nullable = false)
    private String qimUserId;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    /** 소속 Tenant(Realm) — V9 (S4b). 사용자는 Tenant 에 속하고 Service(기관)에 속하지 않는다. 기본 DEFAULT */
    @Column(name = "tenant_code", length = 50, nullable = false)
    @Builder.Default
    private String tenantCode = "DEFAULT";

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

    /**
     * 예약 탈퇴 예정 일시 (SCHEDULED 탈퇴 전용)
     * null이면 즉시 탈퇴 또는 예약 없음.
     */
    @Column(name = "withdrawal_scheduled_at")
    private Instant withdrawalScheduledAt;

    /**
     * 탈퇴 유형 (IMMEDIATE/SCHEDULED/AGENCY_REQUESTED/ADMIN_FORCED)
     * null이면 탈퇴하지 않은 상태.
     */
    @Column(name = "withdrawal_type", length = 30)
    private String withdrawalType;

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

    // ── Spring Data 신규 판정 (Hibernate 6.6 @MapsId merge 회귀 대응) ──────────
    /**
     * 할당 ID 엔터티는 Spring Data {@code save()} 가 {@code merge} 로 가는데, Hibernate 6.6 은
     * DB 에 행이 없는 {@code @MapsId} 자식의 merge 에 {@code StaleObjectStateException} 을 던진다
     * ({@code MapsIdPersistRegressionTest}). {@link Persistable#isNew()} 가 true 이면 {@code save()} 가
     * {@code persist} 를 호출하므로 신규 저장이 INSERT 로 간다. 로드·저장 후에는 false 로 바뀐다.
     */
    @Transient
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean isNew = true;

    @Override
    public String getId() { return qimUserId; }

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { isNew = false; }
}

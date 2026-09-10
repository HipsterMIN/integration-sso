package io.github.hipstermin.idem.hub.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Tenant(Realm) — 운영기관·사용자 디렉터리 단위 (S4b, {@code docs/generalization-plan.md} §2.0).
 *
 * <p>Service({@code agency_meta})는 Tenant 에 속하고, 사용자(registry {@code qim_user.tenant_code})도 Tenant 에 속한다.
 * 설치본에는 V22 가 시드한 {@code DEFAULT} 가 항상 있다. 다중 Tenant 격리(관리자·사용자 분리)는 S7.
 */
@Entity
@Table(name = "tenant", schema = "ido")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantJpaEntity {

    public static final String DEFAULT_CODE = "DEFAULT";

    @Id
    @Column(name = "tenant_code", length = 50, nullable = false)
    private String tenantCode;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    /** ACTIVE / INACTIVE */
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}

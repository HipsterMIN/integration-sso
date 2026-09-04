package kr.go.smes.qsign.infrastructure.jpa.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Q-Sign auth_lock 테이블 JPA 엔티티
 * 설계서 §9.6 — 인증 수단별 잠금·재시도 카운터 SoR
 */
@Entity
@Table(name = "auth_lock", schema = "qsign")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthLockJpaEntity {

    /** {identifierHash}:{providerCode} 복합 키 */
    @Id
    @Column(name = "lock_key", length = 400, nullable = false)
    private String lockKey;

    @Column(name = "attempt_count", nullable = false)
    private short attemptCount;   // DB: SMALLINT

    @Column(name = "max_attempts", nullable = false)
    private short maxAttempts;    // DB: SMALLINT, default 5

    @Column(name = "locked", nullable = false)
    private boolean locked;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "unlock_at")
    private Instant unlockAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_fail_reason", length = 100)
    private String lastFailReason;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        if (maxAttempts == 0) maxAttempts = 5;
    }
}

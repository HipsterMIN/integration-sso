package io.github.hipstermin.idem.gate.infrastructure.jpa.repository;

import io.github.hipstermin.idem.gate.infrastructure.jpa.entity.AuthLockJpaEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Q-Sign AuthLock Spring Data JPA Repository
 * 설계서 §9.6 — 잠금·재시도 카운터
 */
public interface AuthLockJpaRepository extends JpaRepository<AuthLockJpaEntity, String> {

    @Modifying
    @Query("""
            UPDATE AuthLockJpaEntity l
            SET l.attemptCount = l.attemptCount + 1,
                l.lastAttemptAt = :now,
                l.updatedAt = :now
            WHERE l.lockKey = :lockKey
            """)
    int incrementAttempt(@Param("lockKey") String lockKey, @Param("now") Instant now);

    @Modifying
    @Query("""
            UPDATE AuthLockJpaEntity l
            SET l.locked = true,
                l.lockedAt = :now,
                l.updatedAt = :now
            WHERE l.lockKey = :lockKey
            """)
    int lock(@Param("lockKey") String lockKey, @Param("now") Instant now);

    @Modifying
    @Query("""
            UPDATE AuthLockJpaEntity l
            SET l.locked = false,
                l.unlockAt = :now,
                l.updatedAt = :now
            WHERE l.lockKey = :lockKey
            """)
    int unlock(@Param("lockKey") String lockKey, @Param("now") Instant now);
}

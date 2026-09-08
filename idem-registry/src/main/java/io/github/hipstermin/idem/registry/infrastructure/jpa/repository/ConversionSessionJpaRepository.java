package io.github.hipstermin.idem.registry.infrastructure.jpa.repository;

import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.ConversionSessionJpaEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * ConversionSession Spring Data JPA Repository
 */
public interface ConversionSessionJpaRepository
        extends JpaRepository<ConversionSessionJpaEntity, String> {

    /** 사용자의 활성 전환 세션 조회 (INITIATED/MEMBERS_FETCHED/ACCOUNT_SELECTED/LINKING) */
    @Query("""
        SELECT s FROM ConversionSessionJpaEntity s
        WHERE s.qimUserId = :qimUserId
          AND s.status NOT IN ('COMPLETED', 'CANCELLED', 'EXPIRED')
          AND s.expiresAt > :now
        ORDER BY s.createdAt DESC
        LIMIT 1
        """)
    Optional<ConversionSessionJpaEntity> findActiveByUser(
            @Param("qimUserId") String qimUserId,
            @Param("now")       Instant now);

    /**
     * TTL 만료된 활성 세션 조회 (스케줄러용)
     */
    @Query("""
        SELECT s FROM ConversionSessionJpaEntity s
        WHERE s.status NOT IN ('COMPLETED', 'CANCELLED', 'EXPIRED')
          AND s.expiresAt <= :now
        """)
    List<ConversionSessionJpaEntity> findExpired(@Param("now") Instant now);

    /**
     * 만료된 세션 일괄 EXPIRED 처리
     */
    @Modifying
    @Query("""
        UPDATE ConversionSessionJpaEntity s
        SET s.status     = 'EXPIRED',
            s.cancelReason = 'TTL_EXPIRED',
            s.updatedAt  = :now
        WHERE s.status NOT IN ('COMPLETED', 'CANCELLED', 'EXPIRED')
          AND s.expiresAt <= :now
        """)
    int markExpiredBatch(@Param("now") Instant now);
}

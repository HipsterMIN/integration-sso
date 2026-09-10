package io.github.hipstermin.idem.registry.infrastructure.jpa.repository;

import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.ConsentRecordJpaEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 개인정보 동의 기록 Spring Data JPA Repository
 */
public interface ConsentRecordJpaRepository
        extends JpaRepository<ConsentRecordJpaEntity, String> {

    /**
     * 특정 사용자의 특정 유형 최신 동의 기록 조회 (agreed_at DESC)
     */
    @Query("""
        SELECT r FROM ConsentRecordJpaEntity r
        WHERE r.qimUserId    = :qimUserId
          AND r.consentType  = :consentType
        ORDER BY r.agreedAt DESC
        LIMIT 1
        """)
    Optional<ConsentRecordJpaEntity> findLatestByUserAndType(
            @Param("qimUserId")   String qimUserId,
            @Param("consentType") String consentType);

    /**
     * 특정 사용자의 모든 동의 기록 조회 (동의 내역 조회용)
     */
    @Query("""
        SELECT r FROM ConsentRecordJpaEntity r
        WHERE r.qimUserId = :qimUserId
        ORDER BY r.agreedAt DESC
        """)
    List<ConsentRecordJpaEntity> findAllByUser(@Param("qimUserId") String qimUserId);

    /**
     * 특정 사용자의 현재 유효한 AGREED 동의 기록 목록
     * (동의 확인, 약관 변경 필요 여부 판단용)
     */
    @Query("""
        SELECT r FROM ConsentRecordJpaEntity r
        WHERE r.qimUserId     = :qimUserId
          AND r.consentStatus = 'AGREED'
        ORDER BY r.consentType ASC
        """)
    List<ConsentRecordJpaEntity> findActiveConsentsByUser(@Param("qimUserId") String qimUserId);

    /**
     * 동의 철회 처리 — consentStatus=WITHDRAWN, withdrawnAt 설정
     */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE ConsentRecordJpaEntity r
        SET r.consentStatus   = 'WITHDRAWN',
            r.withdrawnAt     = :withdrawnAt,
            r.withdrawalReason = :reason
        WHERE r.recordId = :recordId
        """)
    int markWithdrawn(@Param("recordId")   String recordId,
                      @Param("withdrawnAt") Instant withdrawnAt,
                      @Param("reason")      String reason);
}

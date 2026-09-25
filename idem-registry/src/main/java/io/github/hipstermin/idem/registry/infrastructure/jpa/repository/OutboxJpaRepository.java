package io.github.hipstermin.idem.registry.infrastructure.jpa.repository;

import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.OutboxJpaEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Transactional Outbox Spring Data JPA Repository
 * 설계서 §10.5.2
 *
 * [DB] NHN Cloud RDS for MariaDB (PoC: Docker MariaDB 11.x)
 *      idx_qim_outbox_status (status, created_at) 인덱스 활용
 */
public interface OutboxJpaRepository extends JpaRepository<OutboxJpaEntity, String> {

    /**
     * PENDING 상태 레코드 조회 (Relay 배치용)
     * ORDER BY created_at ASC → 선입선출 보장
     */
    @Query(value = """
        SELECT * FROM outbox
        WHERE status = 'PENDING'
        ORDER BY created_at ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<OutboxJpaEntity> findPendingNative(@Param("limit") int limit);

    /**
     * D3: 내부 이벤트 피드용 키셋 조회 — {@code (created_at, event_id)} 가 커서보다 뒤인 레코드를 오름차순으로.
     * 상태를 보지 않는다(PENDING/PUBLISHED 모두) — 읽기 전용 소비자(hub 폴링)는 아웃박스 상태에 관여하지 않는다.
     */
    @Query("""
        SELECT o FROM OutboxJpaEntity o
        WHERE o.topic = :topic
          AND (o.createdAt > :afterCreatedAt OR (o.createdAt = :afterCreatedAt AND o.eventId > :afterEventId))
        ORDER BY o.createdAt ASC, o.eventId ASC
        """)
    List<OutboxJpaEntity> findAfter(@Param("topic") String topic,
                                    @Param("afterCreatedAt") Instant afterCreatedAt,
                                    @Param("afterEventId") String afterEventId,
                                    org.springframework.data.domain.Pageable pageable);

    /**
     * 발행 완료 처리 — status = PUBLISHED, published_at = now()
     */
    @Modifying
    @Query("""
        UPDATE OutboxJpaEntity o
        SET o.status = 'PUBLISHED', o.publishedAt = :now
        WHERE o.eventId = :eventId
        """)
    void markPublished(@Param("eventId") String eventId, @Param("now") Instant now);

    /**
     * 발행 실패 처리 — status = FAILED, retryCount + 1, errorMessage 저장
     */
    @Modifying
    @Query("""
        UPDATE OutboxJpaEntity o
        SET o.status = 'FAILED',
            o.retryCount = o.retryCount + 1,
            o.errorMessage = :errorMessage
        WHERE o.eventId = :eventId
        """)
    void markFailed(@Param("eventId") String eventId,
                    @Param("errorMessage") String errorMessage);

    /**
     * FAILED 상태 중 retryCount < maxRetry 인 레코드 재시도 대상 조회
     */
    @Query("""
        SELECT o FROM OutboxJpaEntity o
        WHERE o.status = 'FAILED'
          AND o.retryCount < :maxRetry
        ORDER BY o.createdAt ASC
        """)
    List<OutboxJpaEntity> findRetryable(@Param("maxRetry") short maxRetry);

    /**
     * GAP-QIM-04: FAILED → PENDING 상태 복구 (재시도 큐 복귀)
     * retryCount 는 유지 (markFailed 에서 이미 증가했으므로)
     */
    @Modifying
    @Query("""
        UPDATE OutboxJpaEntity o
        SET o.status = 'PENDING',
            o.errorMessage = NULL
        WHERE o.eventId = :eventId
        """)
    void markPending(@Param("eventId") String eventId);
}

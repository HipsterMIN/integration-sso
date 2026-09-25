package io.github.hipstermin.idem.gate.outbox;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Q-Sign Outbox Repository
 * 설계서 §9.3 Transactional Outbox 패턴
 */
public interface QSignOutboxRepository extends JpaRepository<QSignOutboxRecord, String> {

    /** PENDING 상태 레코드를 생성 순서대로 배치 조회 */
    @Query(value = """
            SELECT * FROM idem_gate.outbox
            WHERE status = 'PENDING'
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<QSignOutboxRecord> findPendingBatch(@Param("limit") int limit);

    @Modifying
    @Query("""
            UPDATE QSignOutboxRecord o
            SET o.status = 'PUBLISHED', o.publishedAt = :now
            WHERE o.eventId = :eventId
            """)
    void markPublished(@Param("eventId") String eventId,
                       @Param("now") Instant now);

    default void markPublished(String eventId) {
        markPublished(eventId, Instant.now());
    }

    @Modifying
    @Query("""
            UPDATE QSignOutboxRecord o
            SET o.status = 'FAILED', o.errorMessage = :msg
            WHERE o.eventId = :eventId
            """)
    void markFailed(@Param("eventId") String eventId,
                    @Param("msg") String errorMessage);

    @Modifying
    @Query("""
            UPDATE QSignOutboxRecord o
            SET o.retryCount = o.retryCount + 1, o.errorMessage = :msg
            WHERE o.eventId = :eventId
            """)
    void incrementRetry(@Param("eventId") String eventId,
                        @Param("msg") String errorMessage);
}

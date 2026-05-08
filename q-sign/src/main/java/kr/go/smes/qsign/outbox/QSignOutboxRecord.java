package kr.go.smes.qsign.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Q-Sign Transactional Outbox 레코드 엔티티
 * 설계서 §9.3 — DB 트랜잭션과 Kafka 발행 원자성 보장
 */
@Entity
@Table(name = "outbox", schema = "qsign")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QSignOutboxRecord {

    @Id
    @Column(name = "event_id", length = 36, nullable = false, updatable = false)
    private String eventId;

    @Column(name = "event_type", length = 80, nullable = false, updatable = false)
    private String eventType;

    /** Kafka 파티션 키 = identifierHash */
    @Column(name = "partition_key", length = 300, nullable = false, updatable = false)
    private String partitionKey;

    /** authResultId */
    @Column(name = "aggregate_id", length = 36, nullable = false, updatable = false)
    private String aggregateId;

    @Column(name = "event_version", nullable = false, updatable = false)
    private Long eventVersion;

    @Column(name = "payload", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String payload;

    @Column(name = "topic", length = 200, nullable = false, updatable = false)
    private String topic;

    @Column(name = "status", length = 20, nullable = false)
    private String status;   // PENDING / PUBLISHED / FAILED

    @Column(name = "retry_count", nullable = false)
    private short retryCount;  // DB: SMALLINT (V1 migration 기준)

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Builder
    public QSignOutboxRecord(String eventId, String eventType, String partitionKey,
                              String aggregateId, Long eventVersion,
                              String payload, String topic) {
        this.eventId      = eventId;
        this.eventType    = eventType;
        this.partitionKey = partitionKey;
        this.aggregateId  = aggregateId;
        this.eventVersion = eventVersion;
        this.payload      = payload;
        this.topic        = topic;
        this.status       = "PENDING";
        this.retryCount   = 0;
        this.createdAt    = Instant.now();
    }

    public void markPublished() {
        this.status      = "PUBLISHED";
        this.publishedAt = Instant.now();
    }

    public void markFailed(String errorMessage) {
        this.status       = "FAILED";
        this.errorMessage = errorMessage;
    }

    public void incrementRetry(String errorMessage) {
        this.retryCount++;
        this.errorMessage = errorMessage;
    }
}

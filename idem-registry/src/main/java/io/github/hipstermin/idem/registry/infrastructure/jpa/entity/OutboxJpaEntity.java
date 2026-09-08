package io.github.hipstermin.idem.registry.infrastructure.jpa.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/**
 * Transactional Outbox JPA 엔터티 — MariaDB qim.outbox 테이블 매핑
 * 설계서 §10.5.2 Transactional Outbox 패턴
 *
 * [DB] NHN Cloud RDS for MariaDB (PoC: Docker MariaDB 11.x)
 *      payload → JSON 컬럼 (MariaDB 10.2+ 지원)
 *      status 인덱스: idx_qim_outbox_status (status, created_at)
 */
@Entity
@Table(name = "outbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxJpaEntity {

    @Id
    @Column(name = "event_id", length = 36, nullable = false)
    private String eventId;

    @Column(name = "event_type", length = 80, nullable = false)
    private String eventType;

    /** Kafka 파티션 키 = qimUserId */
    @Column(name = "partition_key", length = 36, nullable = false)
    private String partitionKey;

    /** qimUserId */
    @Column(name = "aggregate_id", length = 36, nullable = false)
    private String aggregateId;

    /** qim_user.event_version 과 동기 */
    @Column(name = "event_version", nullable = false)
    private Long eventVersion;

    /** 직렬화된 이벤트 페이로드 (JSON) */
    @Column(name = "payload", columnDefinition = "JSON", nullable = false)
    private String payload;

    /** qim.user.events 또는 qim.user.snapshot */
    @Column(name = "topic", length = 200, nullable = false)
    private String topic;

    /** PENDING / PUBLISHED / FAILED */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Short retryCount = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = "PENDING";
        if (retryCount == null) retryCount = 0;
        if (topic == null) topic = "qim.user.events";
    }
}

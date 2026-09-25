package io.github.hipstermin.idem.authz.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 인가 이벤트 트랜잭셔널 아웃박스 엔터티 — {@code idem_authz.authz_outbox} 매핑.
 *
 * <p>비즈니스 트랜잭션과 동일 커밋에 적재되어 발행 원자성을 보장한다.
 * 실제 Kafka 발행은 {@code outbox-relay-batch}가 담당하므로 q-authz는
 * Kafka 클라이언트 의존성을 갖지 않는다.
 */
@Entity
@Table(name = "authz_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthzOutboxEntity {

    @Id
    @Column(name = "event_id", length = 36, nullable = false, updatable = false)
    private String eventId;

    @Column(name = "event_type", length = 80, nullable = false, updatable = false)
    private String eventType;

    /** Kafka 파티션 키 = qimUserId */
    @Column(name = "partition_key", length = 64, nullable = false, updatable = false)
    private String partitionKey;

    /** agency_code:role_code */
    @Column(name = "aggregate_id", length = 128, nullable = false, updatable = false)
    private String aggregateId;

    @Column(name = "event_version", updatable = false)
    private Long eventVersion;

    /** 직렬화된 이벤트(JSON 문자열) — 컬럼 타입 text */
    @Column(name = "payload", columnDefinition = "text", nullable = false, updatable = false)
    private String payload;

    @Column(name = "topic", length = 200, nullable = false, updatable = false)
    private String topic;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "retry_count", nullable = false)
    private short retryCount;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Builder
    public AuthzOutboxEntity(String eventId, String eventType, String partitionKey,
                             String aggregateId, Long eventVersion, String payload, String topic) {
        this.eventId      = eventId;
        this.eventType    = eventType;
        this.partitionKey = partitionKey;
        this.aggregateId  = aggregateId;
        this.eventVersion = eventVersion;
        this.payload      = payload;
        this.topic        = topic != null ? topic : "idem.authz.assignment.events";
        this.status       = "PENDING";
        this.retryCount   = 0;
        this.createdAt    = Instant.now();
    }
}

package kr.go.smes.qim.outbox;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * Transactional Outbox 레코드
 * 설계서 10.5.2절 참조
 */
@Getter
@Builder
public class OutboxRecord {

    private final String eventId;
    private final String eventType;

    /** Kafka 파티션 키 = qimUserId (사용자 단위 순서 보장) */
    private final String partitionKey;

    /** 단조 증가 버전 (optimistic-lock) */
    private final Long eventVersion;

    /** 직렬화된 이벤트 페이로드 (JSON) */
    private final String payload;

    private final OutboxStatus status;
    private final Instant createdAt;
    private final Instant publishedAt;

    public enum OutboxStatus {
        PENDING,
        PUBLISHED,
        FAILED
    }
}

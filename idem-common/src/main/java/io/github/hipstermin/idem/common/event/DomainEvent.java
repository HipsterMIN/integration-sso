package io.github.hipstermin.idem.common.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.hipstermin.idem.common.util.UuidV7;
import java.time.Instant;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

/**
 * 플랫폼 공통 도메인 이벤트 베이스
 * 설계서 10.5 / 14.11 / 16.3절 참조
 * - partitionKey = qimUserId (동일 사용자 이벤트 순서 보장)
 * - eventVersion  = 단조 증가 수열 (optimistic-lock 기반 중복·역전 방지)
 */
@Getter
@SuperBuilder
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class DomainEvent {

    /** 이벤트 고유 ID (멱등성 키) */
    private final String eventId;

    /** 이벤트 유형 (USER_UPDATED / AUTH_COMPLETED / HANDOFF_ISSUED 등) */
    private final String eventType;

    /** 이벤트 발행 시스템 */
    private final String sourceSystem;

    /** 전체 흐름 추적 키 */
    private final String correlationId;

    /** Q-IM 사용자 ID (Kafka partitionKey) */
    private final String qimUserId;

    /** 사용자 단위 단조 증가 버전 (optimistic-lock) */
    private final Long eventVersion;

    /** 이벤트 발생 시각 */
    private final Instant occurredAt;

    protected DomainEvent(String eventType, String sourceSystem, String correlationId,
                          String qimUserId, Long eventVersion) {
        this.eventId       = UuidV7.generate();  // UUID v7: 시간 정렬 가능 (DB 인덱스 효율)
        this.eventType     = eventType;
        this.sourceSystem  = sourceSystem;
        this.correlationId = correlationId;
        this.qimUserId     = qimUserId;
        this.eventVersion  = eventVersion;
        this.occurredAt    = Instant.now();
    }
}

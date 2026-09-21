package io.github.hipstermin.idem.common.event;

import lombok.Getter;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

/**
 * Q-IM 사용자 변경 이벤트
 * 설계서 10.5절 / Transactional Outbox 패턴
 * Kafka Topic: qim.user.events (cleanup.policy=compact, partitionKey=qimUserId)
 */
@Getter
@SuperBuilder
@Jacksonized // D1-b: Kafka JsonDeserializer·아웃박스 프로세스 내 배달 모두 이 클래스로 역직렬화한다 (생성자만으로는 Jackson 이 만들 수 없었다)
public class UserEvent extends DomainEvent {

    public static final String TYPE_UPDATED   = "USER_UPDATED";
    public static final String TYPE_SUSPENDED = "USER_SUSPENDED";
    public static final String TYPE_WITHDRAWN = "USER_WITHDRAWN";
    public static final String TYPE_MERGED    = "USER_MERGED";

    /** 변경된 사용자 상태 */
    private final String userStatus;

    /** 변경 원인 (설명용) */
    private final String changeReason;

    /** needsSync=true: 기관이 재동기화 필요 (Selective Pull 신호) */
    private final boolean needsSync;

    /** 병합 대상 qimUserId (MERGED 이벤트 시) */
    private final String mergedIntoQimUserId;

    public UserEvent(String eventType, String sourceSystem, String correlationId,
                     String qimUserId, Long eventVersion,
                     String userStatus, String changeReason, boolean needsSync) {
        super(eventType, sourceSystem, correlationId, qimUserId, eventVersion);
        this.userStatus           = userStatus;
        this.changeReason         = changeReason;
        this.needsSync            = needsSync;
        this.mergedIntoQimUserId  = null;
    }
}

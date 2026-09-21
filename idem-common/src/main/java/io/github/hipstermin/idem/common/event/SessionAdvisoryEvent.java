package io.github.hipstermin.idem.common.event;

import lombok.Getter;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

/**
 * 세션 종료 권고 이벤트 (Advisory — 강제 종료 아님)
 * 설계서 14.11절 참조
 * - 세션 오너십은 채널별 분리: 수신측이 종료 여부를 단독 결정
 * - Mandatory 등급(보안 강제)과 Advisory 등급 구분
 * Kafka Topic: platform.session.advisory
 */
@Getter
@SuperBuilder
@Jacksonized // D1-b: Kafka JsonDeserializer·아웃박스 프로세스 내 배달 모두 이 클래스로 역직렬화한다 (생성자만으로는 Jackson 이 만들 수 없었다)
public class SessionAdvisoryEvent extends DomainEvent {

    public static final String TYPE_SESSION_LOGOUT_HINT    = "SESSION_LOGOUT_HINT";
    public static final String TYPE_MANDATORY_SECURITY     = "MANDATORY_SECURITY_TERMINATE";

    /** Advisory / Mandatory */
    private final String severity;

    /** 대상 기관 코드 (null이면 전체) */
    private final String agencyCode;

    /** 이벤트 발생 이유 */
    private final String reason;

    /** 관련 authResultId (Mandatory 시 무효화 대상) */
    private final String authResultId;

    public SessionAdvisoryEvent(String eventType, String sourceSystem, String correlationId,
                                String qimUserId, Long eventVersion,
                                String severity, String agencyCode,
                                String reason, String authResultId) {
        super(eventType, sourceSystem, correlationId, qimUserId, eventVersion);
        this.severity     = severity;
        this.agencyCode   = agencyCode;
        this.reason       = reason;
        this.authResultId = authResultId;
    }
}

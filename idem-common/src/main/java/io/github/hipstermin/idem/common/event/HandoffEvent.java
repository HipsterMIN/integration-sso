package io.github.hipstermin.idem.common.event;

import lombok.Getter;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

/**
 * IdO Handoff 이벤트 (Issue / Verify / Revoke)
 * 설계서 16.3 / 14.11절 참조
 * Kafka Topic: idem.hub.handoff.events
 */
@Getter
@SuperBuilder
@Jacksonized // D1-b: Kafka JsonDeserializer·아웃박스 프로세스 내 배달 모두 이 클래스로 역직렬화한다 (생성자만으로는 Jackson 이 만들 수 없었다)
public class HandoffEvent extends DomainEvent {

    public static final String TYPE_HANDOFF_ISSUED   = "HANDOFF_ISSUED";
    public static final String TYPE_HANDOFF_CONSUMED = "HANDOFF_CONSUMED";
    public static final String TYPE_HANDOFF_EXPIRED  = "HANDOFF_EXPIRED";
    public static final String TYPE_HANDOFF_REVOKED  = "HANDOFF_REVOKED";
    public static final String TYPE_REUSE_ATTEMPT    = "REUSE_ATTEMPT";
    /** Sprint α-2 / F4.1 — Handoff verify 시 HMAC 서명 검증 실패 (변조 의심 / Redis 침해 시그널) */
    public static final String TYPE_SIGNATURE_INVALID = "SIGNATURE_INVALID";

    private final String ticketId;
    private final String agencyCode;
    private final String authResultId;
    private final String ticketState;

    /** REVOKED 사유: compromise / qim_suspend / policy_rollback / incident_containment */
    private final String revokeReason;

    public HandoffEvent(String eventType, String sourceSystem, String correlationId,
                        String qimUserId, Long eventVersion,
                        String ticketId, String agencyCode,
                        String authResultId, String ticketState, String revokeReason) {
        super(eventType, sourceSystem, correlationId, qimUserId, eventVersion);
        this.ticketId     = ticketId;
        this.agencyCode   = agencyCode;
        this.authResultId = authResultId;
        this.ticketState  = ticketState;
        this.revokeReason = revokeReason;
    }
}

package kr.go.smes.common.event;

import lombok.Getter;
import lombok.experimental.SuperBuilder;

/**
 * IdO Handoff 이벤트 (Issue / Verify / Revoke)
 * 설계서 16.3 / 14.11절 참조
 * Kafka Topic: ido.handoff.events
 */
@Getter
@SuperBuilder
public class HandoffEvent extends DomainEvent {

    public static final String TYPE_HANDOFF_ISSUED   = "HANDOFF_ISSUED";
    public static final String TYPE_HANDOFF_CONSUMED = "HANDOFF_CONSUMED";
    public static final String TYPE_HANDOFF_EXPIRED  = "HANDOFF_EXPIRED";
    public static final String TYPE_HANDOFF_REVOKED  = "HANDOFF_REVOKED";
    public static final String TYPE_REUSE_ATTEMPT    = "REUSE_ATTEMPT";

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

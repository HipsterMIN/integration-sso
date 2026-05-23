package kr.go.smes.support.api.dto;

import java.time.Instant;
import java.util.UUID;

public record CsTicketSummaryResponse(
    UUID id,
    String channel,
    String status,
    String priority,
    String category,
    String tenantId,
    String agencyId,
    String title,
    String requesterName,
    String assignedAgentId,
    boolean callbackRequired,
    Instant callbackDueAt,
    Instant updatedAt
) {
}

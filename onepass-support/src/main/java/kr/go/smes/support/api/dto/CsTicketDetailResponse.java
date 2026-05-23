package kr.go.smes.support.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CsTicketDetailResponse(
    UUID id,
    String channel,
    String status,
    String priority,
    String category,
    String tenantId,
    String agencyId,
    String title,
    String requesterName,
    String requesterPhone,
    String requesterEmail,
    String assignedAgentId,
    UUID linkedQnaId,
    boolean callbackRequired,
    Instant callbackDueAt,
    Instant closedAt,
    Instant createdAt,
    Instant updatedAt,
    List<CsTicketEventResponse> events
) {
}

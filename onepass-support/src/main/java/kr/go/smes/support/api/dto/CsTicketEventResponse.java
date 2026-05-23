package kr.go.smes.support.api.dto;

import java.time.Instant;
import java.util.UUID;

public record CsTicketEventResponse(
    UUID id,
    String eventType,
    String visibility,
    String content,
    String actorId,
    Instant createdAt
) {
}

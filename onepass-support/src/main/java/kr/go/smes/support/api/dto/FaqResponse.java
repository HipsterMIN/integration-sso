package kr.go.smes.support.api.dto;

import java.time.Instant;
import java.util.UUID;

public record FaqResponse(
    UUID id,
    String groupCode,
    String groupName,
    String question,
    String answer,
    int sortOrder,
    Instant updatedAt
) {
}


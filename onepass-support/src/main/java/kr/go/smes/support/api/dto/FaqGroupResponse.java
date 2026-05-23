package kr.go.smes.support.api.dto;

import java.time.Instant;
import java.util.UUID;

public record FaqGroupResponse(
    UUID id,
    String groupCode,
    String groupName,
    String description,
    int sortOrder,
    Instant updatedAt
) {
}


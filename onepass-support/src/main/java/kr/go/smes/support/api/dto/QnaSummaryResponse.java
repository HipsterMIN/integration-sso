package kr.go.smes.support.api.dto;

import java.time.Instant;
import java.util.UUID;

public record QnaSummaryResponse(
    UUID id,
    String title,
    String status,
    boolean secret,
    boolean mine,
    boolean anonymous,
    Instant createdAt
) {
}


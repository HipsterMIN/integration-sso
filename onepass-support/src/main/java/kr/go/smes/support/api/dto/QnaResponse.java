package kr.go.smes.support.api.dto;

import java.time.Instant;
import java.util.UUID;

public record QnaResponse(
    UUID id,
    String title,
    String status,
    Instant createdAt,
    Instant updatedAt
) {

    public static QnaResponse placeholder(UUID id) {
        Instant now = Instant.now();
        return new QnaResponse(id, "Q&A skeleton endpoint", "OPEN", now, now);
    }

    public static QnaResponse createdPlaceholder(String title) {
        Instant now = Instant.now();
        return new QnaResponse(UUID.randomUUID(), title, "OPEN", now, now);
    }

    public static QnaResponse answeredPlaceholder(UUID id, String answer) {
        Instant now = Instant.now();
        String title = answer == null || answer.isBlank() ? "Answered Q&A" : "Answered Q&A skeleton";
        return new QnaResponse(id, title, "ANSWERED", now, now);
    }
}

package kr.go.smes.support.api.dto;

import java.time.Instant;
import java.util.UUID;

public record FaqResponse(
    UUID id,
    String category,
    String question,
    String answer,
    Instant updatedAt
) {

    public static FaqResponse placeholder(UUID id) {
        return new FaqResponse(
            id,
            "general",
            "FAQ skeleton endpoint",
            "FAQ persistence will be implemented in the next iteration.",
            Instant.now()
        );
    }
}

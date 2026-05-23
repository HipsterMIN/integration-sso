package kr.go.smes.support.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record QnaDetailResponse(
    UUID id,
    String tenantId,
    String agencyId,
    String title,
    boolean secret,
    boolean contentMasked,
    String content,
    boolean anonymous,
    String writerUserId,
    String anonymousDisplayName,
    String status,
    Instant createdAt,
    Instant updatedAt,
    List<QnaAnswerResponse> answers
) {
}


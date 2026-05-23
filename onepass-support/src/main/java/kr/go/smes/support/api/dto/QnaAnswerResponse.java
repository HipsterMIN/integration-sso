package kr.go.smes.support.api.dto;

import java.time.Instant;
import java.util.UUID;

public record QnaAnswerResponse(
    UUID answerId,
    boolean secret,
    boolean contentMasked,
    String content,
    String answeredByUserId,
    Instant answeredAt
) {
}


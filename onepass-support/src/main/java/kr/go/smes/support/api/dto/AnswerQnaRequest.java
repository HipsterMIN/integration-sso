package kr.go.smes.support.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AnswerQnaRequest(
    @NotBlank
    @Size(max = 10000)
    String content,
    boolean secret
) {
}

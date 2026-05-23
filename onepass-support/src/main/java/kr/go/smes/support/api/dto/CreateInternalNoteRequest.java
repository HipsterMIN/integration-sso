package kr.go.smes.support.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateInternalNoteRequest(
    @NotBlank
    @Size(max = 10000)
    String content
) {
}

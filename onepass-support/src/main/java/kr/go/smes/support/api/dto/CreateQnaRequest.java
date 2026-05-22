package kr.go.smes.support.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateQnaRequest(
    @NotBlank
    @Size(max = 200)
    String title,

    @NotBlank
    @Size(max = 10000)
    String content,

    @Size(max = 64)
    String tenantId,

    @Size(max = 64)
    String agencyId
) {
}

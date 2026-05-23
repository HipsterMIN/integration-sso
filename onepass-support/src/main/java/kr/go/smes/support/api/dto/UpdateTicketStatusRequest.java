package kr.go.smes.support.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateTicketStatusRequest(
    @NotBlank
    @Size(max = 30)
    String status
) {
}

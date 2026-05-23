package kr.go.smes.support.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateTicketAssignmentRequest(
    @NotBlank
    @Size(max = 64)
    String assignedAgentId
) {
}

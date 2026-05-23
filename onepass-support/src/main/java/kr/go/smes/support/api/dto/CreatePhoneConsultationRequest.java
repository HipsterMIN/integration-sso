package kr.go.smes.support.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePhoneConsultationRequest(
    @Size(max = 64)
    String tenantId,

    @Size(max = 64)
    String agencyId,

    @NotBlank
    @Size(max = 200)
    String title,

    @Size(max = 80)
    String requesterName,

    @Size(max = 120)
    String requesterPhone,

    @Email
    @Size(max = 120)
    String requesterEmail,

    @Size(max = 80)
    String category,

    @Size(max = 30)
    String priority,

    @Size(max = 20)
    String callDirection,

    Instant callStartedAt,

    Instant callEndedAt,

    boolean identityVerified,

    @NotBlank
    @Size(max = 1000)
    String summary,

    @Size(max = 10000)
    String details,

    @Size(max = 1000)
    String requestedAction,

    boolean callbackRequired,

    Instant callbackDueAt
) {
}

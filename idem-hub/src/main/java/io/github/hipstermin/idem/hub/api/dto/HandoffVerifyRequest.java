package io.github.hipstermin.idem.hub.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class HandoffVerifyRequest {
    @NotBlank private String ticketId;
}

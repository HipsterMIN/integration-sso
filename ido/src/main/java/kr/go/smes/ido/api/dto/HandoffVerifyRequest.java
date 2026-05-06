package kr.go.smes.ido.api.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;

@Getter
@NoArgsConstructor
public class HandoffVerifyRequest {
    @NotBlank private String ticketId;
}

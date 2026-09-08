package io.github.hipstermin.idem.gate.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class OidcAuthRequest {
    @NotBlank private String providerCode;
    @NotBlank private String idToken;
    @NotBlank private String requestedLevel;
}

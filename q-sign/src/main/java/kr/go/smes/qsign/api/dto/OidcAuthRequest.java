package kr.go.smes.qsign.api.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;

@Getter
@NoArgsConstructor
public class OidcAuthRequest {
    @NotBlank private String providerCode;
    @NotBlank private String idToken;
    @NotBlank private String requestedLevel;
}

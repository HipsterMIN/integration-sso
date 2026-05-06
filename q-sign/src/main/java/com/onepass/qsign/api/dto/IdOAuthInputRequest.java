package com.onepass.qsign.api.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

@Getter
@NoArgsConstructor
public class IdOAuthInputRequest {
    @NotBlank private String providerCode;
    @NotBlank private String providerTxId;
    @NotBlank private String requestedAuthLevel;
    @NotBlank private String identifierHash;
    private boolean providerVerified;
    private Map<String, Object> claims;
}

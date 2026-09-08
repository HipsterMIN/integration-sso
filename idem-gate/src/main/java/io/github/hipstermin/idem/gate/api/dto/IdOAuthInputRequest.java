package io.github.hipstermin.idem.gate.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

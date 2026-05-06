package com.onepass.ido.api.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;

@Getter
@NoArgsConstructor
public class HandoffIssueRequest {
    @NotBlank private String agencyCode;
    @NotBlank private String qimUserId;
    @NotBlank private String authResultId;
    @NotBlank private String authLevel;
    @NotBlank private String providerCode;
    private String callbackUrl;
}

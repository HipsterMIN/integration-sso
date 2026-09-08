package io.github.hipstermin.idem.hub.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 기관 조회 응답 DTO (Admin API)
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgencyResponse {
    private final String       agencyCode;
    private final String       officialName;
    private final String       minAuthLevel;
    private final String       policyVersion;
    private final String       integrationType;
    private final String       bridgeEndpoint;
    private final String       ssoDomain;
    private final List<String> callbackWhitelist;
    private final List<String> allowedAttributes;
    private final String       webhookEndpoint;
    private final Boolean      webhookEnabled;
    private final Boolean      active;
    private final Integer      dailyLookupLimit;
    private final Instant      createdAt;
    private final Instant      updatedAt;
}

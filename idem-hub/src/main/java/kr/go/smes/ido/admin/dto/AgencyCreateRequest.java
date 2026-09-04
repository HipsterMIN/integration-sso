package kr.go.smes.ido.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * 기관 등록/수정 요청 DTO (Admin API)
 */
@Getter
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgencyCreateRequest {
    private final String agencyCode;
    private final String officialName;
    private final String minAuthLevel;          // L1 / L2 / L3
    private final String policyVersion;
    private final String integrationType;       // DIRECT / APACHE_GATE / BRIDGE / INTERNAL_SSO
    private final String bridgeEndpoint;
    private final String ssoDomain;
    private final List<String> callbackWhitelist;
    private final List<String> allowedAttributes;
    private final String webhookEndpoint;
    private final Boolean webhookEnabled;
    private final Integer dailyLookupLimit;
}

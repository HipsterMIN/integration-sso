package io.github.hipstermin.idem.hub.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

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
    private final String integrationType;       // IntegrationType 이름 (DIRECT / BRIDGE / APACHE_GATE / INTERNAL_SSO) — 미지 값은 400
    private final String bridgeEndpoint;        // BRIDGE 전용
    private final String apacheGateEndpoint;    // APACHE_GATE 전용 (S1 에서 bridgeEndpoint 와 분리)
    private final String ssoDomain;
    private final List<String> callbackWhitelist;
    private final List<String> allowedAttributes;
    private final String webhookEndpoint;
    private final Boolean webhookEnabled;
    private final Integer dailyLookupLimit;
}

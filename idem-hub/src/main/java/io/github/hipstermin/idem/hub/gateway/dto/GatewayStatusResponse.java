package io.github.hipstermin.idem.hub.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import lombok.Builder;
import lombok.Value;

/**
 * 기관 연동 상태 응답 DTO
 *
 * <p>GET /api/v1/agency/gateway/status/{agencyCode} 응답 바디.
 * 운영 대시보드 및 모니터링에서 기관별 게이트웨이 상태를 조회할 때 사용.
 */
@Value
@Builder
public class GatewayStatusResponse {

    /** 기관 코드 */
    @JsonProperty("agency_code")
    String agencyCode;

    /** 기관 공식 명칭 */
    @JsonProperty("agency_name")
    String agencyName;

    /** 기관 활성 여부 */
    @JsonProperty("active")
    boolean active;

    /** 활성 엔드포인트 수 (agency_endpoint_registry is_active=true 건수) */
    @JsonProperty("active_endpoints")
    int activeEndpoints;

    /** 미처리 인바운드 이벤트 수 (gateway_inbound_audit RECEIVED 상태) */
    @JsonProperty("unprocessed_inbound")
    int unprocessedInbound;

    /** 마지막 인바운드 수신 시각 */
    @JsonProperty("last_inbound_at")
    Instant lastInboundAt;

    /** 마지막 아웃바운드 발송 시각 */
    @JsonProperty("last_outbound_at")
    Instant lastOutboundAt;

    /** 조회 시각 */
    @JsonProperty("queried_at")
    @Builder.Default
    Instant queriedAt = Instant.now();
}

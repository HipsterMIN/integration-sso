package kr.go.smes.ido.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

/**
 * OnePass → 기관 아웃바운드 수동 발송 요청 DTO
 *
 * <p>PATCH /api/v1/agency/gateway/outbound/notify 엔드포인트 요청 바디.
 * 운영자 또는 내부 서비스가 특정 기관에 수동으로 이벤트를 발송할 때 사용.
 */
@Value
@Builder
public class OutboundNotifyRequest {

    /** 대상 기관 코드 */
    @JsonProperty("agency_code")
    String agencyCode;

    /**
     * 발송 이벤트 타입
     * NOTIFY_USER / CAST_ISSUED / PROVISIONING
     */
    @JsonProperty("event_type")
    String eventType;

    /** 발송 페이로드 JSON 문자열 (PII 최소화) */
    @JsonProperty("payload")
    String payloadJson;

    /** 멱등성 키 (UUID v7) — 없으면 서버에서 자동 생성 */
    @JsonProperty("idempotency_key")
    String idempotencyKey;

    /** 흐름 추적 ID */
    @JsonProperty("correlation_id")
    String correlationId;
}

package io.github.hipstermin.idem.hub.gateway;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

/**
 * ido.gateway_inbound_audit 테이블 레코드 도메인 객체
 */
@Value
@Builder(toBuilder = true)
public class GatewayInboundRecord {

    /** 레코드 ID (UUID v7, PK) */
    String id;

    /** 송신 기관 코드 */
    String agencyCode;

    /**
     * 이벤트 타입
     * AGENCY_USER_UPDATED / AGENCY_USER_WITHDRAWN /
     * AGENCY_USER_REGISTERED / AGENCY_BIZ_CONVERTED / CUSTOM
     */
    String eventType;

    /** 멱등성 키 (X-Idempotency-Key, UNIQUE) */
    String idempotencyKey;

    /** 수신 페이로드 JSON (PII 최소화) */
    String payloadJson;

    /**
     * 처리 상태
     * RECEIVED / PROCESSED / REJECTED / DUPLICATE
     */
    @Builder.Default
    String status = "RECEIVED";

    /** 송신 IP */
    String sourceIp;

    /** 흐름 추적 ID */
    String correlationId;

    /** 수신 시각 */
    Instant receivedAt;

    /** 처리 완료 시각 */
    Instant processedAt;

    /** 처리 실패 메시지 */
    String errorMessage;
}

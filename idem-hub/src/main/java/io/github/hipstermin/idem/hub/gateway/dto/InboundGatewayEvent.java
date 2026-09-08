package io.github.hipstermin.idem.hub.gateway.dto;

import lombok.Builder;
import lombok.Value;

/**
 * 기관 → OnePass 인바운드 게이트웨이 이벤트 도메인 객체
 *
 * <p>AgencyGatewayController가 수신한 HTTP 요청을 이 객체로 변환하여
 * AgencyGatewayService에 전달한다.
 *
 * <p>PII 최소화: 페이로드 내 실명/전화 평문 금지.
 * 기관은 agencyUserId + 해시만 포함해야 함 (설계서 §15.4.2).
 */
@Value
@Builder
public class InboundGatewayEvent {

    /**
     * 이벤트 타입 (gateway_inbound_audit.event_type 컬럼 값)
     * AGENCY_USER_UPDATED / AGENCY_USER_WITHDRAWN / AGENCY_USER_REGISTERED /
     * AGENCY_BIZ_CONVERTED / CUSTOM
     */
    String eventType;

    /** 멱등성 키 (X-Idempotency-Key 헤더 값, UUID v7) */
    String idempotencyKey;

    /** 이벤트 페이로드 JSON 문자열 (PII 최소화 적용) */
    String payloadJson;

    /** 검증된 기관 코드 (X-Agency-Code 헤더 → 인터셉터가 검증 후 주입) */
    String agencyCode;

    /** 송신 IP (HttpServletRequest.getRemoteAddr()) */
    String sourceIp;

    /** 흐름 추적 ID (X-Correlation-ID 헤더) */
    String correlationId;
}

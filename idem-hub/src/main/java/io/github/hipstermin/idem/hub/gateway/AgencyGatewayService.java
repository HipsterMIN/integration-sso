package io.github.hipstermin.idem.hub.gateway;

import io.github.hipstermin.idem.hub.gateway.dto.GatewayStatusResponse;
import io.github.hipstermin.idem.hub.gateway.dto.InboundGatewayEvent;
import io.github.hipstermin.idem.hub.gateway.dto.OutboundNotifyRequest;

/**
 * 양방향 Agency Gateway 서비스 인터페이스
 *
 * <p>Sprint 15 핵심 서비스 — 설계서 §15.4
 *
 * <h3>인바운드 (기관 → OnePass)</h3>
 * <ul>
 *   <li>X-Api-Key 검증 (HandoffAgencyKeyInterceptor와 동일한 SHA-256 비교)</li>
 *   <li>X-Idempotency-Key 중복 수신 방어 (Redis SET NX 24h + DB UNIQUE)</li>
 *   <li>X-Internal-Sig HMAC-SHA256 서명 검증 (위변조 방지)</li>
 *   <li>이벤트 라우팅 → gateway_inbound_audit INSERT</li>
 * </ul>
 *
 * <h3>아웃바운드 (OnePass → 기관)</h3>
 * <ul>
 *   <li>기관 엔드포인트 조회 (AgencyEndpointRegistryRepository)</li>
 *   <li>HTTP POST 발송 + gateway_outbound_audit INSERT</li>
 *   <li>멱등성 키 자동 생성 (UUID v7)</li>
 * </ul>
 *
 * <h3>상태 조회</h3>
 * <ul>
 *   <li>기관별 엔드포인트 / 프로비저닝 / 인바운드 현황 집계</li>
 * </ul>
 */
public interface AgencyGatewayService {

    /**
     * 인바운드 이벤트 수신 처리
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>Redis SET NX 중복 체크 (idempotencyKey, TTL 24h)</li>
     *   <li>gateway_inbound_audit INSERT (RECEIVED)</li>
     *   <li>이벤트 타입 기반 라우팅 (확장 포인트)</li>
     *   <li>처리 완료 → status PROCESSED 갱신</li>
     * </ol>
     *
     * @param event 수신된 인바운드 이벤트
     * @throws io.github.hipstermin.idem.common.error.PlatformException PROV_IDEMPOTENCY_CONFLICT(E-PROV-503): 중복 이벤트
     * @throws io.github.hipstermin.idem.common.error.PlatformException PROV_INBOUND_REJECTED(E-PROV-504): 처리 거부
     */
    void receiveInbound(InboundGatewayEvent event);

    /**
     * 아웃바운드 이벤트 수동 발송
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>idempotencyKey 없으면 UUID v7 자동 생성</li>
     *   <li>기관 엔드포인트 조회 (endpoint_type = WEBHOOK)</li>
     *   <li>HTTP POST 발송</li>
     *   <li>gateway_outbound_audit INSERT (DELIVERED / FAILED)</li>
     * </ol>
     *
     * @param request 아웃바운드 발송 요청
     * @return HTTP 응답 상태코드 (기관 응답)
     * @throws io.github.hipstermin.idem.common.error.PlatformException PROV_AGENCY_ENDPOINT_NOT_FOUND(E-PROV-501): 엔드포인트 없음
     */
    int sendOutbound(OutboundNotifyRequest request);

    /**
     * 기관 연동 상태 조회
     *
     * @param agencyCode 기관 코드
     * @return 기관별 게이트웨이 상태 (엔드포인트 수, PENDING/DEAD_LETTER 건수 등)
     */
    GatewayStatusResponse getStatus(String agencyCode);
}

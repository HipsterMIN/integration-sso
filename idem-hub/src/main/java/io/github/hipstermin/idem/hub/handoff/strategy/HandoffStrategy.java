package io.github.hipstermin.idem.hub.handoff.strategy;

import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.domain.HandoffTicket;

/**
 * Handoff 연동 유형별 전략 인터페이스 (Strategy Pattern)
 *
 * <p>설계서 §8절 — integration_type 에 따라 Handoff 이후 처리를 다르게 한다:
 * <ul>
 *   <li>{@code DIRECT}       — 기관이 IdO API를 직접 호출 (기본)</li>
 *   <li>{@code APACHE_GATE}  — Apache/Nginx 게이트웨이를 통한 프록시 연동</li>
 *   <li>{@code BRIDGE}       — IdO가 중간 Bridge 서버를 경유해 Payload 전달</li>
 *   <li>{@code INTERNAL_SSO} — 동일 기관 내부 SSO 시스템과 연계</li>
 * </ul>
 *
 * @see DirectHandoffStrategy
 * @see BridgeHandoffStrategy
 */
public interface HandoffStrategy {

    /** 이 전략이 처리하는 integration_type 문자열 */
    String getIntegrationType();

    /**
     * Ticket 발급 후 후처리 실행
     *
     * <p>DIRECT: 아무 작업 없음 (기관이 직접 verify 호출)<br>
     * BRIDGE: Bridge 서버로 Payload를 미리 전달<br>
     * APACHE_GATE: 게이트웨이 세션 등록
     *
     * @param ticket     발급된 Handoff Ticket
     * @param payload    사전 빌드된 HandoffPayload (null이면 미생성)
     * @param correlationId 추적 ID
     */
    void postIssue(HandoffTicket ticket, HandoffPayload payload, String correlationId);
}

package io.github.hipstermin.idem.hub.handoff.strategy;

import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.domain.HandoffTicket;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import org.springframework.stereotype.Component;

/**
 * OIDC_RP 연동 유형의 Handoff 전략 (S6) — 도달하면 안 되는 자리.
 *
 * <p>OIDC_RP 기관은 표준 OIDC 로만 로그인하며 Handoff 티켓을 발급하지 않는다. 발급 요청은
 * {@code HandoffServiceImpl.issue} 가 E-IDO-121 로 먼저 거부한다. 이 클래스는 {@link HandoffStrategyFactory} 가
 * "모든 {@link IntegrationType} 에 전략이 있어야 기동" 하는 규칙을 지키기 위한 것이며, 호출되면 결함이다.
 */
@Component
public class OidcRpHandoffStrategy implements HandoffStrategy {

    @Override
    public IntegrationType getIntegrationType() {
        return IntegrationType.OIDC_RP;
    }

    @Override
    public void postIssue(HandoffTicket ticket, HandoffPayload payload, String correlationId) {
        throw new IllegalStateException("OIDC_RP 기관에는 Handoff 티켓이 발급되지 않아야 한다: agency="
                + ticket.getAgencyCode() + " cid=" + correlationId);
    }
}

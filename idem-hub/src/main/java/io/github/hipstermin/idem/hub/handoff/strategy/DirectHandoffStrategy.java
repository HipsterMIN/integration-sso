package io.github.hipstermin.idem.hub.handoff.strategy;

import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.domain.HandoffTicket;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * DIRECT 연동 전략 (기본값)
 *
 * <p>기관이 IdO의 {@code POST /api/v1/handoff/verify} 를 직접 호출하는 방식.
 * 발급 후 별도 후처리 없음 — 기관이 ticketId를 받아 직접 verify 요청.
 *
 * <p>대부분의 기관이 이 방식을 사용하며, 가장 간단하고 안전하다.
 */
@Slf4j
@Component
public class DirectHandoffStrategy implements HandoffStrategy {

    @Override
    public IntegrationType getIntegrationType() {
        return IntegrationType.DIRECT;
    }

    @Override
    public void postIssue(HandoffTicket ticket, HandoffPayload payload, String correlationId) {
        // DIRECT: 기관이 직접 verify 호출 → 후처리 없음
        log.debug("[DirectStrategy] 후처리 없음 (DIRECT): ticketId={} agency={} cid={}",
                ticket.getTicketId(), ticket.getAgencyCode(), correlationId);
    }
}

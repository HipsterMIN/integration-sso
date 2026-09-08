package io.github.hipstermin.idem.hub.handoff.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.domain.HandoffTicket;
import io.github.hipstermin.idem.hub.domain.AgencyMeta;
import io.github.hipstermin.idem.hub.infrastructure.AgencyMetaRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * BRIDGE 연동 전략
 *
 * <p>설계서 §8.3 — IdO가 중간 Bridge 서버에 Payload를 미리 푸시하는 방식.
 * 기관 네트워크가 외부 직접 호출이 불가능한 경우 사용.
 *
 * <p><b>흐름</b>:
 * <pre>
 *   IdO issues Ticket
 *     → BridgeHandoffStrategy.postIssue()
 *         → POST {bridgeEndpoint}/api/handoff/push
 *               body: { ticketId, payload }
 *               headers: X-Agency-Code, X-Handoff-Signature
 *     → Bridge 서버가 Payload를 임시 저장
 *     → 기관 시스템이 Bridge에서 Payload 조회
 * </pre>
 *
 * <p><b>보안</b>: HMAC-SHA256 서명으로 Bridge 서버 위·변조 방지
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BridgeHandoffStrategy implements HandoffStrategy {

    private final AgencyMetaRepository agencyMetaRepository;
    private final RestTemplate         restTemplate;
    private final ObjectMapper         objectMapper;

    @Override
    public String getIntegrationType() {
        return "BRIDGE";
    }

    @Override
    public void postIssue(HandoffTicket ticket, HandoffPayload payload, String correlationId) {
        String agencyCode = ticket.getAgencyCode();

        // Bridge endpoint 조회
        String bridgeEndpoint = agencyMetaRepository.findByCode(agencyCode)
                .map(AgencyMeta::getBridgeEndpoint)
                .orElse(null);

        if (bridgeEndpoint == null || bridgeEndpoint.isBlank()) {
            log.warn("[BridgeStrategy] Bridge endpoint 미설정 — 스킵: agency={} cid={}", agencyCode, correlationId);
            return;
        }

        try {
            String pushUrl = bridgeEndpoint + "/api/handoff/push";

            Map<String, Object> body = Map.of(
                    "ticketId",      ticket.getTicketId(),
                    "agencyCode",    agencyCode,
                    "correlationId", correlationId,
                    "payload",       payload != null ? payload : Map.of(),
                    "expiresAt",     ticket.getExpiresAt() != null ? ticket.getExpiresAt().toString() : ""
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Agency-Code",    agencyCode);
            headers.set("X-Correlation-Id", correlationId);
            headers.set("X-Source-System",  "ido");

            ResponseEntity<String> resp = restTemplate.exchange(
                    pushUrl, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

            if (resp.getStatusCode().is2xxSuccessful()) {
                log.info("[BridgeStrategy] Bridge 푸시 성공: ticketId={} bridge={} cid={}",
                        ticket.getTicketId(), pushUrl, correlationId);
            } else {
                log.warn("[BridgeStrategy] Bridge 푸시 실패 (non-2xx): status={} agency={} cid={}",
                        resp.getStatusCode(), agencyCode, correlationId);
            }
        } catch (Exception e) {
            // Bridge 실패는 치명적이지 않음 — ticket은 이미 발급됨
            log.error("[BridgeStrategy] Bridge 푸시 예외 (비치명적): agency={} cid={} err={}",
                    agencyCode, correlationId, e.getMessage());
        }
    }
}

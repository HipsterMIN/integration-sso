package io.github.hipstermin.idem.hub.handoff.strategy;

import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.domain.HandoffTicket;
import io.github.hipstermin.idem.hub.domain.AgencyMeta;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.infrastructure.AgencyMetaRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * INTERNAL_SSO 연동 전략
 *
 * <p>설계서 §8.4 — 기관 내부 SSO 세션 사전 등록 방식.
 * IdO가 Ticket을 발급한 직후, 기관의 SSO 엔드포인트
 * ({@code /internal/sso-session})에 Ticket 메타데이터를 미리 Push하여
 * 기관 사용자가 콜백 URL에 도달할 때 SSO 쿠키가 자동 발급되도록 한다.
 *
 * <p><b>흐름</b>:
 * <pre>
 *   IdO issues Ticket
 *     → InternalSsoHandoffStrategy.postIssue()
 *         → agencyMetaRepository.findByCode(agencyCode)
 *               → ssoDomain 조회
 *         → POST {ssoDomain}/internal/sso-session
 *               body: { ticketId, qimUserId, authLevel, expiresAt, correlationId }
 *               headers: X-Agency-Code, X-Correlation-Id, X-Source-System
 *     → 기관 SSO 서버가 세션 쿠키 사전 등록
 *     → 사용자 콜백 도달 시 SSO 쿠키 자동 발급
 * </pre>
 *
 * <p><b>보안 고려사항</b>:
 * <ul>
 *   <li>ssoDomain은 기관 내부 네트워크 엔드포인트 (외부 직접 노출 X)</li>
 *   <li>X-Source-System 헤더로 발신 모듈 식별</li>
 *   <li>postIssue() 실패는 비치명적 — Ticket은 이미 발급 완료됨</li>
 * </ul>
 *
 * <p><b>DB 매핑</b>:
 * {@code agency_meta.sso_domain} VARCHAR(200) 컬럼 사용.
 *
 * @see HandoffStrategy
 * @see io.github.hipstermin.idem.hub.domain.AgencyMeta#getSsoDomain()
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalSsoHandoffStrategy implements HandoffStrategy {

    /** SSO 세션 사전 등록 엔드포인트 경로 (설계서 §8.4) */
    private static final String SSO_SESSION_PATH = "/internal/sso-session";

    private final AgencyMetaRepository agencyMetaRepository;
    private final RestTemplate         restTemplate;

    @Override
    public IntegrationType getIntegrationType() {
        return IntegrationType.INTERNAL_SSO;
    }

    /**
     * SSO 세션 사전 등록
     *
     * <p>기관의 {@code ssoDomain} 엔드포인트에 ticketId + 사용자 정보를 미리 Push한다.
     * 기관 SSO 서버는 이 정보를 임시 저장해두고, 사용자가 콜백 URL에 도달할 때
     * SSO 쿠키를 자동 발급한다.
     *
     * @param ticket        발급된 Handoff Ticket
     * @param payload       사전 빌드된 Handoff Payload (null 가능)
     * @param correlationId 전체 흐름 추적 키
     */
    @Override
    public void postIssue(HandoffTicket ticket, HandoffPayload payload, String correlationId) {
        String agencyCode = ticket.getAgencyCode();

        // 1. 기관 메타에서 ssoDomain 조회
        String ssoDomain = agencyMetaRepository.findByCode(agencyCode)
                .map(AgencyMeta::getSsoDomain)
                .orElse(null);

        if (ssoDomain == null || ssoDomain.isBlank()) {
            log.warn("[InternalSsoStrategy] ssoDomain 미설정 — INTERNAL_SSO 사전 등록 스킵: " +
                     "agency={} ticketId={} cid={}", agencyCode, ticket.getTicketId(), correlationId);
            return;
        }

        String ssoUrl = ssoDomain.stripTrailing() + SSO_SESSION_PATH;

        try {
            // 2. 요청 Body 구성 (ticketId, qimUserId, authLevel, expiresAt, correlationId)
            Map<String, Object> body = Map.of(
                    "ticketId",      ticket.getTicketId(),
                    "qimUserId",     ticket.getQimUserId() != null ? ticket.getQimUserId() : "",
                    "authLevel",     ticket.getAuthLevel() != null ? ticket.getAuthLevel().name() : "",
                    "expiresAt",     ticket.getExpiresAt() != null ? ticket.getExpiresAt().toString() : "",
                    "correlationId", correlationId
            );

            // 3. 헤더 구성
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Agency-Code",    agencyCode);
            headers.set("X-Correlation-Id", correlationId);
            headers.set("X-Source-System",  "ido");

            // 4. POST 전송
            ResponseEntity<String> resp = restTemplate.exchange(
                    ssoUrl, HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);

            if (resp.getStatusCode().is2xxSuccessful()) {
                log.info("[InternalSsoStrategy] SSO 세션 사전 등록 성공: " +
                         "ticketId={} ssoDomain={} cid={}",
                        ticket.getTicketId(), ssoDomain, correlationId);
            } else {
                log.warn("[InternalSsoStrategy] SSO 세션 사전 등록 실패 (non-2xx): " +
                         "status={} agency={} ticketId={} cid={}",
                        resp.getStatusCode(), agencyCode, ticket.getTicketId(), correlationId);
            }

        } catch (Exception e) {
            // SSO 사전 등록 실패는 비치명적 — Ticket은 이미 정상 발급됨
            // 기관 측에서 /verify 호출 시에도 Ticket 유효성 확인 가능
            log.error("[InternalSsoStrategy] SSO 세션 사전 등록 예외 (비치명적): " +
                      "agency={} ticketId={} cid={} err={}",
                    agencyCode, ticket.getTicketId(), correlationId, e.getMessage());
        }
    }
}

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
 * APACHE_GATE 연동 전략
 *
 * <p>설계서 §8.5 — Apache {@code mod_auth_openidc} 호환 게이트웨이 세션 헤더 사전 등록 방식.
 * IdO가 Ticket을 발급한 직후, 기관 Apache 게이트웨이의 세션 등록 엔드포인트에
 * {@code X-Remote-User}, {@code X-Auth-Level}, {@code X-Handoff-Token},
 * {@code X-Session-Expiry} 헤더 값을 미리 Push한다.
 * 기관 사용자가 게이트웨이 URL에 도달할 때 Apache가 해당 세션 정보를
 * HTTP 헤더로 백엔드 애플리케이션에 주입한다.
 *
 * <p><b>흐름</b>:
 * <pre>
 *   IdO issues Ticket
 *     → ApacheGateHandoffStrategy.postIssue()
 *         → agencyMetaRepository.findByCode(agencyCode)
 *               → apacheGateEndpoint 조회
 *         → POST {apacheGateEndpoint}
 *               headers: X-Remote-User, X-Auth-Level, X-Handoff-Token,
 *                        X-Session-Expiry, X-Agency-Code, X-Correlation-Id
 *               body: { ticketId, qimUserId, authLevel, expiresAt }
 *     → Apache 게이트웨이가 세션 헤더 임시 저장
 *     → 사용자 게이트웨이 도달 시 mod_auth 헤더 자동 주입
 * </pre>
 *
 * <p><b>Apache mod_auth_openidc 호환 헤더 설명</b>:
 * <ul>
 *   <li>{@code X-Remote-User}  : 인증된 사용자 식별자 (qimUserId)</li>
 *   <li>{@code X-Auth-Level}   : 인증 수준 (L1 / L2 / L3)</li>
 *   <li>{@code X-Handoff-Token}: Handoff Ticket ID (1회용, TTL 60초)</li>
 *   <li>{@code X-Session-Expiry}: 세션 만료 시각 (ISO-8601 UTC)</li>
 * </ul>
 *
 * <p><b>DB 매핑</b>:
 * {@code agency_meta.bridge_endpoint} 컬럼을 재사용 (APACHE_GATE 전용 컬럼 추가 전 임시).
 * {@code AgencyMetaRepositoryImpl.toDomain()} 에서 integrationType=APACHE_GATE 시
 * {@code apacheGateEndpoint} 필드에 매핑한다.
 *
 * <p><b>보안 고려사항</b>:
 * <ul>
 *   <li>apacheGateEndpoint는 기관 내부 네트워크 엔드포인트 (외부 직접 노출 X)</li>
 *   <li>X-Handoff-Token은 1회성 — 기관이 소비하면 무효화</li>
 *   <li>postIssue() 실패는 비치명적 — Ticket은 이미 발급 완료됨</li>
 * </ul>
 *
 * @see HandoffStrategy
 * @see io.github.hipstermin.idem.hub.domain.AgencyMeta#getApacheGateEndpoint()
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApacheGateHandoffStrategy implements HandoffStrategy {

    private final AgencyMetaRepository agencyMetaRepository;
    private final RestTemplate         restTemplate;

    @Override
    public IntegrationType getIntegrationType() {
        return IntegrationType.APACHE_GATE;
    }

    /**
     * Apache 게이트웨이 세션 헤더 사전 등록
     *
     * <p>기관의 {@code apacheGateEndpoint}에 mod_auth_openidc 호환 헤더 정보를 Push한다.
     * Apache 게이트웨이는 이 정보를 임시 저장해두고, 사용자가 도달할 때
     * 백엔드 애플리케이션에 세션 헤더를 주입한다.
     *
     * @param ticket        발급된 Handoff Ticket
     * @param payload       사전 빌드된 Handoff Payload (null 가능)
     * @param correlationId 전체 흐름 추적 키
     */
    @Override
    public void postIssue(HandoffTicket ticket, HandoffPayload payload, String correlationId) {
        String agencyCode = ticket.getAgencyCode();

        // 1. 기관 메타에서 apacheGateEndpoint 조회
        String apacheGateEndpoint = agencyMetaRepository.findByCode(agencyCode)
                .map(AgencyMeta::getApacheGateEndpoint)
                .orElse(null);

        if (apacheGateEndpoint == null || apacheGateEndpoint.isBlank()) {
            log.warn("[ApacheGateStrategy] apacheGateEndpoint 미설정 — APACHE_GATE 헤더 사전 등록 스킵: " +
                     "agency={} ticketId={} cid={}", agencyCode, ticket.getTicketId(), correlationId);
            return;
        }

        try {
            // 2. mod_auth_openidc 호환 헤더 구성
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Apache mod_auth 표준 헤더 (설계서 §8.5)
            headers.set("X-Remote-User",    ticket.getQimUserId() != null ? ticket.getQimUserId() : "");
            headers.set("X-Auth-Level",     ticket.getAuthLevel() != null ? ticket.getAuthLevel().name() : "");
            headers.set("X-Handoff-Token",  ticket.getTicketId());
            headers.set("X-Session-Expiry", ticket.getExpiresAt() != null
                    ? ticket.getExpiresAt().toString() : "");

            // 플랫폼 공통 헤더
            headers.set("X-Agency-Code",    agencyCode);
            headers.set("X-Correlation-Id", correlationId);
            headers.set("X-Source-System",  "idem-hub");

            // 3. 요청 Body 구성 (헤더 외 추가 메타데이터)
            Map<String, Object> body = Map.of(
                    "ticketId",      ticket.getTicketId(),
                    "qimUserId",     ticket.getQimUserId() != null ? ticket.getQimUserId() : "",
                    "authLevel",     ticket.getAuthLevel() != null ? ticket.getAuthLevel().name() : "",
                    "expiresAt",     ticket.getExpiresAt() != null ? ticket.getExpiresAt().toString() : "",
                    "correlationId", correlationId
            );

            // 4. POST 전송
            ResponseEntity<String> resp = restTemplate.exchange(
                    apacheGateEndpoint, HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);

            if (resp.getStatusCode().is2xxSuccessful()) {
                log.info("[ApacheGateStrategy] Apache 게이트웨이 세션 헤더 사전 등록 성공: " +
                         "ticketId={} endpoint={} cid={}",
                        ticket.getTicketId(), apacheGateEndpoint, correlationId);
            } else {
                log.warn("[ApacheGateStrategy] Apache 게이트웨이 세션 헤더 사전 등록 실패 (non-2xx): " +
                         "status={} agency={} ticketId={} cid={}",
                        resp.getStatusCode(), agencyCode, ticket.getTicketId(), correlationId);
            }

        } catch (Exception e) {
            // Apache 게이트웨이 등록 실패는 비치명적 — Ticket은 이미 정상 발급됨
            log.error("[ApacheGateStrategy] Apache 게이트웨이 세션 등록 예외 (비치명적): " +
                      "agency={} ticketId={} cid={} err={}",
                    agencyCode, ticket.getTicketId(), correlationId, e.getMessage());
        }
    }
}

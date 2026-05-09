package kr.go.smes.agency.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import kr.go.smes.common.util.UuidV7;

/**
 * IdO Handoff Ticket 발급 클라이언트
 *
 * <p><b>목적</b>: agency-stub 이 IdO 에 직접 Handoff Ticket 발급을 요청하는 HTTP 클라이언트.
 * 실제 유관기관 테스트 시나리오에서 "사전 QIM 인증 완료 후 Ticket 요청" 단계를 재현합니다.
 *
 * <p><b>흐름</b>:
 * <pre>
 *   AgencySimulatorController.runFullFlow()
 *     └─ IdoTicketClient.issue()
 *           POST {ido.base-url}/api/v1/handoff/issue
 *           Headers: X-Agency-Code, X-Agency-Key, X-Correlation-Id, Idempotency-Key
 *           → HandoffTicket (ticketId, expiresAt, ...)
 *     └─ IdoVerifyClient.verify(ticketId, correlationId)
 *           POST {ido.base-url}/api/v1/handoff/verify
 *           → HandoffPayload (APPROVED / REJECTED / HOLD)
 *     └─ AgencySessionService.createSession(payload, ticketId, ...)
 *           → 기관 로컬 세션 생성 + AGSID 발급
 * </pre>
 *
 * <p><b>Resilience4j</b>: CircuitBreaker "ido-ticket" / Retry "ido-ticket"
 * (설정: application.yml resilience4j 섹션)
 *
 * <p><b>보안</b>: X-Agency-Key 원문은 절대 로그에 기록하지 않음.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdoTicketClient {

    private static final String PATH_ISSUE = "/api/v1/handoff/issue";

    @Qualifier("idoRestTemplate")
    private final RestTemplate restTemplate;

    @Value("${agency-stub.ido.base-url:http://localhost:8083}")
    private String idoBaseUrl;

    @Value("${agency-stub.code:AGENCY_STUB_001}")
    private String agencyCode;

    @Value("${agency-stub.ido.api-key:stub-api-key-dev-001}")
    private String apiKey;

    // ════════════════════════════════════════════════════════════════════════
    // 티켓 발급 — POST /api/v1/handoff/issue
    // ════════════════════════════════════════════════════════════════════════

    /**
     * IdO 에 Handoff Ticket 발급 요청.
     *
     * <p>실제 운영에서는 QIM 인증이 완료된 후 IdO FE Session 흐름을 통해 발급되지만,
     * agency-stub 시뮬레이터에서는 이 메서드를 직접 호출해 E2E 흐름을 재현합니다.
     *
     * @param qimUserId    QIM 사용자 ID (시뮬레이션용: 임의 UUID 사용 가능)
     * @param authResultId 인증 결과 ID (시뮬레이션용: 임의 UUID 사용 가능)
     * @param authLevel    인증 수준 (L1 / L2 / L3)
     * @param providerCode 인증 수단 코드 (예: QSIGN_CERT)
     * @param correlationId 추적 ID
     * @return 발급된 티켓 정보 {@link TicketResult}
     * @throws IdoTicketIssuanceException 발급 실패 시
     */
    @CircuitBreaker(name = "ido-ticket", fallbackMethod = "issueFallback")
    @Retry(name = "ido-ticket")
    public TicketResult issue(
            String qimUserId,
            String authResultId,
            String authLevel,
            String providerCode,
            String correlationId) {

        String url = idoBaseUrl + PATH_ISSUE;
        String idempotencyKey = UuidV7.generate();

        log.info("[IdoTicketClient] Ticket 발급 요청: qimUserId={} authLevel={} correlationId={} url={}",
                qimUserId, authLevel, correlationId, url);

        HttpHeaders headers = buildHeaders(correlationId, idempotencyKey);

        IssueRequest body = new IssueRequest(
                agencyCode,
                qimUserId,
                authResultId,
                authLevel,
                providerCode,
                null   // callbackUrl — simulator 에서는 불필요
        );

        try {
            ResponseEntity<TicketResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    TicketResponse.class
            );

            TicketResponse ticketResp = response.getBody();
            if (ticketResp == null || ticketResp.getTicketId() == null) {
                log.error("[IdoTicketClient] 응답 body null 또는 ticketId 없음: correlationId={}", correlationId);
                throw new IdoTicketIssuanceException("EMPTY_TICKET_RESPONSE", "IdO 응답에 ticketId가 없습니다");
            }

            log.info("[IdoTicketClient] Ticket 발급 완료: ticketId={} expiresAt={} correlationId={}",
                    ticketResp.getTicketId(), ticketResp.getExpiresAt(), correlationId);

            return new TicketResult(
                    ticketResp.getTicketId(),
                    ticketResp.getExpiresAt(),
                    correlationId
            );

        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            String body2 = truncate(e.getResponseBodyAsString(), 300);
            log.warn("[IdoTicketClient] 4xx 오류: status={} body={} correlationId={}", status, body2, correlationId);
            throw new IdoTicketIssuanceException("CLIENT_ERROR_" + status,
                    "Ticket 발급 실패 (HTTP " + status + "): " + body2);

        } catch (HttpServerErrorException e) {
            log.warn("[IdoTicketClient] 5xx 오류: status={} correlationId={}",
                    e.getStatusCode().value(), correlationId);
            throw e; // Retry 가 처리

        } catch (ResourceAccessException e) {
            log.warn("[IdoTicketClient] 네트워크 오류: correlationId={} err={}", correlationId, e.getMessage());
            throw e; // Retry 가 처리
        }
    }

    /**
     * Resilience4j Fallback — CB OPEN 또는 모든 Retry 소진 시
     */
    @SuppressWarnings("unused")
    public TicketResult issueFallback(
            String qimUserId, String authResultId, String authLevel,
            String providerCode, String correlationId, Throwable t) {
        log.error("[IdoTicketClient] FALLBACK 실행: qimUserId={} correlationId={} cause={}",
                qimUserId, correlationId, t.getMessage());
        throw new IdoTicketIssuanceException("IDO_UNAVAILABLE",
                "IdO 서비스에 일시적으로 접근할 수 없습니다: " + t.getMessage());
    }

    // ════════════════════════════════════════════════════════════════════════
    // 헬퍼
    // ════════════════════════════════════════════════════════════════════════

    private HttpHeaders buildHeaders(String correlationId, String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Agency-Code",    agencyCode);
        h.set("X-Agency-Key",     apiKey);         // 로그에 기록 안 됨
        h.set("X-Correlation-Id", correlationId != null ? correlationId : "");
        h.set("Idempotency-Key",  idempotencyKey);
        h.set("Accept",           MediaType.APPLICATION_JSON_VALUE);
        return h;
    }

    private String truncate(String s, int max) {
        return (s != null && s.length() > max) ? s.substring(0, max) + "..." : s;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Inner DTO / 결과 레코드
    // ════════════════════════════════════════════════════════════════════════

    /** POST /api/v1/handoff/issue 요청 Body */
    record IssueRequest(
            String agencyCode,
            String qimUserId,
            String authResultId,
            String authLevel,
            String providerCode,
            String callbackUrl
    ) {}

    /** POST /api/v1/handoff/issue 응답 Body (HandoffTicket 핵심 필드만 매핑) */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TicketResponse {
        private String  ticketId;
        private String  agencyCode;
        private String  correlationId;
        private String  state;
        private Instant issuedAt;
        private Instant expiresAt;
    }

    /** 발급 결과 레코드 — 호출부에 전달 */
    public record TicketResult(
            String  ticketId,
            Instant expiresAt,
            String  correlationId
    ) {}

    /** 발급 실패 예외 */
    public static class IdoTicketIssuanceException extends RuntimeException {
        private final String errorCode;

        public IdoTicketIssuanceException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String getErrorCode() { return errorCode; }
    }
}

package io.github.hipstermin.idem.tenant.client;

import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
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

/**
 * IdO Handoff Verify API 클라이언트
 *
 * <p><b>이전 상태</b>: callIdoVerify() 가 Stub 값을 반환하는 PoC 코드
 * <p><b>현재 구현</b>: 실제 {@code POST {idem.hub.base-url}/api/v1/handoff/verify} 호출
 *
 * <p><b>요청 헤더</b>:
 * <pre>
 *   X-Agency-Code:   AGENCY_STUB_001
 *   X-Agency-Key:    {운영: Vault 주입 / PoC: stub-api-key-dev-001}
 *   X-Correlation-Id: {correlationId}
 *   Content-Type:    application/json
 * </pre>
 *
 * <p><b>Resilience4j 설정</b>:
 * <ul>
 *   <li>CircuitBreaker "ido-verify" — 실패율 50% 초과 시 10s OPEN</li>
 *   <li>Retry "ido-verify" — 5xx / 네트워크 오류 시 최대 2회 재시도</li>
 * </ul>
 *
 * <p><b>에러 처리</b>:
 * <ul>
 *   <li>401 / 403 → {@code REJECTED} 상태 HandoffPayload 반환 (재시도 없음)</li>
 *   <li>404 → 티켓 없음 → {@code REJECTED} 반환</li>
 *   <li>409 → 이미 소비된 티켓 → {@code REJECTED} 반환</li>
 *   <li>5xx / 타임아웃 → Retry → 실패 시 fallback (HOLD 상태)</li>
 * </ul>
 *
 * <p><b>보안</b>: X-Agency-Key 원문은 로그에 절대 기록하지 않음.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdoVerifyClient {

    private static final String PATH_VERIFY = "/api/v1/handoff/verify";

    @Qualifier("idoRestTemplate")
    private final RestTemplate restTemplate;

    @Value("${idem.sample.ido.base-url:http://localhost:8083}")
    private String idoBaseUrl;

    @Value("${idem.sample.code:AGENCY_STUB_001}")
    private String agencyCode;

    @Value("${idem.sample.ido.api-key:stub-api-key-dev-001}")
    private String apiKey;

    /**
     * IdO Handoff Verify API 호출 (실제 HTTP POST)
     *
     * @param ticketId      검증할 Handoff Ticket ID
     * @param correlationId 추적 ID
     * @return {@link HandoffPayload} — APPROVED / REJECTED / HOLD
     */
    @CircuitBreaker(name = "ido-verify", fallbackMethod = "verifyFallback")
    @Retry(name = "ido-verify")
    public HandoffPayload verify(String ticketId, String correlationId) {
        String url = idoBaseUrl + PATH_VERIFY;

        log.info("[IdoVerifyClient] Verify 요청: ticketId={} correlationId={} url={}",
                ticketId, correlationId, url);

        HttpHeaders headers = buildHeaders(correlationId);
        VerifyRequest body  = new VerifyRequest(ticketId);

        try {
            ResponseEntity<HandoffPayload> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    HandoffPayload.class
            );

            HandoffPayload payload = response.getBody();
            if (payload == null) {
                log.warn("[IdoVerifyClient] 응답 body null: ticketId={}", ticketId);
                return rejectedPayload(ticketId, correlationId, "EMPTY_RESPONSE");
            }

            log.info("[IdoVerifyClient] Verify 완료: ticketId={} state={} correlationId={}",
                    ticketId, payload.getState(), correlationId);
            return payload;

        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            log.warn("[IdoVerifyClient] 4xx 오류: ticketId={} status={} body={}",
                    ticketId, status, truncate(e.getResponseBodyAsString(), 300));

            // 재시도 없이 즉시 REJECTED 처리
            return switch (status) {
                case 401, 403 -> rejectedPayload(ticketId, correlationId, "AUTH_FAILED");
                case 404      -> rejectedPayload(ticketId, correlationId, "TICKET_NOT_FOUND");
                case 409      -> rejectedPayload(ticketId, correlationId, "TICKET_ALREADY_CONSUMED");
                case 410      -> rejectedPayload(ticketId, correlationId, "TICKET_EXPIRED");
                default       -> rejectedPayload(ticketId, correlationId, "CLIENT_ERROR_" + status);
            };

        } catch (HttpServerErrorException e) {
            // 5xx → Retry가 재시도; 재시도 소진 시 fallback
            log.warn("[IdoVerifyClient] 5xx 오류: ticketId={} status={}", ticketId,
                    e.getStatusCode().value());
            throw e;

        } catch (ResourceAccessException e) {
            // 타임아웃 / 연결 거부 → Retry가 재시도
            log.warn("[IdoVerifyClient] 네트워크 오류: ticketId={} err={}", ticketId, e.getMessage());
            throw e;
        }
    }

    /**
     * Resilience4j Fallback — CB OPEN 또는 모든 Retry 소진 시
     * HOLD 상태 반환: 기관은 사용자에게 일시적 오류 안내 후 재시도 유도
     */
    @SuppressWarnings("unused")
    public HandoffPayload verifyFallback(String ticketId, String correlationId, Throwable t) {
        log.error("[IdoVerifyClient] FALLBACK 실행: ticketId={} correlationId={} cause={}",
                ticketId, correlationId, t.getMessage());
        return HandoffPayload.builder()
                .ticketId(ticketId)
                .correlationId(correlationId)
                .agencyCode(agencyCode)
                .state(HandoffPayload.HandoffState.HOLD)
                .build();
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private HttpHeaders buildHeaders(String correlationId) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Agency-Code",    agencyCode);
        h.set("X-Agency-Key",     apiKey);  // 로그에 기록 안 됨 (헤더 객체만 전달)
        h.set("X-Correlation-Id", correlationId != null ? correlationId : "");
        h.set("Accept",           MediaType.APPLICATION_JSON_VALUE);
        return h;
    }

    private HandoffPayload rejectedPayload(String ticketId, String correlationId, String reason) {
        log.warn("[IdoVerifyClient] REJECTED: ticketId={} reason={}", ticketId, reason);
        return HandoffPayload.builder()
                .ticketId(ticketId)
                .correlationId(correlationId)
                .agencyCode(agencyCode)
                .state(HandoffPayload.HandoffState.REJECTED)
                .build();
    }

    private String truncate(String s, int max) {
        return (s != null && s.length() > max) ? s.substring(0, max) + "..." : s;
    }

    // ── Inner Request DTO ─────────────────────────────────────────────────

    record VerifyRequest(String ticketId) {}
}

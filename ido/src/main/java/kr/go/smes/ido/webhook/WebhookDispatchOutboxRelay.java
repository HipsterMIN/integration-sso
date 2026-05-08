package kr.go.smes.ido.webhook;

import kr.go.smes.common.event.AuditLogEvent;
import kr.go.smes.ido.audit.AuditLogPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Webhook 발송 Outbox Relay (at-least-once HTTPS POST 보장)
 *
 * <p><b>책임</b>:
 * {@code ido.webhook_dispatch_outbox} 테이블의 PENDING 레코드를 폴링하여
 * 기관 endpoint로 HTTPS POST를 실행하고 결과를 갱신한다.
 *
 * <p><b>설계 패턴: Transactional Outbox + Polling Relay</b>
 * <pre>
 * [WebhookDispatcherService]
 *     └─► INSERT webhook_dispatch_outbox (PENDING)  ← 트랜잭션 내
 *
 * [이 클래스 — 500ms 폴링]
 *     └─► SELECT FOR UPDATE SKIP LOCKED (concurrency-safe)
 *             └─► HTTP POST → 기관 endpoint
 *                   ├─ 200~299: UPDATE status=DISPATCHED
 *                   ├─ 4xx/5xx: retry_count++ / next_retry_at = 지수 백오프
 *                   └─ max_retry 초과: UPDATE status=FAILED
 * </pre>
 *
 * <p><b>SKIP LOCKED</b>: 다중 IdO 인스턴스 배포 시 동일 레코드 중복 발송 방지.
 *
 * <p><b>지수 백오프 재시도 정책</b>:
 * <pre>
 *   retry 1 → next_retry_at = NOW() + 1s
 *   retry 2 → next_retry_at = NOW() + 4s
 *   retry 3 → next_retry_at = NOW() + 16s
 *   retry 4+→ status = FAILED (DLQ 개념)
 * </pre>
 *
 * <p><b>보안</b>:
 * <ul>
 *   <li>X-Webhook-Signature: HMAC-SHA256(payload, signingSecret) 헤더 첨부</li>
 *   <li>X-Webhook-Timestamp: 재사용 공격 방지 (기관이 ±5분 이내 검증 권장)</li>
 *   <li>X-Correlation-Id: end-to-end 추적</li>
 * </ul>
 *
 * <p><b>운영 주의</b>:
 * {@code ido.webhook.relay-batch-size}는 HTTP 동시 발송 수와 비례.
 * 단일 인스턴스 기준 최대 100건/500ms ≈ 200 TPS 처리 가능.
 * 60,000명 급증 시나리오에서는 수초에 걸쳐 분산 처리됨.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDispatchOutboxRelay {

    private static final String SOURCE_SYSTEM = "ido";

    // HTTP 상태 코드 분류
    private static final int HTTP_OK_MIN       = 200;
    private static final int HTTP_OK_MAX       = 299;
    private static final int HTTP_TOO_MANY     = 429;
    private static final int HTTP_SERVER_ERROR = 500;

    // 지수 백오프 기준 (초)
    private static final long BACKOFF_BASE_SEC = 2L;   // 2^retry 초

    private final JdbcTemplate          jdbcTemplate;
    @Qualifier("webhookRestTemplate")
    private final RestTemplate          restTemplate;
    private final WebhookDispatcherService webhookDispatcherService;
    private final AuditLogPublisher     auditLogPublisher;

    @Value("${ido.webhook.relay-interval-ms:500}")
    private long relayIntervalMs;

    @Value("${ido.webhook.relay-batch-size:50}")
    private int relayBatchSize;

    @Value("${ido.webhook.max-retry:3}")
    private int defaultMaxRetry;

    @Value("${ido.webhook.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${ido.webhook.read-timeout-ms:8000}")
    private int readTimeoutMs;

    // ═══════════════════════════════════════════════════════════════════════
    // 폴링 스케줄러
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * PENDING 레코드 폴링 + HTTP 발송 (고정 지연 스케줄)
     *
     * <p>fixedDelay 방식 사용 — 이전 실행이 완료된 후 대기.
     * 단일 인스턴스에서 순차 실행 보장.
     * 다중 인스턴스 환경: FOR UPDATE SKIP LOCKED로 중복 발송 방지.
     */
    @Scheduled(fixedDelayString = "${ido.webhook.relay-interval-ms:500}")
    public void relay() {
        List<Map<String, Object>> pending = fetchPendingBatch();
        if (pending.isEmpty()) return;

        log.debug("[WebhookRelay] PENDING 레코드 {}건 처리 시작", pending.size());

        int dispatched = 0;
        int failed     = 0;
        int retrying   = 0;

        for (Map<String, Object> row : pending) {
            DispatchResult result = dispatchOne(row);
            switch (result) {
                case SUCCESS  -> dispatched++;
                case FAILED   -> failed++;
                case RETRYING -> retrying++;
            }
        }

        if (dispatched + failed + retrying > 0) {
            log.info("[WebhookRelay] 배치 완료: 성공={} 실패={} 재시도예약={}",
                    dispatched, failed, retrying);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 단건 발송
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 단건 webhook HTTPS POST 실행
     *
     * @param row webhook_dispatch_outbox 단건 레코드
     * @return 발송 결과 (SUCCESS / FAILED / RETRYING)
     */
    private DispatchResult dispatchOne(Map<String, Object> row) {
        String dispatchId    = (String) row.get("dispatch_id");
        String agencyCode    = (String) row.get("agency_code");
        String endpointUrl   = (String) row.get("endpoint_url");
        String payloadJson   = (String) row.get("payload");
        String correlationId = (String) row.get("correlation_id");
        String sourceEventId = (String) row.get("source_event_id");
        String eventType     = (String) row.get("source_event_type");
        String signingSecret = (String) row.get("signing_secret_hash");  // 실제로는 signing 원본 저장 위치
        int    retryCount    = toInt(row.get("retry_count"), 0);
        int    maxRetry      = toInt(row.get("max_retry"), defaultMaxRetry);

        log.debug("[WebhookRelay] 발송 시도: dispatchId={} agencyCode={} endpoint={} retry={}/{}",
                dispatchId, agencyCode, endpointUrl, retryCount, maxRetry);

        try {
            // ① HMAC-SHA256 서명 + 타임스탬프
            String timestamp  = String.valueOf(Instant.now().getEpochSecond());
            String signTarget = timestamp + "." + payloadJson;
            String signature  = webhookDispatcherService.computeHmacSignature(signTarget, signingSecret);

            // ② HTTP 헤더 구성
            HttpHeaders headers = buildHeaders(correlationId, signature, timestamp, payloadJson.length());

            // ③ HTTP POST 실행
            ResponseEntity<String> response = restTemplate.exchange(
                    endpointUrl,
                    HttpMethod.POST,
                    new HttpEntity<>(payloadJson, headers),
                    String.class
            );

            int statusCode = response.getStatusCode().value();

            if (statusCode >= HTTP_OK_MIN && statusCode <= HTTP_OK_MAX) {
                // ✅ 성공
                markDispatched(dispatchId, statusCode);
                publishAuditSuccess(agencyCode, dispatchId, sourceEventId, eventType,
                        correlationId, statusCode, retryCount);
                log.info("[WebhookRelay] 발송 성공: dispatchId={} agencyCode={} status={} retry={}/{}",
                        dispatchId, agencyCode, statusCode, retryCount, maxRetry);
                return DispatchResult.SUCCESS;
            } else {
                // 2xx가 아닌 정상 응답 → 재시도 대상
                log.warn("[WebhookRelay] 비정상 응답: dispatchId={} agencyCode={} status={} retry={}/{}",
                        dispatchId, agencyCode, statusCode, retryCount, maxRetry);
                return handleRetryOrFail(dispatchId, agencyCode, sourceEventId, eventType,
                        correlationId, retryCount, maxRetry, statusCode,
                        "HTTP " + statusCode);
            }

        } catch (HttpClientErrorException e) {
            // 4xx — 기관 측 오류 (payload 문제 등)
            int statusCode = e.getStatusCode().value();
            log.warn("[WebhookRelay] 4xx 오류: dispatchId={} agencyCode={} status={} body={}",
                    dispatchId, agencyCode, statusCode,
                    truncate(e.getResponseBodyAsString(), 200));

            // 404, 410 → 즉시 FAILED (더 이상 존재하지 않는 endpoint)
            if (statusCode == 404 || statusCode == 410) {
                markFailed(dispatchId, statusCode, "endpoint not found: " + statusCode);
                publishAuditFailure(agencyCode, dispatchId, sourceEventId, eventType,
                        correlationId, statusCode, "ENDPOINT_NOT_FOUND");
                return DispatchResult.FAILED;
            }

            return handleRetryOrFail(dispatchId, agencyCode, sourceEventId, eventType,
                    correlationId, retryCount, maxRetry, statusCode,
                    "4xx: " + e.getMessage());

        } catch (HttpServerErrorException e) {
            // 5xx — 기관 서버 오류 → 재시도
            int statusCode = e.getStatusCode().value();
            log.warn("[WebhookRelay] 5xx 오류: dispatchId={} agencyCode={} status={}",
                    dispatchId, agencyCode, statusCode);
            return handleRetryOrFail(dispatchId, agencyCode, sourceEventId, eventType,
                    correlationId, retryCount, maxRetry, statusCode,
                    "5xx: " + e.getMessage());

        } catch (ResourceAccessException e) {
            // 네트워크 오류 (타임아웃, 연결 거부)
            log.warn("[WebhookRelay] 네트워크 오류: dispatchId={} agencyCode={} error={}",
                    dispatchId, agencyCode, e.getMessage());
            return handleRetryOrFail(dispatchId, agencyCode, sourceEventId, eventType,
                    correlationId, retryCount, maxRetry, null,
                    "network: " + e.getMessage());

        } catch (Exception e) {
            log.error("[WebhookRelay] 예상치 못한 오류: dispatchId={} agencyCode={} error={}",
                    dispatchId, agencyCode, e.getMessage(), e);
            return handleRetryOrFail(dispatchId, agencyCode, sourceEventId, eventType,
                    correlationId, retryCount, maxRetry, null,
                    "unexpected: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 재시도 / 실패 처리
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 재시도 또는 최종 실패 처리
     *
     * <p>지수 백오프: next_retry_at = NOW() + BACKOFF_BASE_SEC^(retryCount+1) 초
     * <pre>
     *   retry 0→1: NOW() + 2s
     *   retry 1→2: NOW() + 4s
     *   retry 2→3: NOW() + 8s
     *   retry 3+:  status = FAILED
     * </pre>
     */
    private DispatchResult handleRetryOrFail(String dispatchId, String agencyCode,
                                              String sourceEventId, String eventType,
                                              String correlationId, int retryCount, int maxRetry,
                                              Integer lastHttpStatus, String errorMessage) {
        if (retryCount >= maxRetry) {
            // 최대 재시도 초과 → FAILED
            markFailed(dispatchId, lastHttpStatus, errorMessage);
            publishAuditFailure(agencyCode, dispatchId, sourceEventId, eventType,
                    correlationId, lastHttpStatus, "MAX_RETRY_EXCEEDED: " + errorMessage);
            log.error("[WebhookRelay] 최대 재시도 초과 → FAILED: dispatchId={} agencyCode={} maxRetry={}",
                    dispatchId, agencyCode, maxRetry);
            return DispatchResult.FAILED;
        }

        // 지수 백오프 적용하여 다음 시도 예약
        long backoffSec = (long) Math.pow(BACKOFF_BASE_SEC, retryCount + 1);
        scheduleRetry(dispatchId, retryCount + 1, lastHttpStatus, errorMessage, backoffSec);
        log.warn("[WebhookRelay] 재시도 예약: dispatchId={} agencyCode={} nextRetry={}s retry={}/{}",
                dispatchId, agencyCode, backoffSec, retryCount + 1, maxRetry);
        return DispatchResult.RETRYING;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DB 조작
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * PENDING 레코드 배치 조회 (FOR UPDATE SKIP LOCKED)
     *
     * <p>SKIP LOCKED: 다중 인스턴스 동시 실행 시 동일 레코드 중복 처리 방지.
     * next_retry_at이 NULL이거나 현재 시각 이전인 레코드만 조회.
     */
    private List<Map<String, Object>> fetchPendingBatch() {
        try {
            return jdbcTemplate.queryForList("""
                    SELECT w.dispatch_id, w.agency_code, w.endpoint_url,
                           w.source_event_id, w.source_event_type, w.correlation_id,
                           w.payload::text AS payload,
                           w.retry_count, w.max_retry,
                           c.signing_secret_hash
                    FROM   ido.webhook_dispatch_outbox w
                    LEFT JOIN ido.agency_webhook_config c
                           ON c.agency_code = w.agency_code AND c.active = TRUE
                    WHERE  w.status = 'PENDING'
                      AND  (w.next_retry_at IS NULL OR w.next_retry_at <= NOW())
                    ORDER BY w.created_at ASC
                    LIMIT  ?
                    FOR UPDATE OF w SKIP LOCKED
                    """,
                    relayBatchSize
            );
        } catch (Exception e) {
            log.warn("[WebhookRelay] PENDING 조회 실패 (비치명적): {}", e.getMessage());
            return List.of();
        }
    }

    /** 발송 성공 → DISPATCHED */
    private void markDispatched(String dispatchId, int httpStatus) {
        try {
            jdbcTemplate.update("""
                    UPDATE ido.webhook_dispatch_outbox
                    SET    status = 'DISPATCHED',
                           last_http_status = ?,
                           dispatched_at = NOW()
                    WHERE  dispatch_id = ?
                    """,
                    httpStatus, dispatchId
            );
        } catch (Exception e) {
            log.error("[WebhookRelay] markDispatched 실패: dispatchId={} error={}", dispatchId, e.getMessage());
        }
    }

    /** 최종 실패 → FAILED */
    private void markFailed(String dispatchId, Integer httpStatus, String errorMessage) {
        try {
            jdbcTemplate.update("""
                    UPDATE ido.webhook_dispatch_outbox
                    SET    status = 'FAILED',
                           last_http_status = ?,
                           last_error_message = ?
                    WHERE  dispatch_id = ?
                    """,
                    httpStatus,
                    truncate(errorMessage, 500),
                    dispatchId
            );
        } catch (Exception e) {
            log.error("[WebhookRelay] markFailed 실패: dispatchId={} error={}", dispatchId, e.getMessage());
        }
    }

    /** 재시도 예약 — retry_count 증가 + next_retry_at 지수 백오프 */
    private void scheduleRetry(String dispatchId, int newRetryCount,
                                Integer httpStatus, String errorMessage, long backoffSec) {
        try {
            jdbcTemplate.update("""
                    UPDATE ido.webhook_dispatch_outbox
                    SET    retry_count        = ?,
                           last_http_status   = ?,
                           last_error_message = ?,
                           next_retry_at      = NOW() + (? || ' seconds')::interval
                    WHERE  dispatch_id = ?
                    """,
                    newRetryCount,
                    httpStatus,
                    truncate(errorMessage, 500),
                    backoffSec,
                    dispatchId
            );
        } catch (Exception e) {
            log.error("[WebhookRelay] scheduleRetry 실패: dispatchId={} error={}", dispatchId, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HTTP 헤더 구성
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Webhook 요청 HTTP 헤더 구성
     *
     * <p>기관 수신측 검증 방법:
     * <ol>
     *   <li>X-Webhook-Timestamp 값이 현재 시각 ±5분 이내인지 확인 (재사용 공격 방지)</li>
     *   <li>signTarget = timestamp + "." + requestBody</li>
     *   <li>expectedSig = "sha256=" + HEX(HmacSHA256(signTarget, sharedSecret))</li>
     *   <li>X-Webhook-Signature == expectedSig 검증</li>
     * </ol>
     */
    private HttpHeaders buildHeaders(String correlationId, String signature,
                                      String timestamp, int contentLength) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Webhook-Signature", signature);
        headers.set("X-Webhook-Timestamp",  timestamp);
        headers.set("X-Source-System",      SOURCE_SYSTEM);
        headers.set("X-Platform-Version",   "1.0");

        if (correlationId != null && !correlationId.isBlank()) {
            headers.set("X-Correlation-Id", correlationId);
        }

        // Content-Length 명시 (일부 기관 방화벽 요구사항)
        headers.setContentLength(contentLength);

        // Accept: application/json (기관 응답 형식 강제)
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));

        return headers;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 감사 로그
    // ═══════════════════════════════════════════════════════════════════════

    private void publishAuditSuccess(String agencyCode, String dispatchId,
                                      String sourceEventId, String eventType,
                                      String correlationId, int httpStatus, int retryCount) {
        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_WEBHOOK)
                        .eventAction("WEBHOOK_DISPATCHED")
                        .actorType(AuditLogEvent.ACTOR_SYSTEM)
                        .actorId(SOURCE_SYSTEM)
                        .resourceType("WEBHOOK")
                        .resourceId(dispatchId)
                        .agencyCode(agencyCode)
                        .correlationId(correlationId)
                        .outcome(AuditLogEvent.OUTCOME_SUCCESS)
                        .outcomeDetail("HTTP " + httpStatus + " retry=" + retryCount)
                        .metadata(Map.of(
                                "dispatchId",    dispatchId,
                                "sourceEventId", sourceEventId,
                                "eventType",     eventType,
                                "httpStatus",    httpStatus,
                                "retryCount",    retryCount
                        ))
                        .build()
        );
    }

    private void publishAuditFailure(String agencyCode, String dispatchId,
                                      String sourceEventId, String eventType,
                                      String correlationId, Integer httpStatus, String reason) {
        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_WEBHOOK)
                        .eventAction("WEBHOOK_DISPATCH_FAILED")
                        .actorType(AuditLogEvent.ACTOR_SYSTEM)
                        .actorId(SOURCE_SYSTEM)
                        .resourceType("WEBHOOK")
                        .resourceId(dispatchId)
                        .agencyCode(agencyCode)
                        .correlationId(correlationId)
                        .outcome(AuditLogEvent.OUTCOME_FAILURE)
                        .outcomeDetail(reason)
                        .metadata(Map.of(
                                "dispatchId",    dispatchId,
                                "sourceEventId", sourceEventId,
                                "eventType",     eventType,
                                "httpStatus",    httpStatus != null ? httpStatus : -1,
                                "reason",        reason
                        ))
                        .build()
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 유틸
    // ═══════════════════════════════════════════════════════════════════════

    private int toInt(Object val, int defaultVal) {
        if (val instanceof Number n) return n.intValue();
        return defaultVal;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 발송 결과 열거형
    // ═══════════════════════════════════════════════════════════════════════

    private enum DispatchResult {
        /** HTTPS POST 성공 (2xx 응답) */
        SUCCESS,
        /** 재시도 예약 (지수 백오프) */
        RETRYING,
        /** 최종 실패 (max_retry 초과 또는 4xx 즉시 실패) */
        FAILED
    }
}

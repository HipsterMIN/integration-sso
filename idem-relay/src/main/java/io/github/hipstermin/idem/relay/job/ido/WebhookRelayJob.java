package io.github.hipstermin.idem.relay.job.ido;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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

/**
 * ido.webhook_dispatch_outbox HTTPS POST 릴레이 배치 Job
 *
 * <h2>처리 대상</h2>
 * ido.webhook_dispatch_outbox 테이블의 PENDING 레코드 → 기관 HTTPS POST.
 * 기존 {@code WebhookDispatchOutboxRelay}를 대체.
 *
 * <h2>ShedLock 설정</h2>
 * <pre>
 * lockAtMostFor  = "10s": 50건 × 최대 200ms(HTTP connect 3s + read 8s 중 짧은 것) ≈ 10s
 * lockAtLeastFor = "400ms": 배치 주기 500ms 기준 한 주기를 거의 채움
 * </pre>
 *
 * <h2>보안</h2>
 * X-Webhook-Signature: HMAC-SHA256({timestamp}.{payload}) — 기관이 검증.
 * X-Webhook-Timestamp: Unix epoch 초 — 재사용 공격 방지 (±5분 이내 유효).
 *
 * <h2>지수 백오프</h2>
 * 2^(retryCount+1) 초 (2s → 4s → 8s → FAILED).
 * 기존 WebhookDispatchOutboxRelay와 동일한 정책.
 */
@Slf4j
@Component
public class WebhookRelayJob {

    private static final String SOURCE_SYSTEM = "onepass-batch";
    private static final long   BACKOFF_BASE  = 2L;   // 2^(retry+1) 초

    private final JdbcTemplate idoJdbcTemplate;
    private final RestTemplate webhookRestTemplate;

    private final Counter successCounter;
    private final Counter retryCounter;
    private final Counter failureCounter;

    @Value("${batch.relay.webhook.batch-size:50}")
    private int batchSize;

    @Value("${batch.relay.webhook.max-retry:3}")
    private int defaultMaxRetry;

    @Value("${batch.relay.webhook.enabled:true}")
    private boolean enabled;

    @Value("${batch.relay.webhook.signing-secret:}")
    private String defaultSigningSecret;

    @Value("${ido.platform-version:1.0}")
    private String platformVersion;

    public WebhookRelayJob(
            @Qualifier("idoJdbcTemplate") JdbcTemplate idoJdbcTemplate,
            @Qualifier("webhookRestTemplate") RestTemplate webhookRestTemplate,
            MeterRegistry meterRegistry) {
        this.idoJdbcTemplate     = idoJdbcTemplate;
        this.webhookRestTemplate = webhookRestTemplate;
        this.successCounter      = meterRegistry.counter("batch.relay.webhook.success");
        this.retryCounter        = meterRegistry.counter("batch.relay.webhook.retry");
        this.failureCounter      = meterRegistry.counter("batch.relay.webhook.failure");
    }

    @Scheduled(fixedDelayString = "${batch.relay.webhook.interval-ms:500}")
    @SchedulerLock(
            name           = "ido-webhook-relay",
            lockAtMostFor  = "${batch.relay.webhook.lock-at-most:10s}",
            lockAtLeastFor = "${batch.relay.webhook.lock-at-least:400ms}"
    )
    public void relay() {
        if (!enabled) return;

        List<WebhookRow> pending = fetchPending();
        if (pending.isEmpty()) return;

        log.debug("[WebhookRelayJob] PENDING {} 건 발송 시작", pending.size());

        int dispatched = 0;
        int retrying   = 0;
        int failed     = 0;

        for (WebhookRow row : pending) {
            DispatchResult result = dispatch(row);
            switch (result) {
                case SUCCESS  -> dispatched++;
                case RETRYING -> retrying++;
                case FAILED   -> failed++;
            }
        }

        successCounter.increment(dispatched);
        retryCounter.increment(retrying);
        failureCounter.increment(failed);

        if (dispatched + retrying + failed > 0) {
            log.info("[WebhookRelayJob] 완료: success={} retry={} failed={}", dispatched, retrying, failed);
        }
    }

    private DispatchResult dispatch(WebhookRow row) {
        try {
            String timestamp  = String.valueOf(Instant.now().getEpochSecond());
            String signTarget = timestamp + "." + row.payload();
            String signature  = computeSignature(signTarget, row.signingSecret());

            HttpHeaders headers = buildHeaders(row.correlationId(), signature, timestamp,
                    row.payload().length());

            ResponseEntity<String> response = webhookRestTemplate.exchange(
                    row.endpointUrl(),
                    HttpMethod.POST,
                    new HttpEntity<>(row.payload(), headers),
                    String.class
            );

            int status = response.getStatusCode().value();
            if (status >= 200 && status <= 299) {
                markDispatched(row.dispatchId(), status);
                log.info("[WebhookRelayJob] 발송 성공: dispatchId={} agency={} status={}",
                        row.dispatchId(), row.agencyCode(), status);
                return DispatchResult.SUCCESS;
            }
            return handleRetryOrFail(row, status, "HTTP " + status);

        } catch (HttpClientErrorException e) {
            int code = e.getStatusCode().value();
            if (code == 404 || code == 410) {
                markFailed(row.dispatchId(), code, "endpoint not found: " + code);
                return DispatchResult.FAILED;
            }
            return handleRetryOrFail(row, code, "4xx: " + truncate(e.getMessage(), 200));
        } catch (HttpServerErrorException e) {
            return handleRetryOrFail(row, e.getStatusCode().value(), "5xx: " + truncate(e.getMessage(), 200));
        } catch (ResourceAccessException e) {
            return handleRetryOrFail(row, null, "network: " + truncate(e.getMessage(), 200));
        } catch (Exception e) {
            log.error("[WebhookRelayJob] 예외: dispatchId={} error={}", row.dispatchId(), e.getMessage());
            return handleRetryOrFail(row, null, "error: " + truncate(e.getMessage(), 200));
        }
    }

    private DispatchResult handleRetryOrFail(WebhookRow row, Integer httpStatus, String error) {
        if (row.retryCount() >= row.maxRetry()) {
            markFailed(row.dispatchId(), httpStatus, error);
            log.error("[WebhookRelayJob] MAX_RETRY → FAILED: dispatchId={} retry={}/{}",
                    row.dispatchId(), row.retryCount(), row.maxRetry());
            return DispatchResult.FAILED;
        }
        long backoffSec = (long) Math.pow(BACKOFF_BASE, row.retryCount() + 1);
        scheduleRetry(row.dispatchId(), row.retryCount() + 1, httpStatus, error, backoffSec);
        log.warn("[WebhookRelayJob] 재시도 예약: dispatchId={} backoff={}s retry={}/{}",
                row.dispatchId(), backoffSec, row.retryCount() + 1, row.maxRetry());
        return DispatchResult.RETRYING;
    }

    // ─────────────────────────────────────────────────────────────────────
    // DB 조작
    // ─────────────────────────────────────────────────────────────────────

    private List<WebhookRow> fetchPending() {
        List<WebhookRow> rows = new ArrayList<>();
        try {
            idoJdbcTemplate.query("""
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
                    """, (ResultSet rs) -> {
                rows.add(new WebhookRow(
                        rs.getString("dispatch_id"),
                        rs.getString("agency_code"),
                        rs.getString("endpoint_url"),
                        rs.getString("payload"),
                        rs.getString("correlation_id"),
                        rs.getString("source_event_id"),
                        rs.getString("source_event_type"),
                        rs.getString("signing_secret_hash"),
                        rs.getInt("retry_count"),
                        rs.getInt("max_retry") == 0 ? defaultMaxRetry : rs.getInt("max_retry")
                ));
            }, batchSize);
        } catch (Exception e) {
            log.warn("[WebhookRelayJob] PENDING 조회 실패: {}", e.getMessage());
        }
        return rows;
    }

    private void markDispatched(String dispatchId, int status) {
        try {
            idoJdbcTemplate.update("""
                    UPDATE ido.webhook_dispatch_outbox
                    SET status = 'DISPATCHED', last_http_status = ?, dispatched_at = NOW()
                    WHERE dispatch_id = ?
                    """, status, dispatchId);
        } catch (Exception e) {
            log.error("[WebhookRelayJob] DISPATCHED 갱신 실패: dispatchId={}", dispatchId);
        }
    }

    private void markFailed(String dispatchId, Integer status, String error) {
        try {
            idoJdbcTemplate.update("""
                    UPDATE ido.webhook_dispatch_outbox
                    SET status = 'FAILED', last_http_status = ?, last_error_message = ?
                    WHERE dispatch_id = ?
                    """, status, truncate(error, 500), dispatchId);
        } catch (Exception e) {
            log.error("[WebhookRelayJob] FAILED 갱신 실패: dispatchId={}", dispatchId);
        }
    }

    private void scheduleRetry(String dispatchId, int nextRetryCount,
                                Integer status, String error, long backoffSec) {
        try {
            idoJdbcTemplate.update("""
                    UPDATE ido.webhook_dispatch_outbox
                    SET retry_count = ?, last_http_status = ?,
                        last_error_message = ?,
                        next_retry_at = NOW() + (? || ' seconds')::interval
                    WHERE dispatch_id = ?
                    """, nextRetryCount, status, truncate(error, 500), backoffSec, dispatchId);
        } catch (Exception e) {
            log.error("[WebhookRelayJob] 재시도 예약 실패: dispatchId={}", dispatchId);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 서명 / 헤더
    // ─────────────────────────────────────────────────────────────────────

    private String computeSignature(String signTarget, String secret) {
        String key = (secret != null && !secret.isBlank()) ? secret : defaultSigningSecret;
        if (key == null || key.isBlank()) {
            log.warn("[WebhookRelayJob] Webhook 서명 비밀키 미설정 — 서명 없이 발송");
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(
                    mac.doFinal(signTarget.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("[WebhookRelayJob] HMAC 계산 실패: {}", e.getMessage());
            return "";
        }
    }

    private HttpHeaders buildHeaders(String correlationId, String signature,
                                      String timestamp, int contentLength) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Webhook-Signature", signature);
        headers.set("X-Webhook-Timestamp", timestamp);
        headers.set("X-Source-System",     SOURCE_SYSTEM);
        headers.set("X-Platform-Version",  platformVersion);
        if (correlationId != null && !correlationId.isBlank()) {
            headers.set("X-Correlation-Id", correlationId);
        }
        headers.setContentLength(contentLength);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));
        return headers;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Inner types
    // ─────────────────────────────────────────────────────────────────────

    private record WebhookRow(
            String dispatchId, String agencyCode, String endpointUrl,
            String payload, String correlationId, String sourceEventId,
            String sourceEventType, String signingSecret,
            int retryCount, int maxRetry
    ) {}

    private enum DispatchResult { SUCCESS, RETRYING, FAILED }
}

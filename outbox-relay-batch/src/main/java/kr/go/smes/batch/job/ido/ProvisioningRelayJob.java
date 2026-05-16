package kr.go.smes.batch.job.ido;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * ido.provisioning_outbox HTTP POST 릴레이 배치 Job
 *
 * <h2>처리 대상</h2>
 * ido.provisioning_outbox 테이블의 PENDING 레코드 → 기관 HTTP POST 재시도.
 * 기존 {@code ProvisioningOutboxRelay}를 대체.
 *
 * <h2>ShedLock 설정</h2>
 * <pre>
 * lockAtMostFor  = "120s": 50건 × 최대 2s HTTP = 100s + 여유 20s
 * lockAtLeastFor = "25s" : 배치 주기 30s 기준 거의 한 주기를 채움
 * </pre>
 *
 * <h2>기관별 인증 방식</h2>
 * <ul>
 *   <li>API_KEY: {@code X-Api-Key} 헤더</li>
 *   <li>HMAC: {@code X-Signature} + {@code X-Timestamp} 헤더</li>
 *   <li>MTLS: 클라이언트 인증서 (별도 RestTemplate 빈 필요 — 현재 TODO)</li>
 *   <li>NONE: 추가 헤더 없음</li>
 * </ul>
 *
 * <h2>지수 백오프</h2>
 * DB의 next_retry_at 컬럼 기반: 1분 → 5분 → 30분 (provisioning_outbox 기존 정책).
 * 실제 백오프 계산은 ProvisioningOutboxRepositoryImpl과 동일한 로직 인라인 적용.
 *
 * <h2>자격증명 조회</h2>
 * 기관 자격증명은 ido.agency_credential_config 또는 환경변수에서 조회.
 * 배치 서비스는 ido DataSource를 통해 DB에서 직접 조회 (AgencyCredentialStore 인라인).
 *
 * <h2>TODO — MTLS 지원</h2>
 * 현재 MTLS 기관은 일반 RestTemplate으로 처리 시 TLS 핸드셰이크 실패.
 * 향후 mTLS 클라이언트 인증서 장착 RestTemplate Bean 추가 필요.
 * (ido 서비스의 mtlsProvisioningRestTemplate 설정 참조)
 */
@Slf4j
@Component
public class ProvisioningRelayJob {

    private static final String ENDPOINT_TYPE_PROVISIONING = "PROVISIONING";
    private static final int[] BACKOFF_MINUTES = {1, 5, 30};  // provisioning 전용 백오프

    private final JdbcTemplate idoJdbcTemplate;
    private final RestTemplate restTemplate;

    private final Counter successCounter;
    private final Counter retryCounter;
    private final Counter deadLetterCounter;

    @Value("${batch.relay.provisioning.batch-size:50}")
    private int batchSize;

    @Value("${batch.relay.provisioning.enabled:true}")
    private boolean enabled;

    public ProvisioningRelayJob(
            @Qualifier("idoJdbcTemplate") JdbcTemplate idoJdbcTemplate,
            @Qualifier("provisioningRestTemplate") RestTemplate restTemplate,
            MeterRegistry meterRegistry) {
        this.idoJdbcTemplate   = idoJdbcTemplate;
        this.restTemplate      = restTemplate;
        this.successCounter    = meterRegistry.counter("batch.relay.provisioning.success");
        this.retryCounter      = meterRegistry.counter("batch.relay.provisioning.retry");
        this.deadLetterCounter = meterRegistry.counter("batch.relay.provisioning.dead_letter");
    }

    /**
     * PENDING 프로비저닝 레코드 기관 HTTP POST 재시도
     *
     * <p>기본 30초 주기 — provisioning은 Kafka처럼 실시간이 아니므로
     * 30초 간격으로 충분 (지수 백오프 최소 1분 단위이므로 30초보다 짧을 필요 없음).
     */
    @Scheduled(fixedDelayString = "${batch.relay.provisioning.interval-ms:30000}")
    @SchedulerLock(
            name           = "ido-provisioning-relay",
            lockAtMostFor  = "${batch.relay.provisioning.lock-at-most:120s}",
            lockAtLeastFor = "${batch.relay.provisioning.lock-at-least:25s}"
    )
    @Transactional(transactionManager = "idoTransactionManager")
    public void relay() {
        if (!enabled) return;

        List<ProvisioningRow> pending = fetchPending();
        if (pending.isEmpty()) return;

        log.info("[ProvisioningRelayJob] PENDING {} 건 재시도 시작", pending.size());

        int success = 0;
        int retry   = 0;
        int dead    = 0;

        for (ProvisioningRow row : pending) {
            RelayResult result = retryRecord(row);
            switch (result) {
                case SUCCESS     -> success++;
                case RETRY       -> retry++;
                case DEAD_LETTER -> dead++;
            }
        }

        successCounter.increment(success);
        retryCounter.increment(retry);
        deadLetterCounter.increment(dead);

        log.info("[ProvisioningRelayJob] 완료: success={} retry={} dead={}", success, retry, dead);
    }

    private RelayResult retryRecord(ProvisioningRow row) {
        // ① 기관 엔드포인트 조회
        EndpointInfo endpoint = findEndpoint(row.agencyCode());
        if (endpoint == null) {
            return escalateOrDead(row, "Endpoint not found: " + row.agencyCode());
        }

        // ② HTTP POST
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Idempotency-Key", row.idempotencyKey());
            headers.set("X-Provisioning-Source", "onepass-batch");
            headers.set("X-Retry-Count", String.valueOf(row.retryCount()));
            if (row.correlationId() != null) headers.set("X-Correlation-ID", row.correlationId());

            addAuthHeader(headers, endpoint, row.idempotencyKey());

            ResponseEntity<String> response = restTemplate.postForEntity(
                    endpoint.url(),
                    new HttpEntity<>(row.payloadJson(), headers),
                    String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                markCompleted(row.id());
                log.info("[ProvisioningRelayJob] 성공: agency={} id={}", row.agencyCode(), row.id());
                return RelayResult.SUCCESS;
            }
            return escalateOrDead(row, "HTTP " + response.getStatusCode().value());

        } catch (HttpClientErrorException e) {
            int code = e.getStatusCode().value();
            if (code == 404 || code == 410) {
                markDeadLetter(row.id(), "endpoint not found: " + code);
                return RelayResult.DEAD_LETTER;
            }
            return escalateOrDead(row, "4xx: " + truncate(e.getMessage(), 200));
        } catch (HttpServerErrorException e) {
            return escalateOrDead(row, "5xx: " + truncate(e.getMessage(), 200));
        } catch (ResourceAccessException e) {
            return escalateOrDead(row, "network: " + truncate(e.getMessage(), 200));
        } catch (Exception e) {
            log.error("[ProvisioningRelayJob] 예외: agency={} id={} error={}", row.agencyCode(), row.id(), e.getMessage());
            return escalateOrDead(row, "error: " + truncate(e.getMessage(), 200));
        }
    }

    private RelayResult escalateOrDead(ProvisioningRow row, String error) {
        int nextRetry = row.retryCount() + 1;
        if (nextRetry >= row.maxRetry()) {
            markDeadLetter(row.id(), error);
            log.error("[ProvisioningRelayJob] DEAD_LETTER: agency={} id={} retry={}/{} error={}",
                    row.agencyCode(), row.id(), nextRetry, row.maxRetry(), error);
            return RelayResult.DEAD_LETTER;
        }
        scheduleRetry(row.id(), error);
        return RelayResult.RETRY;
    }

    // ─────────────────────────────────────────────────────────────────────
    // DB 조작
    // ─────────────────────────────────────────────────────────────────────

    private List<ProvisioningRow> fetchPending() {
        List<ProvisioningRow> rows = new ArrayList<>();
        try {
            idoJdbcTemplate.query("""
                    SELECT id, qim_user_id, agency_code, event_type,
                           payload_json::text, idempotency_key,
                           status, retry_count, max_retry,
                           correlation_id
                    FROM ido.provisioning_outbox
                    WHERE status = 'PENDING'
                      AND (next_retry_at IS NULL OR next_retry_at <= NOW())
                    ORDER BY created_at ASC
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """, (ResultSet rs) -> {
                rows.add(new ProvisioningRow(
                        rs.getString("id"),
                        rs.getString("qim_user_id"),
                        rs.getString("agency_code"),
                        rs.getString("event_type"),
                        rs.getString("payload_json"),
                        rs.getString("idempotency_key"),
                        rs.getString("status"),
                        rs.getInt("retry_count"),
                        rs.getInt("max_retry"),
                        rs.getString("correlation_id")
                ));
            }, batchSize);
        } catch (Exception e) {
            log.warn("[ProvisioningRelayJob] PENDING 조회 실패: {}", e.getMessage());
        }
        return rows;
    }

    private void markCompleted(String id) {
        try {
            idoJdbcTemplate.update("""
                    UPDATE ido.provisioning_outbox
                    SET status = 'COMPLETED', completed_at = NOW(), last_attempted_at = NOW()
                    WHERE id = ?
                    """, id);
        } catch (Exception e) {
            log.error("[ProvisioningRelayJob] COMPLETED 갱신 실패: id={}", id);
        }
    }

    private void markDeadLetter(String id, String error) {
        try {
            idoJdbcTemplate.update("""
                    UPDATE ido.provisioning_outbox
                    SET status = 'DEAD_LETTER',
                        error_message = ?,
                        retry_count = retry_count + 1,
                        last_attempted_at = NOW()
                    WHERE id = ?
                    """, truncate(error, 500), id);
        } catch (Exception e) {
            log.error("[ProvisioningRelayJob] DEAD_LETTER 갱신 실패: id={}", id);
        }
    }

    private void scheduleRetry(String id, String error) {
        try {
            // 지수 백오프: 현재 retry_count 기반으로 BACKOFF_MINUTES 인덱스 참조
            // DB에서 retry_count를 직접 읽어 계산
            idoJdbcTemplate.update("""
                    UPDATE ido.provisioning_outbox
                    SET retry_count = retry_count + 1,
                        error_message = ?,
                        last_attempted_at = NOW(),
                        next_retry_at = CASE
                            WHEN retry_count = 0 THEN NOW() + INTERVAL '1 minute'
                            WHEN retry_count = 1 THEN NOW() + INTERVAL '5 minutes'
                            ELSE NOW() + INTERVAL '30 minutes'
                        END
                    WHERE id = ?
                    """, truncate(error, 500), id);
        } catch (Exception e) {
            log.error("[ProvisioningRelayJob] 재시도 예약 실패: id={}", id);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 기관 엔드포인트 / 인증
    // ─────────────────────────────────────────────────────────────────────

    private EndpointInfo findEndpoint(String agencyCode) {
        try {
            return idoJdbcTemplate.queryForObject("""
                    SELECT r.endpoint_url, r.auth_type, r.auth_credential_ref
                    FROM ido.agency_endpoint_registry r
                    WHERE r.agency_code = ?
                      AND r.endpoint_type = ?
                      AND r.active = TRUE
                    LIMIT 1
                    """, (rs, rowNum) -> new EndpointInfo(
                    rs.getString("endpoint_url"),
                    rs.getString("auth_type"),
                    rs.getString("auth_credential_ref"),
                    agencyCode
            ), agencyCode, ENDPOINT_TYPE_PROVISIONING);
        } catch (Exception e) {
            log.warn("[ProvisioningRelayJob] 엔드포인트 조회 실패: agency={} error={}", agencyCode, e.getMessage());
            return null;
        }
    }

    private void addAuthHeader(HttpHeaders headers, EndpointInfo endpoint, String idempotencyKey) {
        if (endpoint.authCredentialRef() == null || endpoint.authCredentialRef().isBlank()) return;

        switch (endpoint.authType()) {
            case "API_KEY" -> {
                String apiKey = findSecret(endpoint.authCredentialRef());
                if (apiKey != null && !apiKey.isBlank()) headers.set("X-Api-Key", apiKey);
            }
            case "HMAC" -> {
                String secret = findSecret(endpoint.authCredentialRef());
                if (secret != null && !secret.isBlank()) {
                    try {
                        long epochSec = Instant.now().getEpochSecond();
                        String sig    = computeHmac(idempotencyKey + ":" + epochSec, secret);
                        headers.set("X-Signature", sig);
                        headers.set("X-Timestamp",  String.valueOf(epochSec));
                    } catch (Exception e) {
                        log.error("[ProvisioningRelayJob] HMAC 서명 실패: agency={}", endpoint.agencyCode());
                    }
                }
            }
            case "MTLS" -> {
                // TODO: mTLS RestTemplate 적용 (ido.config.ProvisioningRestTemplateConfig 참조)
                log.debug("[ProvisioningRelayJob] MTLS 기관 — 현재 일반 RestTemplate 사용");
            }
        }
    }

    /**
     * 기관 자격증명 조회 — ido.agency_credential_config 또는 환경변수
     *
     * <p>AgencyCredentialStore와 동일한 로직:
     * 환경변수명 = ref를 대문자로 변환 후 특수문자를 '_'로 교체.
     */
    private String findSecret(String ref) {
        if (ref == null || ref.isBlank()) return null;
        // 환경변수에서 조회 (K8s Secret mount)
        String envVarName = ref.toUpperCase().replaceAll("[^A-Z0-9]", "_");
        String secret     = System.getenv(envVarName);
        if (secret != null && !secret.isBlank()) return secret;

        // DB fallback (agency_credential_config 테이블)
        try {
            return idoJdbcTemplate.queryForObject("""
                    SELECT credential_value FROM ido.agency_credential_config
                    WHERE credential_ref = ? AND active = TRUE LIMIT 1
                    """, String.class, ref);
        } catch (Exception e) {
            log.warn("[ProvisioningRelayJob] 자격증명 조회 실패: ref={}", ref);
            return null;
        }
    }

    private String computeHmac(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Inner types
    // ─────────────────────────────────────────────────────────────────────

    private record ProvisioningRow(
            String id, String qimUserId, String agencyCode, String eventType,
            String payloadJson, String idempotencyKey, String status,
            int retryCount, int maxRetry, String correlationId
    ) {}

    private record EndpointInfo(
            String url, String authType, String authCredentialRef, String agencyCode
    ) {}

    private enum RelayResult { SUCCESS, RETRY, DEAD_LETTER }
}

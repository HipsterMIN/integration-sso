package io.github.hipstermin.idem.relay.job.ido;

import io.github.hipstermin.idem.relay.alert.DeadLetterNotifier;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

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
 * <h2>기관별 인증 방식 및 RestTemplate 선택</h2>
 * <ul>
 *   <li>API_KEY: {@code X-Api-Key} 헤더 → {@code provisioningRestTemplate} (일반 TLS)</li>
 *   <li>HMAC:    {@code X-Signature} + {@code X-Timestamp} 헤더 → {@code provisioningRestTemplate}</li>
 *   <li>MTLS:    클라이언트 인증서 TLS 핸드셰이크 → {@code mtlsProvisioningRestTemplate}</li>
 *   <li>NONE:    추가 헤더 없음 → {@code provisioningRestTemplate}</li>
 * </ul>
 *
 * <h2>mTLS 동작 원리</h2>
 * {@code mtlsProvisioningRestTemplate}은 PKCS12 KeyStore가 장착된 SSLContext로
 * {@link BatchRestTemplateConfig}에서 초기화됨. MTLS 기관 요청 시 TLS 핸드셰이크 중
 * 서버 → 클라이언트 인증서 요청에 자동 응답.
 *
 * <h2>지수 백오프</h2>
 * DB의 next_retry_at 컬럼 기반: 1분 → 5분 → 30분 (provisioning_outbox 기존 정책).
 * 실제 백오프 계산은 ProvisioningOutboxRepositoryImpl과 동일한 로직 인라인 적용.
 *
 * <h2>자격증명 조회</h2>
 * 기관 자격증명은 환경변수 우선 → DB(ido.agency_credential_config) Fallback.
 * 환경변수명 변환 규칙: authCredentialRef 비알파벳/숫자 → '_' 치환 후 대문자화.
 * (예: {@code "secrets/agency/AGENCY_001/api-key"} → {@code SECRETS_AGENCY_AGENCY_001_API_KEY})
 */
@Slf4j
@Component
public class ProvisioningRelayJob {

    private static final String ENDPOINT_TYPE_PROVISIONING = "PROVISIONING";

    /**
     * 프로비저닝 백오프 정책 (분 단위)
     * retry_count=0 → 1분, retry_count=1 → 5분, retry_count≥2 → 30분
     */
    private static final int[] BACKOFF_MINUTES = {1, 5, 30};

    private final JdbcTemplate idoJdbcTemplate;

    /** API_KEY / HMAC / NONE 기관 전용 (일반 TLS) */
    private final RestTemplate restTemplate;

    /** MTLS 기관 전용 — 클라이언트 인증서(PKCS12) 장착 SSLContext */
    private final RestTemplate mtlsRestTemplate;

    private final Counter successCounter;
    private final Counter retryCounter;
    private final Counter deadLetterCounter;

    /** DEAD_LETTER 전환 시 Slack/PagerDuty 알림 발송 */
    private final DeadLetterNotifier deadLetterNotifier;

    @Value("${batch.relay.provisioning.batch-size:50}")
    private int batchSize;

    @Value("${batch.relay.provisioning.enabled:true}")
    private boolean enabled;

    public ProvisioningRelayJob(
            @Qualifier("idoJdbcTemplate")              JdbcTemplate idoJdbcTemplate,
            @Qualifier("provisioningRestTemplate")     RestTemplate restTemplate,
            @Qualifier("mtlsProvisioningRestTemplate") RestTemplate mtlsRestTemplate,
            MeterRegistry meterRegistry,
            DeadLetterNotifier deadLetterNotifier) {
        this.idoJdbcTemplate     = idoJdbcTemplate;
        this.restTemplate        = restTemplate;
        this.mtlsRestTemplate    = mtlsRestTemplate;
        this.successCounter      = meterRegistry.counter("batch.relay.provisioning.success");
        this.retryCounter        = meterRegistry.counter("batch.relay.provisioning.retry");
        this.deadLetterCounter   = meterRegistry.counter("batch.relay.provisioning.dead_letter");
        this.deadLetterNotifier  = deadLetterNotifier;
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

        log.info("[ProvisioningRelayJob] PENDING {} 건 처리 시작", pending.size());

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

    // ─────────────────────────────────────────────────────────────────────────
    // 릴레이 핵심 로직
    // ─────────────────────────────────────────────────────────────────────────

    private RelayResult retryRecord(ProvisioningRow row) {
        // ① 기관 엔드포인트 조회
        EndpointInfo endpoint = findEndpoint(row.agencyCode());
        if (endpoint == null) {
            return escalateOrDead(row, "endpoint_not_found:" + row.agencyCode());
        }

        // ② 인증 방식에 따른 RestTemplate 선택
        RestTemplate selectedTemplate = selectRestTemplate(endpoint);

        // ③ HTTP 헤더 구성
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Idempotency-Key",       row.idempotencyKey());
        headers.set("X-Provisioning-Source",   "onepass-batch");
        headers.set("X-Retry-Count",           String.valueOf(row.retryCount()));
        if (row.correlationId() != null) {
            headers.set("X-Correlation-ID", row.correlationId());
        }

        // ④ 인증 헤더 추가 (MTLS는 헤더 불필요 — TLS 핸드셰이크로 처리)
        if (!addAuthHeader(headers, endpoint, row.idempotencyKey())) {
            // 자격증명 조회 실패 — 재시도 예약 (dead letter 아님, 운영자가 K8s Secret 등록 후 재시도)
            return escalateOrDead(row, "credential_not_found:ref=" + endpoint.authCredentialRef());
        }

        // ⑤ HTTP POST
        try {
            ResponseEntity<String> response = selectedTemplate.postForEntity(
                    endpoint.url(),
                    new HttpEntity<>(row.payloadJson(), headers),
                    String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                markCompleted(row.id());
                log.info("[ProvisioningRelayJob] ✅ 성공: agency={} id={} authType={}",
                        row.agencyCode(), row.id(), endpoint.authType());
                return RelayResult.SUCCESS;
            }

            return escalateOrDead(row, "http_" + response.getStatusCode().value());

        } catch (HttpClientErrorException e) {
            int code = e.getStatusCode().value();
            if (code == 404 || code == 410) {
                // 엔드포인트 영구 소멸 — dead letter 즉시 처리
                markDeadLetter(row.id(), "endpoint_gone:" + code);
                log.error("[ProvisioningRelayJob] ☠️ DEAD_LETTER(endpoint gone): agency={} id={} status={}",
                        row.agencyCode(), row.id(), code);
                return RelayResult.DEAD_LETTER;
            }
            return escalateOrDead(row, "4xx:" + code + ":" + truncate(e.getMessage(), 150));

        } catch (HttpServerErrorException e) {
            return escalateOrDead(row, "5xx:" + e.getStatusCode().value() + ":" + truncate(e.getMessage(), 150));

        } catch (ResourceAccessException e) {
            // 연결 거부, 타임아웃, TLS 핸드셰이크 실패 등
            String msg = truncate(e.getMessage(), 200);
            log.warn("[ProvisioningRelayJob] 네트워크 오류: agency={} id={} error={}",
                    row.agencyCode(), row.id(), msg);
            return escalateOrDead(row, "network:" + msg);

        } catch (Exception e) {
            log.error("[ProvisioningRelayJob] 예기치 않은 오류: agency={} id={} error={}",
                    row.agencyCode(), row.id(), e.getMessage(), e);
            return escalateOrDead(row, "error:" + truncate(e.getMessage(), 150));
        }
    }

    /**
     * 재시도 횟수에 따라 DEAD_LETTER 전환 또는 재시도 예약
     */
    private RelayResult escalateOrDead(ProvisioningRow row, String error) {
        int nextRetry = row.retryCount() + 1;
        if (nextRetry >= row.maxRetry()) {
            markDeadLetter(row.id(), error);
            log.error("[ProvisioningRelayJob] ☠️ DEAD_LETTER: agency={} id={} retry={}/{} error={}",
                    row.agencyCode(), row.id(), nextRetry, row.maxRetry(), error);
            // D-06: DEAD_LETTER 전환 시 Slack/PagerDuty 알림 (비치명적 — 실패해도 흐름 유지)
            deadLetterNotifier.notifyDeadLetter(
                    row.agencyCode(), row.id(), error, nextRetry);
            return RelayResult.DEAD_LETTER;
        }
        scheduleRetry(row.id(), error, row.retryCount());
        log.warn("[ProvisioningRelayJob] ⚠️ RETRY 예약: agency={} id={} retry={}/{} error={}",
                row.agencyCode(), row.id(), nextRetry, row.maxRetry(), error);
        return RelayResult.RETRY;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 인증 / RestTemplate 선택
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * authType에 따른 RestTemplate 선택
     *
     * <ul>
     *   <li>MTLS → {@code mtlsProvisioningRestTemplate} (클라이언트 인증서 TLS)</li>
     *   <li>그 외 → {@code provisioningRestTemplate} (일반 TLS)</li>
     * </ul>
     */
    private RestTemplate selectRestTemplate(EndpointInfo endpoint) {
        boolean isMtls = "MTLS".equalsIgnoreCase(endpoint.authType());
        if (isMtls) {
            log.debug("[ProvisioningRelayJob] MTLS RestTemplate 선택: agency={}", endpoint.agencyCode());
        }
        return isMtls ? mtlsRestTemplate : restTemplate;
    }

    /**
     * 인증 방식별 HTTP 헤더 추가
     *
     * @return true: 성공(헤더 추가 완료 또는 MTLS/NONE은 헤더 불필요), false: 자격증명 조회 실패
     */
    private boolean addAuthHeader(HttpHeaders headers, EndpointInfo endpoint, String idempotencyKey) {
        String ref = endpoint.authCredentialRef();

        switch (endpoint.authType() == null ? "NONE" : endpoint.authType().toUpperCase()) {

            case "API_KEY" -> {
                String apiKey = findSecret(ref);
                if (apiKey == null || apiKey.isBlank()) {
                    log.error("[ProvisioningRelayJob] ❌ API_KEY 자격증명 미등록: agency={} ref={} " +
                              "→ K8s Secret에 {} 키 등록 필요.",
                            endpoint.agencyCode(), ref,
                            ref == null ? "N/A" : ref.toUpperCase().replaceAll("[^A-Z0-9]", "_"));
                    return false;
                }
                headers.set("X-Api-Key", apiKey);
            }

            case "HMAC" -> {
                String secret = findSecret(ref);
                if (secret == null || secret.isBlank()) {
                    log.error("[ProvisioningRelayJob] ❌ HMAC 자격증명 미등록: agency={} ref={}",
                            endpoint.agencyCode(), ref);
                    return false;
                }
                try {
                    long   epochSec = Instant.now().getEpochSecond();
                    String sig      = computeHmac(idempotencyKey + ":" + epochSec, secret);
                    headers.set("X-Signature", sig);
                    headers.set("X-Timestamp",  String.valueOf(epochSec));
                } catch (Exception e) {
                    log.error("[ProvisioningRelayJob] ❌ HMAC 서명 계산 실패: agency={} error={}",
                            endpoint.agencyCode(), e.getMessage());
                    return false;
                }
            }

            case "MTLS" -> {
                // mTLS는 TLS 핸드셰이크에서 클라이언트 인증서로 인증 — 별도 헤더 불필요
                // RestTemplate 선택은 selectRestTemplate()에서 처리됨
                log.debug("[ProvisioningRelayJob] MTLS 기관 — 인증 헤더 없음 (TLS 핸드셰이크): agency={}",
                        endpoint.agencyCode());
            }

            case "NONE" -> { /* 인증 없음 */ }

            default -> log.warn("[ProvisioningRelayJob] 알 수 없는 authType: {} agency={}",
                    endpoint.authType(), endpoint.agencyCode());
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DB 조작
    // ─────────────────────────────────────────────────────────────────────────

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
            log.error("[ProvisioningRelayJob] PENDING 조회 실패: {}", e.getMessage(), e);
        }
        return rows;
    }

    private void markCompleted(String id) {
        try {
            idoJdbcTemplate.update("""
                    UPDATE ido.provisioning_outbox
                    SET status           = 'COMPLETED',
                        completed_at     = NOW(),
                        last_attempted_at = NOW()
                    WHERE id = ?
                    """, id);
        } catch (Exception e) {
            log.error("[ProvisioningRelayJob] COMPLETED 갱신 실패: id={} error={}", id, e.getMessage());
        }
    }

    private void markDeadLetter(String id, String error) {
        try {
            idoJdbcTemplate.update("""
                    UPDATE ido.provisioning_outbox
                    SET status            = 'DEAD_LETTER',
                        error_message     = ?,
                        retry_count       = retry_count + 1,
                        last_attempted_at = NOW()
                    WHERE id = ?
                    """, truncate(error, 500), id);
        } catch (Exception e) {
            log.error("[ProvisioningRelayJob] DEAD_LETTER 갱신 실패: id={} error={}", id, e.getMessage());
        }
    }

    /**
     * 재시도 예약 — provisioning 전용 지수 백오프
     *
     * <pre>
     * retry_count=0 → next_retry_at = NOW() + 1분
     * retry_count=1 → next_retry_at = NOW() + 5분
     * retry_count≥2 → next_retry_at = NOW() + 30분
     * </pre>
     *
     * @param id         레코드 PK
     * @param error      에러 메시지
     * @param retryCount 현재 retry_count (DB 갱신 전)
     */
    private void scheduleRetry(String id, String error, int retryCount) {
        try {
            int backoffMinutes = BACKOFF_MINUTES[Math.min(retryCount, BACKOFF_MINUTES.length - 1)];
            idoJdbcTemplate.update("""
                    UPDATE ido.provisioning_outbox
                    SET retry_count       = retry_count + 1,
                        error_message     = ?,
                        last_attempted_at = NOW(),
                        next_retry_at     = NOW() + (? * INTERVAL '1 minute')
                    WHERE id = ?
                    """, truncate(error, 500), backoffMinutes, id);
        } catch (Exception e) {
            log.error("[ProvisioningRelayJob] 재시도 예약 실패: id={} error={}", id, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 기관 엔드포인트 / 자격증명 조회
    // ─────────────────────────────────────────────────────────────────────────

    private EndpointInfo findEndpoint(String agencyCode) {
        try {
            return idoJdbcTemplate.queryForObject("""
                    SELECT r.endpoint_url, r.auth_type, r.auth_credential_ref
                    FROM ido.agency_endpoint_registry r
                    WHERE r.agency_code    = ?
                      AND r.endpoint_type = ?
                      AND r.active        = TRUE
                    LIMIT 1
                    """, (rs, rowNum) -> new EndpointInfo(
                    rs.getString("endpoint_url"),
                    rs.getString("auth_type"),
                    rs.getString("auth_credential_ref"),
                    agencyCode
            ), agencyCode, ENDPOINT_TYPE_PROVISIONING);

        } catch (Exception e) {
            log.warn("[ProvisioningRelayJob] 엔드포인트 조회 실패: agency={} error={}",
                    agencyCode, e.getMessage());
            return null;
        }
    }

    /**
     * 기관 자격증명 조회 — 환경변수 우선 → DB Fallback
     *
     * <p>환경변수명 변환 규칙: ref의 비알파벳/숫자 → '_' 대문자화
     * <ul>
     *   <li>{@code "secrets/agency/AGENCY_001/api-key"} → {@code SECRETS_AGENCY_AGENCY_001_API_KEY}</li>
     *   <li>{@code "secrets/agency/AGENCY_003/hmac-secret"} → {@code SECRETS_AGENCY_AGENCY_003_HMAC_SECRET}</li>
     * </ul>
     */
    private String findSecret(String ref) {
        if (ref == null || ref.isBlank()) return null;

        // ① 환경변수 조회 (K8s Secret envFrom 마운트)
        String envVarName = ref.toUpperCase().replaceAll("[^A-Z0-9]", "_");
        String envValue   = System.getenv(envVarName);
        if (envValue != null && !envValue.isBlank()) return envValue;

        // ② DB fallback (ido.agency_credential_config 테이블)
        try {
            return idoJdbcTemplate.queryForObject("""
                    SELECT credential_value
                    FROM ido.agency_credential_config
                    WHERE credential_ref = ? AND active = TRUE
                    LIMIT 1
                    """, String.class, ref);
        } catch (Exception e) {
            log.warn("[ProvisioningRelayJob] 자격증명 조회 실패: ref={} → 환경변수 {} 등록 필요",
                    ref, envVarName);
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

    // ─────────────────────────────────────────────────────────────────────────
    // Inner types
    // ─────────────────────────────────────────────────────────────────────────

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

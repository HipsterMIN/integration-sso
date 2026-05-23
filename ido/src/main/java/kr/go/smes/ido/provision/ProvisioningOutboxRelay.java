package kr.go.smes.ido.provision;

import kr.go.smes.ido.infrastructure.AgencyEndpointRecord;
import kr.go.smes.ido.infrastructure.AgencyEndpointRegistryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;


import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 프로비저닝 아웃박스 릴레이 — PENDING 레코드 재발행 (@Scheduled)
 *
 * <p>IdoOutboxRelay 패턴을 복제하여 provisioning_outbox 전용으로 구현.
 * 차이점:
 * <ul>
 *   <li>Kafka 발행 대신 기관 HTTP POST 재시도</li>
 *   <li>지수 백오프: 1분→5분→30분 (DB CASE WHEN으로 계산 — ProvisioningOutboxRepositoryImpl)</li>
 *   <li>DEAD_LETTER 전환: retry_count ≥ max_retry 시</li>
 *   <li>FOR UPDATE SKIP LOCKED: 다중 인스턴스 간 중복 처리 방지</li>
 * </ul>
 *
 * <p>설정 키:
 * <ul>
 *   <li>{@code ido.provisioning.relay-interval-ms} — 스케줄 주기 (기본 30,000ms = 30초)</li>
 *   <li>{@code ido.provisioning.relay-batch-size} — 배치 크기 (기본 50)</li>
 *   <li>{@code ido.provisioning.relay-enabled}    — On/Off Feature Flag (기본 true)</li>
 * </ul>
 *
 * <p>@EnableScheduling은 IdoApplication + IdoWebConfig에 이미 선언됨.
 */
@Slf4j
@Component
public class ProvisioningOutboxRelay {

    private static final String ENDPOINT_TYPE_PROVISIONING = "PROVISIONING";

    private final ProvisioningOutboxRepository     outboxRepository;
    private final AgencyEndpointRegistryRepository endpointRegistry;
    private final AgencyCredentialStore            credentialStore;

    /** 일반 기관 HTTP 통신 (API_KEY / HMAC / NONE) */
    private final RestTemplate restTemplate;

    /** mTLS 전용 RestTemplate — 클라이언트 인증서(PKCS12) 장착 빈 */
    private final RestTemplate mtlsRestTemplate;

    /**
     * @Qualifier("mtlsProvisioningRestTemplate")를 명시적 생성자로 처리.
     */
    public ProvisioningOutboxRelay(
            ProvisioningOutboxRepository outboxRepository,
            AgencyEndpointRegistryRepository endpointRegistry,
            AgencyCredentialStore credentialStore,
            RestTemplate restTemplate,
            @Qualifier("mtlsProvisioningRestTemplate") RestTemplate mtlsRestTemplate) {
        this.outboxRepository  = outboxRepository;
        this.endpointRegistry  = endpointRegistry;
        this.credentialStore   = credentialStore;
        this.restTemplate      = restTemplate;
        this.mtlsRestTemplate  = mtlsRestTemplate;
    }

    /**
     * Feature Flag: IDO_PROVISIONING_RELAY_ENABLED
     * false → @Scheduled 실행되어도 즉시 return (테스트/점검 중 유용)
     */
    @Value("${ido.provisioning.relay-enabled:${IDO_PROVISIONING_RELAY_ENABLED:true}}")
    private boolean relayEnabled;

    @Value("${ido.provisioning.relay-batch-size:50}")
    private int batchSize;

    // ─────────────────────────────────────────────────────────────────────
    // 스케줄러
    // ─────────────────────────────────────────────────────────────────────

    /**
     * PENDING 레코드 재시도 스케줄러
     *
     * <p>fixedDelay: 이전 실행 완료 후 대기 → 처리 중 중복 실행 없음.
     * 기본 30초 주기 (지수 백오프 최소 단위 1분보다 작게 설정하여 즉시 재시도 가능).
     *
     * <p>트랜잭션: FOR UPDATE SKIP LOCKED는 트랜잭션 내에서만 유효.
     */
    @Scheduled(fixedDelayString = "${ido.provisioning.relay-interval-ms:30000}")
    @Transactional
    public void relay() {
        if (!relayEnabled) {
            log.trace("[ProvisioningRelay] DISABLED (IDO_PROVISIONING_RELAY_ENABLED=false)");
            return;
        }

        List<ProvisioningOutboxRecord> pendingBatch = outboxRepository.findPendingBatch(batchSize);
        if (pendingBatch.isEmpty()) {
            return;
        }

        log.info("[ProvisioningRelay] PENDING {} 건 재시도 시작", pendingBatch.size());

        int successCount    = 0;
        int retryCount      = 0;
        int deadLetterCount = 0;

        for (ProvisioningOutboxRecord record : pendingBatch) {
            RelayResult result = retryRecord(record);

            switch (result) {
                case SUCCESS    -> successCount++;
                case RETRY      -> retryCount++;
                case DEAD_LETTER -> deadLetterCount++;
            }
        }

        log.info("[ProvisioningRelay] 배치 완료: 성공={} 재예약={} DEAD_LETTER={}",
                successCount, retryCount, deadLetterCount);
    }

    // ─────────────────────────────────────────────────────────────────────
    // private: 단일 레코드 재시도
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 단일 outbox 레코드를 해당 기관 엔드포인트에 재발행
     *
     * @param record PENDING 상태 프로비저닝 아웃박스 레코드
     * @return 처리 결과 (SUCCESS / RETRY / DEAD_LETTER)
     */
    private RelayResult retryRecord(ProvisioningOutboxRecord record) {
        String id          = record.getId();
        String agencyCode  = record.getAgencyCode();
        int    nextRetry   = record.getRetryCount() + 1;
        int    maxRetry    = record.getMaxRetry();

        // ① 기관 PROVISIONING 엔드포인트 조회
        Optional<AgencyEndpointRecord> endpointOpt =
                endpointRegistry.findByAgencyAndType(agencyCode, ENDPOINT_TYPE_PROVISIONING);

        if (endpointOpt.isEmpty()) {
            log.warn("[ProvisioningRelay] 엔드포인트 없음 (비활성화?): agencyCode={} id={}", agencyCode, id);
            // 엔드포인트가 비활성화된 경우 DEAD_LETTER 처리
            if (nextRetry >= maxRetry) {
                outboxRepository.markDeadLetter(id, "Endpoint not found or deactivated: " + agencyCode);
                return RelayResult.DEAD_LETTER;
            }
            outboxRepository.incrementRetryWithBackoff(id, "Endpoint not found: " + agencyCode);
            return RelayResult.RETRY;
        }

        AgencyEndpointRecord endpoint = endpointOpt.get();

        // ② HTTP 재발행 시도
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (record.getCorrelationId() != null) {
                headers.set("X-Correlation-ID", record.getCorrelationId());
            }
            headers.set("X-Provisioning-Source", "onepass-ido");
            headers.set("X-Idempotency-Key", record.getIdempotencyKey());
            headers.set("X-Retry-Count", String.valueOf(record.getRetryCount()));
            // 인증 헤더 — auth_type별 처리 (API_KEY / HMAC / MTLS)
            addAuthHeader(headers, endpoint, record.getIdempotencyKey());

            HttpEntity<String> entity = new HttpEntity<>(record.getPayloadJson(), headers);
            ResponseEntity<String> response = selectRestTemplate(endpoint).postForEntity(
                    endpoint.getEndpointUrl(), entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                outboxRepository.markCompleted(id);
                log.info("[ProvisioningRelay] 재발행 성공: agencyCode={} id={} retryCount={}",
                        agencyCode, id, record.getRetryCount());
                return RelayResult.SUCCESS;
            } else {
                // HTTP 4xx/5xx — 재시도 또는 DEAD_LETTER
                String error = "HTTP " + response.getStatusCode().value();
                return handleFailure(id, agencyCode, nextRetry, maxRetry, error);
            }

        } catch (Exception e) {
            String error = e.getClass().getSimpleName() + ": " + truncate(e.getMessage(), 500);
            log.warn("[ProvisioningRelay] HTTP 예외: agencyCode={} id={} retry={}/{} error={}",
                    agencyCode, id, nextRetry, maxRetry, error);
            return handleFailure(id, agencyCode, nextRetry, maxRetry, error);
        }
    }

    /**
     * 실패 처리 — 재시도 횟수에 따라 RETRY 또는 DEAD_LETTER 결정
     */
    private RelayResult handleFailure(String id, String agencyCode,
                                      int nextRetry, int maxRetry, String error) {
        if (nextRetry >= maxRetry) {
            outboxRepository.markDeadLetter(id, error);
            log.error("[ProvisioningRelay] DEAD_LETTER 전환: agencyCode={} id={} retry={}/{} error={}",
                    agencyCode, id, nextRetry, maxRetry, error);
            return RelayResult.DEAD_LETTER;
        }
        outboxRepository.incrementRetryWithBackoff(id, error);
        return RelayResult.RETRY;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 내부 열거형
    // ─────────────────────────────────────────────────────────────────────

    private enum RelayResult {
        /** 재발행 성공 → COMPLETED */
        SUCCESS,
        /** 실패 + maxRetry 미만 → PENDING (next_retry_at 갱신) */
        RETRY,
        /** maxRetry 초과 → DEAD_LETTER */
        DEAD_LETTER
    }

    // ─────────────────────────────────────────────────────────────────────
    // private: 인증 헤더 (ProvisioningServiceImpl과 동일 로직 — 공통 유틸 추후 추출 가능)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 기관 auth_type에 따른 아웃바운드 인증 헤더 추가.
     *
     * @param headers        설정 대상 HttpHeaders
     * @param endpoint       대상 기관 엔드포인트 정보
     * @param idempotencyKey HMAC 서명 페이로드에 포함될 멱등성 키
     */
    private void addAuthHeader(HttpHeaders headers, AgencyEndpointRecord endpoint,
                               String idempotencyKey) {
        String ref = endpoint.getAuthCredentialRef();
        if (ref == null || ref.isBlank()) return;

        switch (endpoint.getAuthType()) {

            case "API_KEY" -> {
                String apiKey = credentialStore.findSecret(ref);
                if (apiKey == null || apiKey.isBlank()) {
                    log.error("[ProvisioningRelay] API_KEY 자격증명 미등록 — agencyCode={} ref={} " +
                              "→ K8s Secret에 {} 키 등록 필요.",
                            endpoint.getAgencyCode(), ref,
                            AgencyCredentialStore.toEnvVarName(ref));
                    return;
                }
                headers.set("X-Api-Key", apiKey);
            }

            case "HMAC" -> {
                String hmacSecret = credentialStore.findSecret(ref);
                if (hmacSecret == null || hmacSecret.isBlank()) {
                    log.error("[ProvisioningRelay] HMAC 자격증명 미등록 — agencyCode={} ref={}",
                            endpoint.getAgencyCode(), ref);
                    return;
                }
                try {
                    // F4.9 (Sprint β-3): 공통 SignaturePayloadBuilder 위임.
                    // payload = {agencyCode}:{idempotencyKey}:{epochSeconds} — 인바운드와 통일.
                    long epochSeconds = Instant.now().getEpochSecond();
                    String signature  = kr.go.smes.ido.gateway.SignaturePayloadBuilder.computeSignature(
                            endpoint.getAgencyCode(), idempotencyKey, epochSeconds, hmacSecret);
                    headers.set("X-Signature", signature);
                    headers.set("X-Timestamp",  String.valueOf(epochSeconds));
                    headers.set("X-Agency-Code", endpoint.getAgencyCode());
                } catch (Exception e) {
                    log.error("[ProvisioningRelay] HMAC 서명 계산 실패 — agencyCode={} error={}",
                            endpoint.getAgencyCode(), e.getMessage());
                }
            }

            case "MTLS" -> {
                // 헤더 불필요 — selectRestTemplate()에서 mtlsRestTemplate 선택
                log.debug("[ProvisioningRelay] MTLS 기관 — 클라이언트 인증서 TLS: agencyCode={}",
                          endpoint.getAgencyCode());
            }

            default -> { /* NONE — 추가 헤더 없음 */ }
        }
    }

    /**
     * authType에 따라 적절한 RestTemplate 선택.
     * MTLS 기관은 클라이언트 인증서 장착 빈, 그 외는 일반 빈 사용.
     */
    private RestTemplate selectRestTemplate(AgencyEndpointRecord endpoint) {
        return "MTLS".equals(endpoint.getAuthType()) ? mtlsRestTemplate : restTemplate;
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }
}

package kr.go.smes.ido.provision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.ido.infrastructure.AgencyEndpointRecord;
import kr.go.smes.ido.infrastructure.AgencyEndpointRegistryRepository;
import kr.go.smes.ido.provision.dto.ProvisioningRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 전 기관 프로비저닝 서비스 구현체
 *
 * <p>Sprint 14 핵심 — Virtual Thread 병렬 HTTP 설계:
 * <ul>
 *   <li>JDK 21 {@code Executors.newVirtualThreadPerTaskExecutor()} — OS 스레드 블로킹 없음</li>
 *   <li>68개 기관에 HTTP POST 동시 발행 → 응답 대기 → 성공/실패 분류</li>
 *   <li>성공: outbox INSERT + markCompleted (DB에 COMPLETED 기록)</li>
 *   <li>실패: outbox INSERT(PENDING 상태) → ProvisioningOutboxRelay가 지수 백오프 재시도</li>
 * </ul>
 *
 * <p>PII 최소화 (§14.4.3):
 * 기관 전송 페이로드에 실명/전화 평문 절대 포함 금지.
 * {@code identity_hash} = SHA-256(qimUserId + ":" + registeredAtEpoch)
 *
 * <p>중복 트리거 방어:
 * {@code sourceEventId} 기반 {@code countBySourceEventId()} 조회로
 * 동일 Kafka 이벤트 재소비 시 프로비저닝 중복 실행 차단.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProvisioningServiceImpl implements ProvisioningService {

    private static final String ENDPOINT_TYPE_PROVISIONING = "PROVISIONING";

    private final AgencyEndpointRegistryRepository endpointRegistry;
    private final ProvisioningOutboxRepository     outboxRepository;
    private final RestTemplate                     restTemplate;
    private final ObjectMapper                     objectMapper;

    /**
     * F-20: 프로비저닝 기능 On/Off.
     * Phase 1 기본값: false. Phase 2 이후 true로 전환.
     * @see docs/features/F-20-provisioning.md
     */
    @Value("${ido.provisioning.enabled:${IDO_PROVISIONING_ENABLED:false}}")
    private boolean provisioningEnabled;

    /**
     * F-22: 프로비저닝 dry-run 모드.
     * true: 페이로드 생성 + 로그만, HTTP 미발행 (Phase 2-A 관찰 기간).
     * false: 실제 기관 HTTP POST 발행 (Phase 2-B 이후).
     * @see docs/features/F-20-provisioning.md
     */
    @Value("${ido.provisioning.dry-run:${IDO_PROVISIONING_DRY_RUN:true}}")
    private boolean provisioningDryRun;

    /** 단일 기관 HTTP 요청 타임아웃 오버라이드 (0이면 기관별 설정 사용) */
    @Value("${ido.provisioning.timeout-ms:0}")
    private int globalTimeoutMs;

    /** 배치 병렬 처리 최대 기관 수 (안전 상한선) */
    @Value("${ido.provisioning.max-parallel-agencies:100}")
    private int maxParallelAgencies;

    // ─────────────────────────────────────────────────────────────────────
    // 공개 메서드
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void triggerProvisioning(String qimUserId, String eventType,
                                    String sourceEventId, String correlationId) {
        // ① Feature Flag 가드 (F-20)
        if (!provisioningEnabled) {
            log.info("[Provisioning] DISABLED (IDO_PROVISIONING_ENABLED=false). 스킵: qimUserId={}", qimUserId);
            return;
        }

        // dry-run 모드 안내 (F-22)
        if (provisioningDryRun) {
            log.info("[Provisioning] DRY-RUN 모드 (IDO_PROVISIONING_DRY_RUN=true): 페이로드 생성 후 HTTP 미발행. qimUserId={}", qimUserId);
        }

        // ② 중복 트리거 방어 — 동일 sourceEventId로 이미 프로비저닝 레코드가 있으면 스킵
        if (sourceEventId != null && outboxRepository.countBySourceEventId(sourceEventId) > 0) {
            log.info("[Provisioning] 중복 트리거 스킵: sourceEventId={} qimUserId={}", sourceEventId, qimUserId);
            return;
        }

        // ③ 활성 PROVISIONING 엔드포인트 전체 조회 (최대 68개)
        List<AgencyEndpointRecord> endpoints = endpointRegistry.findAllActiveByType(ENDPOINT_TYPE_PROVISIONING);
        if (endpoints.isEmpty()) {
            log.warn("[Provisioning] 활성 PROVISIONING 엔드포인트 없음. qimUserId={}", qimUserId);
            return;
        }

        Instant now = Instant.now();
        // ④ 멱등성 기준 키 (모든 기관 공통, 이벤트 단위)
        //    각 기관별 outbox 레코드의 idempotency_key = baseKey (agency_code 가 UNIQUE 키의 다른 축)
        String baseIdempotencyKey = sourceEventId != null ? sourceEventId : UUID.randomUUID().toString();
        String identityHash       = buildIdentityHash(qimUserId, now);

        log.info("[Provisioning] 프로비저닝 시작: qimUserId={} eventType={} 기관수={} idempotencyKey={}",
                qimUserId, eventType, endpoints.size(), baseIdempotencyKey);

        // ⑤ dry-run 시 여기서 종료 (HTTP 발행 없이 관찰만)
        if (provisioningDryRun) {
            log.info("[Provisioning] DRY-RUN 완료: 대상 기관={}개, qimUserId={}, eventType={}. " +
                     "실제 발행 없음. IDO_PROVISIONING_DRY_RUN=false 로 변경 시 발행 시작.",
                     endpoints.size(), qimUserId, eventType);
            for (AgencyEndpointRecord ep : endpoints) {
                log.debug("[Provisioning] DRY-RUN 대상: agencyCode={} url={}",
                          ep.getAgencyCode(), ep.getEndpointUrl());
            }
            return;
        }

        // ⑤ Virtual Thread 병렬 HTTP 발행
        List<AgencyEndpointRecord> targetEndpoints =
                endpoints.size() > maxParallelAgencies
                        ? endpoints.subList(0, maxParallelAgencies)
                        : endpoints;

        try (ExecutorService vThreadPool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ProvisioningResult>> futures = new ArrayList<>(targetEndpoints.size());

            for (AgencyEndpointRecord endpoint : targetEndpoints) {
                ProvisioningRequest request = ProvisioningRequest.builder()
                        .qimUserId(qimUserId)
                        .eventType(eventType)
                        .identityHash(identityHash)
                        .registeredAt(now)
                        .idempotencyKey(baseIdempotencyKey)
                        .correlationId(correlationId)
                        .build();

                futures.add(vThreadPool.submit(
                        () -> sendToAgency(endpoint, request, correlationId)
                ));
            }

            // ⑥ 결과 수집 + outbox 기록
            int successCount = 0;
            int failCount    = 0;

            for (int i = 0; i < futures.size(); i++) {
                AgencyEndpointRecord endpoint = targetEndpoints.get(i);
                try {
                    ProvisioningResult result = futures.get(i).get();
                    String outboxId = insertOutbox(qimUserId, endpoint.getAgencyCode(),
                            eventType, request2Json(
                                    ProvisioningRequest.builder()
                                            .qimUserId(qimUserId)
                                            .eventType(eventType)
                                            .identityHash(identityHash)
                                            .registeredAt(now)
                                            .idempotencyKey(baseIdempotencyKey)
                                            .correlationId(correlationId)
                                            .build()),
                            baseIdempotencyKey, correlationId, sourceEventId);

                    if (result.success()) {
                        if (outboxId != null) {
                            outboxRepository.markCompleted(outboxId);
                        }
                        successCount++;
                        log.debug("[Provisioning] 성공: agencyCode={} httpStatus={}",
                                endpoint.getAgencyCode(), result.httpStatus());
                    } else {
                        failCount++;
                        log.warn("[Provisioning] 실패 (PENDING 재시도 예약): agencyCode={} error={}",
                                endpoint.getAgencyCode(), result.errorMessage());
                        // PENDING 상태로 남아 ProvisioningOutboxRelay가 재시도
                    }

                } catch (Exception e) {
                    failCount++;
                    log.error("[Provisioning] 기관 발송 예외: agencyCode={} error={}",
                            endpoint.getAgencyCode(), e.getMessage());
                    insertOutbox(qimUserId, endpoint.getAgencyCode(), eventType,
                            "{\"error\":\"future_exception\"}", baseIdempotencyKey,
                            correlationId, sourceEventId);
                }
            }

            log.info("[Provisioning] 완료: qimUserId={} eventType={} 성공={} 실패(PENDING)={}",
                    qimUserId, eventType, successCount, failCount);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // private: 단일 기관 HTTP 발행
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 단일 기관에 HTTP POST 발행 (Virtual Thread 내 실행)
     *
     * @param endpoint    대상 기관 엔드포인트 정보
     * @param request     전송 페이로드
     * @param correlationId 흐름 추적 ID
     * @return 발행 결과 (success, httpStatus, errorMessage)
     */
    private ProvisioningResult sendToAgency(AgencyEndpointRecord endpoint,
                                            ProvisioningRequest request,
                                            String correlationId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (correlationId != null) {
                headers.set("X-Correlation-ID", correlationId);
            }
            headers.set("X-Provisioning-Source", "onepass-ido");
            // 인증 헤더 — auth_type별 처리 (API_KEY: X-Api-Key, HMAC: X-Signature, MTLS: 클라이언트 인증서)
            addAuthHeader(headers, endpoint);

            String payloadJson = objectMapper.writeValueAsString(request);
            HttpEntity<String> entity = new HttpEntity<>(payloadJson, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    endpoint.getEndpointUrl(), entity, String.class);

            boolean success = response.getStatusCode().is2xxSuccessful();
            return new ProvisioningResult(success, response.getStatusCode().value(), null);

        } catch (Exception e) {
            log.warn("[Provisioning] HTTP 실패: agencyCode={} url={} error={}",
                    endpoint.getAgencyCode(), endpoint.getEndpointUrl(), e.getMessage());
            return new ProvisioningResult(false, 0, e.getMessage());
        }
    }

    /**
     * 기관 인증 방식에 따른 HTTP 헤더 추가
     *
     * <p>운영: auth_credential_ref → K8s Secret 조회 (Phase 2 구현 예정)
     * 현재: auth_type 기반 헤더 구조만 설정, 실제 자격증명은 TODO
     */
    private void addAuthHeader(HttpHeaders headers, AgencyEndpointRecord endpoint) {
        if (endpoint.getAuthCredentialRef() == null) return;
        switch (endpoint.getAuthType()) {
            case "API_KEY" ->
                // TODO (Sprint 17): K8s Secret에서 실제 API 키 조회
                headers.set("X-Api-Key", "PLACEHOLDER_" + endpoint.getAgencyCode());
            case "HMAC" ->
                // TODO (Sprint 17): HMAC-SHA256 서명 생성
                headers.set("X-Signature", "HMAC_PLACEHOLDER");
            case "MTLS" ->
                // mTLS: RestTemplate에 클라이언트 인증서 설정 필요 (Sprint 17)
                log.debug("[Provisioning] mTLS 인증: agencyCode={} (Sprint 17 구현 예정)", endpoint.getAgencyCode());
            default -> {
                // NONE — 추가 헤더 없음
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // private: Outbox INSERT
    // ─────────────────────────────────────────────────────────────────────

    /**
     * provisioning_outbox에 레코드 삽입 후 생성된 ID 반환
     * ON CONFLICT DO NOTHING → 중복 시 null 반환 (정상).
     *
     * <p>성공 삽입 후 findByIdempotencyKeyAndAgency 조회로 실제 DB UUID를 반환.
     * Relay는 findPendingBatch()로 id를 직접 조회하므로 이 ID는 즉시 markCompleted용.
     */
    private String insertOutbox(String qimUserId, String agencyCode, String eventType,
                                String payloadJson, String idempotencyKey,
                                String correlationId, String sourceEventId) {
        ProvisioningOutboxRecord record = ProvisioningOutboxRecord.builder()
                .qimUserId(qimUserId)
                .agencyCode(agencyCode)
                .eventType(eventType)
                .payloadJson(payloadJson)
                .idempotencyKey(idempotencyKey)
                .correlationId(correlationId)
                .sourceEventId(sourceEventId)
                .build();
        int rows = outboxRepository.insert(record);
        if (rows == 0) {
            // 중복 삽입(ON CONFLICT DO NOTHING) — 기존 레코드 ID 반환
            return outboxRepository.findIdByIdempotencyKeyAndAgency(idempotencyKey, agencyCode);
        }
        // 새로 삽입된 레코드 ID 조회
        return outboxRepository.findIdByIdempotencyKeyAndAgency(idempotencyKey, agencyCode);
    }

    // ─────────────────────────────────────────────────────────────────────
    // private: 유틸리티
    // ─────────────────────────────────────────────────────────────────────

    /**
     * PII 최소화용 identity_hash 생성
     * SHA-256(qimUserId + ":" + epochMilli)
     */
    private String buildIdentityHash(String qimUserId, Instant registeredAt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = qimUserId + ":" + registeredAt.toEpochMilli();
            byte[] hash  = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 JVM 필수 지원 — 실제로는 발생 불가
            log.error("[Provisioning] SHA-256 사용 불가 (JVM 오류): {}", e.getMessage());
            return "sha256:unavailable";
        }
    }

    private String request2Json(ProvisioningRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            log.warn("[Provisioning] 페이로드 직렬화 실패: {}", e.getMessage());
            return "{\"error\":\"serialization_failed\"}";
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // private: 결과 레코드
    // ─────────────────────────────────────────────────────────────────────

    private record ProvisioningResult(boolean success, int httpStatus, String errorMessage) {}
}

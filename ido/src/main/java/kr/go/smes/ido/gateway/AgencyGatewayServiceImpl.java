package kr.go.smes.ido.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.ido.gateway.dto.GatewayStatusResponse;
import kr.go.smes.ido.gateway.dto.InboundGatewayEvent;
import kr.go.smes.ido.gateway.dto.OutboundNotifyRequest;
import kr.go.smes.ido.infrastructure.AgencyEndpointRecord;
import kr.go.smes.ido.infrastructure.AgencyEndpointRegistryRepository;
import kr.go.smes.ido.infrastructure.AgencyMetaRepository;
import kr.go.smes.ido.provision.ProvisioningOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * AgencyGatewayService 구현체 — 양방향 게이트웨이 핵심 로직
 *
 * <p>인바운드 처리 흐름:
 * <ol>
 *   <li>Redis SET NX 중복 체크 (GatewayIdempotencyStore)</li>
 *   <li>gateway_inbound_audit INSERT (RECEIVED)</li>
 *   <li>이벤트 타입 기반 라우팅</li>
 *   <li>PROCESSED 상태 갱신</li>
 * </ol>
 *
 * <p>아웃바운드 처리 흐름:
 * <ol>
 *   <li>idempotencyKey 없으면 UUID v7 자동 생성</li>
 *   <li>Redis SET NX 중복 발송 방어</li>
 *   <li>기관 WEBHOOK 엔드포인트 조회</li>
 *   <li>HTTP POST 발송</li>
 *   <li>gateway_outbound_audit INSERT (DELIVERED / FAILED)</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgencyGatewayServiceImpl implements AgencyGatewayService {

    private static final String ENDPOINT_TYPE_WEBHOOK = "WEBHOOK";

    private final GatewayIdempotencyStore          idempotencyStore;
    private final GatewayInboundRepository         inboundRepository;
    private final GatewayOutboundRepository        outboundRepository;
    private final AgencyEndpointRegistryRepository endpointRegistry;
    private final AgencyMetaRepository             agencyMetaRepository;
    private final ProvisioningOutboxRepository     provisioningOutboxRepository;
    private final RestTemplate                     restTemplate;
    private final ObjectMapper                     objectMapper;

    // ─────────────────────────────────────────────────────────────────────
    // 인바운드 수신
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void receiveInbound(InboundGatewayEvent event) {
        String idempotencyKey = event.getIdempotencyKey();
        String agencyCode     = event.getAgencyCode();
        String correlationId  = event.getCorrelationId();

        // ① Redis SET NX 중복 체크 (1차 방어 — 빠름)
        boolean acquired = idempotencyStore.tryAcquireInbound(idempotencyKey);
        if (!acquired) {
            log.info("[GatewayInbound] 중복 이벤트 차단 (Redis): idempotencyKey={} agencyCode={}",
                    idempotencyKey, agencyCode);
            throw new PlatformException(PlatformErrorCode.PROV_IDEMPOTENCY_CONFLICT, correlationId);
        }

        // ② DB INSERT (2차 방어 — UNIQUE 제약)
        GatewayInboundRecord record = GatewayInboundRecord.builder()
                .agencyCode(agencyCode)
                .eventType(event.getEventType())
                .idempotencyKey(idempotencyKey)
                .payloadJson(event.getPayloadJson())
                .sourceIp(event.getSourceIp())
                .correlationId(correlationId)
                .build();

        int inserted = inboundRepository.insert(record);
        if (inserted == 0) {
            // DB UNIQUE 충돌 — Redis 통과했으나 DB에서 잡힌 race condition
            log.warn("[GatewayInbound] DB 중복 (race condition): idempotencyKey={}", idempotencyKey);
            idempotencyStore.releaseInbound(idempotencyKey); // Redis 키 해제
            throw new PlatformException(PlatformErrorCode.PROV_IDEMPOTENCY_CONFLICT, correlationId);
        }

        // ③ 이벤트 타입 기반 라우팅 (확장 포인트 — Sprint 16+에서 핸들러 등록)
        try {
            routeInboundEvent(event);
        } catch (Exception routeEx) {
            inboundRepository.markRejected(idempotencyKey, routeEx.getMessage());
            log.error("[GatewayInbound] 이벤트 라우팅 실패: eventType={} agencyCode={} error={}",
                    event.getEventType(), agencyCode, routeEx.getMessage());
            throw new PlatformException(PlatformErrorCode.PROV_INBOUND_REJECTED, correlationId);
        }

        // ④ 처리 완료
        inboundRepository.markProcessed(idempotencyKey);
        log.info("[GatewayInbound] 처리 완료: eventType={} agencyCode={} idempotencyKey={}",
                event.getEventType(), agencyCode, idempotencyKey);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 아웃바운드 발송
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public int sendOutbound(OutboundNotifyRequest request) {
        String agencyCode     = request.getAgencyCode();
        String correlationId  = request.getCorrelationId();
        // idempotencyKey 없으면 UUID v7 자동 생성
        String idempotencyKey = (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank())
                ? request.getIdempotencyKey()
                : UUID.randomUUID().toString();

        // ① 아웃바운드 중복 발송 방어
        if (!idempotencyStore.tryAcquireOutbound(idempotencyKey, agencyCode)) {
            log.info("[GatewayOutbound] 중복 발송 차단: idempotencyKey={} agencyCode={}",
                    idempotencyKey, agencyCode);
            return 0;
        }

        // ② WEBHOOK 엔드포인트 조회
        Optional<AgencyEndpointRecord> endpointOpt =
                endpointRegistry.findByAgencyAndType(agencyCode, ENDPOINT_TYPE_WEBHOOK);
        if (endpointOpt.isEmpty()) {
            log.warn("[GatewayOutbound] WEBHOOK 엔드포인트 없음: agencyCode={}", agencyCode);
            throw new PlatformException(PlatformErrorCode.PROV_AGENCY_ENDPOINT_NOT_FOUND, correlationId);
        }

        AgencyEndpointRecord endpoint = endpointOpt.get();
        String payloadHash = sha256Hex(request.getPayloadJson() != null ? request.getPayloadJson() : "");

        // ③ HTTP POST 발송
        int httpStatus     = 0;
        boolean delivered  = false;
        String errorMsg    = null;

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Idempotency-Key", idempotencyKey);
            if (correlationId != null) headers.set("X-Correlation-ID", correlationId);
            headers.set("X-Outbound-Source", "onepass-ido");

            // HMAC 서명 (auth_type=HMAC인 경우)
            if ("HMAC".equals(endpoint.getAuthType()) && request.getPayloadJson() != null) {
                // TODO (Sprint 17): K8s Secret에서 실제 HMAC 키 조회
                headers.set("X-Internal-Sig", "HMAC_PLACEHOLDER_" + agencyCode);
            }

            HttpEntity<String> entity = new HttpEntity<>(request.getPayloadJson(), headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    endpoint.getEndpointUrl(), entity, String.class);

            httpStatus = response.getStatusCode().value();
            delivered  = response.getStatusCode().is2xxSuccessful();

        } catch (Exception e) {
            errorMsg  = e.getClass().getSimpleName() + ": " + truncate(e.getMessage(), 500);
            delivered = false;
            log.warn("[GatewayOutbound] HTTP 실패: agencyCode={} url={} error={}",
                    agencyCode, endpoint.getEndpointUrl(), errorMsg);
        }

        // ④ 이력 저장
        outboundRepository.insert(agencyCode, request.getEventType(), idempotencyKey,
                endpoint.getEndpointUrl(), httpStatus, payloadHash, correlationId, delivered);

        log.info("[GatewayOutbound] 발송 완료: agencyCode={} eventType={} delivered={} httpStatus={}",
                agencyCode, request.getEventType(), delivered, httpStatus);
        return httpStatus;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 상태 조회
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public GatewayStatusResponse getStatus(String agencyCode) {
        // 기관 메타 조회
        var agencyMeta = agencyMetaRepository.findByCode(agencyCode);
        String agencyName = agencyMeta.map(m -> m.getOfficialName()).orElse(agencyCode);
        boolean active    = agencyMeta.map(m -> m.isActive()).orElse(false);

        // 활성 엔드포인트 수
        int activeEndpoints = endpointRegistry.findAllActiveByAgency(agencyCode).size();

        // PENDING 프로비저닝 건수 (provisioning_outbox)
        // findPendingBatch(Integer.MAX_VALUE)는 위험 — countByAgency 전용 메서드 사용
        // 현재 ProvisioningOutboxRepository에 countPendingByAgency 없으므로 0으로 기본값
        int pendingProvisioning    = 0;
        int deadLetterProvisioning = provisioningOutboxRepository.countDeadLetterByUser("__agency__" + agencyCode);
        // 실제로는 agencyCode 기준 dead_letter count가 필요하나 현재 API는 qimUserId 기준
        // → 0으로 처리 (Sprint 17 운영 모니터링 확장 시 보완)
        deadLetterProvisioning = 0;

        // 인바운드 미처리 건수
        int unprocessedInbound = inboundRepository.countUnprocessedByAgency(agencyCode);

        // 마지막 수신/발송 시각
        var lastInboundAt  = inboundRepository.findLastReceivedAt(agencyCode).orElse(null);
        var lastOutboundAt = outboundRepository.findLastSentAt(agencyCode).orElse(null);

        return GatewayStatusResponse.builder()
                .agencyCode(agencyCode)
                .agencyName(agencyName)
                .active(active)
                .activeEndpoints(activeEndpoints)
                .pendingProvisioning(pendingProvisioning)
                .deadLetterProvisioning(deadLetterProvisioning)
                .unprocessedInbound(unprocessedInbound)
                .lastInboundAt(lastInboundAt)
                .lastOutboundAt(lastOutboundAt)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // private: 이벤트 라우팅 (확장 포인트)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 인바운드 이벤트 타입 기반 라우팅
     *
     * <p>현재: 이벤트 수신 로그 기록.
     * Sprint 16+ 확장: 이벤트 타입별 핸들러 등록 (전략 패턴).
     */
    private void routeInboundEvent(InboundGatewayEvent event) {
        switch (event.getEventType()) {
            case "AGENCY_USER_UPDATED" ->
                log.info("[GatewayInbound] AGENCY_USER_UPDATED 수신: agencyCode={} correlationId={}",
                        event.getAgencyCode(), event.getCorrelationId());
            case "AGENCY_USER_WITHDRAWN" ->
                log.info("[GatewayInbound] AGENCY_USER_WITHDRAWN 수신: agencyCode={} correlationId={}",
                        event.getAgencyCode(), event.getCorrelationId());
            case "AGENCY_USER_REGISTERED", "AGENCY_BIZ_CONVERTED" ->
                log.info("[GatewayInbound] {} 수신: agencyCode={} correlationId={}",
                        event.getEventType(), event.getAgencyCode(), event.getCorrelationId());
            case "CUSTOM" ->
                log.info("[GatewayInbound] CUSTOM 이벤트 수신: agencyCode={}", event.getAgencyCode());
            default ->
                throw new IllegalArgumentException("지원하지 않는 이벤트 타입: " + event.getEventType());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // private: 유틸리티
    // ─────────────────────────────────────────────────────────────────────

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return "sha256_unavailable";
        }
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }
}

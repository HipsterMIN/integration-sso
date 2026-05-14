package kr.go.smes.ido.gateway;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import kr.go.smes.common.util.CorrelationIdHolder;
import kr.go.smes.ido.config.FeatureFlags;
import kr.go.smes.ido.config.HandoffAgencyKeyInterceptor;
import kr.go.smes.ido.gateway.dto.GatewayStatusResponse;
import kr.go.smes.ido.gateway.dto.InboundGatewayEvent;
import kr.go.smes.ido.gateway.dto.OutboundNotifyRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 양방향 Agency Gateway API 컨트롤러 (Sprint 15)
 *
 * <h3>엔드포인트</h3>
 * <ul>
 *   <li>{@code POST  /api/v1/agency/gateway/inbound/event}    — 기관 → OnePass 인바운드 이벤트 수신</li>
 *   <li>{@code PATCH /api/v1/agency/gateway/outbound/notify}  — OnePass → 기관 수동 발송 트리거</li>
 *   <li>{@code GET   /api/v1/agency/gateway/status/{agencyCode}} — 기관 연동 상태 조회</li>
 * </ul>
 *
 * <h3>인바운드 보안 (기관 → OnePass)</h3>
 * <ul>
 *   <li>X-Agency-Code + X-Agency-Key 헤더 → HandoffAgencyKeyInterceptor 검증</li>
 *   <li>X-Idempotency-Key 헤더 필수 (UUID v7) → Redis SET NX 24h 중복 방어</li>
 *   <li>X-Internal-Sig 헤더 (선택, HMAC-SHA256) → Sprint 17에서 강제화</li>
 * </ul>
 *
 * <h3>아웃바운드 보안 (OnePass → 기관)</h3>
 * <ul>
 *   <li>내부 서비스 전용 엔드포인트 — 외부 노출 금지 (IngressRule에서 차단)</li>
 *   <li>X-Internal-Token 헤더로 내부 서비스 인증 (Sprint 17 구현)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agency/gateway")
@RequiredArgsConstructor
public class AgencyGatewayController {

    private static final String HEADER_IDEMPOTENCY_KEY = "X-Idempotency-Key";
    private static final String HEADER_AGENCY_CODE     = "X-Agency-Code";
    private static final String HEADER_CORRELATION_ID  = "X-Correlation-ID";
    private static final String HEADER_INTERNAL_SIG    = "X-Internal-Sig";

    private final AgencyGatewayService gatewayService;
    private final FeatureFlags featureFlags;

    // ═════════════════════════════════════════════════════════════════════
    // 1. 인바운드 이벤트 수신  POST /api/v1/agency/gateway/inbound/event
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 기관 → OnePass 인바운드 이벤트 수신
     *
     * <p>요청 헤더:
     * <ul>
     *   <li>X-Agency-Code (필수) — 송신 기관 코드 (인터셉터 검증)</li>
     *   <li>X-Agency-Key  (필수) — 기관 API Key (인터셉터 SHA-256 검증)</li>
     *   <li>X-Idempotency-Key (필수) — UUID v7 멱등성 키</li>
     *   <li>X-Correlation-ID (선택) — 흐름 추적</li>
     *   <li>X-Internal-Sig (선택) — HMAC-SHA256 서명 (Sprint 17 필수화)</li>
     * </ul>
     *
     * <p>응답:
     * <ul>
     *   <li>202 Accepted — 수신 완료 (비동기 처리)</li>
     *   <li>409 Conflict — 중복 이벤트 (E-PROV-503)</li>
     *   <li>422 Unprocessable — 처리 거부 (E-PROV-504)</li>
     *   <li>401 Unauthorized — API Key 불일치 (인터셉터)</li>
     * </ul>
     */
    @PostMapping("/inbound/event")
    public ResponseEntity<Map<String, String>> receiveInbound(
            @RequestBody String payloadJson,
            HttpServletRequest httpRequest) {

        // F-23: 인바운드 API 열림 여부 확인 (Phase-Gate)
        if (!featureFlags.isGatewayInbound()) {
            log.info("[GatewayController] 인바운드 API DISABLED (IDO_GATEWAY_INBOUND_ENABLED=false). Phase 3 이전에는 비활성화 상태입니다.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                        "error",   "FEATURE_DISABLED",
                        "message", "Gateway 인바운드 API가 현재 비활성화 상태입니다. (IDO_GATEWAY_INBOUND_ENABLED=false)",
                        "phase",   "Phase 3-A 진입 후 활성화 예정"
                    ));
        }

        // ① 헤더 추출 (X-Agency-Code는 인터셉터가 검증 후 Attribute에 저장)
        String agencyCode     = resolveAgencyCode(httpRequest);
        String idempotencyKey = httpRequest.getHeader(HEADER_IDEMPOTENCY_KEY);
        String correlationId  = resolveCorrelationId(httpRequest);
        String eventType      = httpRequest.getHeader("X-Event-Type");
        String sourceIp       = httpRequest.getRemoteAddr();

        // ② 필수 헤더 검증
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            log.warn("[GatewayController] X-Idempotency-Key 누락: agencyCode={}", agencyCode);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "MISSING_IDEMPOTENCY_KEY",
                                 "message", "X-Idempotency-Key 헤더가 필요합니다."));
        }
        if (eventType == null || eventType.isBlank()) {
            eventType = "CUSTOM"; // 기본값
        }

        log.info("[GatewayController] 인바운드 이벤트 수신: agencyCode={} eventType={} idempotencyKey={}",
                agencyCode, eventType, idempotencyKey);

        // ③ 서비스 처리
        InboundGatewayEvent event = InboundGatewayEvent.builder()
                .agencyCode(agencyCode)
                .eventType(eventType)
                .idempotencyKey(idempotencyKey)
                .payloadJson(payloadJson)
                .sourceIp(sourceIp)
                .correlationId(correlationId)
                .build();

        gatewayService.receiveInbound(event);

        return ResponseEntity.accepted()
                .body(Map.of(
                        "status",          "RECEIVED",
                        "idempotency_key", idempotencyKey,
                        "correlation_id",  correlationId
                ));
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 아웃바운드 수동 발송  PATCH /api/v1/agency/gateway/outbound/notify
    // ═════════════════════════════════════════════════════════════════════

    /**
     * OnePass → 기관 수동 아웃바운드 발송 트리거
     *
     * <p>내부 서비스(운영자 대시보드, 배치 작업) 전용.
     * 외부 기관이 직접 호출할 수 없음 (Kubernetes IngressRule에서 차단).
     *
     * <p>요청 바디:
     * <pre>
     * {
     *   "agency_code":      "AGENCY_001",
     *   "event_type":       "NOTIFY_USER",
     *   "payload":          "{\"onepass_user_id\":\"...\"}",
     *   "idempotency_key":  "01914bf9-...",   // 없으면 서버 자동 생성
     *   "correlation_id":   "01914bfa-..."    // 없으면 서버 자동 생성
     * }
     * </pre>
     *
     * <p>응답:
     * <ul>
     *   <li>200 OK — 발송 완료 (http_status: 기관 응답 코드)</li>
     *   <li>404 Not Found — WEBHOOK 엔드포인트 없음 (E-PROV-501)</li>
     * </ul>
     */
    @PatchMapping("/outbound/notify")
    public ResponseEntity<Map<String, Object>> sendOutbound(
            @RequestBody @Valid OutboundNotifyRequestBody body,
            HttpServletRequest httpRequest) {

        // F-24: 아웃바운드 API 열림 여부 확인 (Phase-Gate)
        if (!featureFlags.isGatewayOutbound()) {
            log.info("[GatewayController] 아웃바운드 API DISABLED (IDO_GATEWAY_OUTBOUND_ENABLED=false). Phase 3-B 이전에는 비활성화 상태입니다.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                        "error",   "FEATURE_DISABLED",
                        "message", "Gateway 아웃바운드 API가 현재 비활성화 상태입니다. (IDO_GATEWAY_OUTBOUND_ENABLED=false)",
                        "phase",   "Phase 3-B 진입 후 활성화 예정"
                    ));
        }

        String correlationId = resolveCorrelationId(httpRequest);

        OutboundNotifyRequest request = OutboundNotifyRequest.builder()
                .agencyCode(body.agencyCode())
                .eventType(body.eventType())
                .payloadJson(body.payload())
                .idempotencyKey(body.idempotencyKey())
                .correlationId(correlationId)
                .build();

        log.info("[GatewayController] 아웃바운드 발송 트리거: agencyCode={} eventType={}",
                body.agencyCode(), body.eventType());

        int httpStatus = gatewayService.sendOutbound(request);

        return ResponseEntity.ok(Map.of(
                "agency_code",     body.agencyCode(),
                "event_type",      body.eventType(),
                "http_status",     httpStatus,
                "correlation_id",  correlationId,
                "idempotency_key", body.idempotencyKey() != null ? body.idempotencyKey() : "auto-generated"
        ));
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 기관 연동 상태 조회  GET /api/v1/agency/gateway/status/{agencyCode}
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 기관 연동 상태 조회
     *
     * <p>운영 대시보드 / 모니터링 시스템에서 호출.
     * 기관별 엔드포인트 수, PENDING 프로비저닝 건수, 미처리 인바운드 등을 집계.
     *
     * @param agencyCode 기관 코드 (경로 변수, 영문/숫자/언더스코어만 허용)
     */
    @GetMapping("/status/{agencyCode}")
    public ResponseEntity<GatewayStatusResponse> getStatus(
            @PathVariable
            @Pattern(regexp = "^[A-Z0-9_]{1,50}$", message = "agencyCode는 대문자/숫자/언더스코어만 허용")
            String agencyCode,
            HttpServletRequest httpRequest) {

        log.debug("[GatewayController] 상태 조회: agencyCode={}", agencyCode);
        GatewayStatusResponse status = gatewayService.getStatus(agencyCode);
        return ResponseEntity.ok(status);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 내부 헬퍼
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 검증된 agencyCode 추출
     * 인터셉터가 Attribute에 저장한 값 우선, 없으면 헤더에서 직접 추출
     */
    private String resolveAgencyCode(HttpServletRequest request) {
        Object attr = request.getAttribute(HandoffAgencyKeyInterceptor.ATTR_VALIDATED_AGENCY_CODE);
        if (attr != null) return attr.toString();
        return request.getHeader(HEADER_AGENCY_CODE);
    }

    /**
     * Correlation ID 결정
     * 헤더 > CorrelationIdHolder > 자동 생성 순
     */
    private String resolveCorrelationId(HttpServletRequest request) {
        String fromHeader = request.getHeader(HEADER_CORRELATION_ID);
        if (fromHeader != null && !fromHeader.isBlank()) return fromHeader;
        String fromHolder = CorrelationIdHolder.get();
        if (fromHolder != null && !fromHolder.isBlank()) return fromHolder;
        return UUID.randomUUID().toString();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 요청 바디 레코드 (Jakarta Validation 포함)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 아웃바운드 발송 요청 바디 레코드
     */
    record OutboundNotifyRequestBody(
            @NotBlank(message = "agency_code는 필수입니다")
            @com.fasterxml.jackson.annotation.JsonProperty("agency_code")
            String agencyCode,

            @NotBlank(message = "event_type은 필수입니다")
            @com.fasterxml.jackson.annotation.JsonProperty("event_type")
            String eventType,

            @com.fasterxml.jackson.annotation.JsonProperty("payload")
            String payload,

            @com.fasterxml.jackson.annotation.JsonProperty("idempotency_key")
            String idempotencyKey,

            @com.fasterxml.jackson.annotation.JsonProperty("correlation_id")
            String correlationId
    ) {}
}

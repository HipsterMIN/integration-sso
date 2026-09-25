package io.github.hipstermin.idem.tenant.webhook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.util.UuidV7;
import io.github.hipstermin.idem.tenant.session.AgencySessionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * IdO → 기관 Webhook 수신 컨트롤러
 *
 * <p><b>경로</b>: {@code POST /api/v1/webhook/inbound}
 *
 * <p><b>역할</b>:
 * IdO {@code WebhookDispatchOutboxRelay}가 발송한 HTTPS POST를 수신하여
 * <ol>
 *   <li>HMAC-SHA256 서명 검증 (X-Webhook-Signature / X-Webhook-Timestamp)</li>
 *   <li>재사용 공격 방지 — Timestamp ±5분 이내 확인</li>
 *   <li>중복 이벤트 방지 — source_event_id + agency_code UNIQUE 인덱스</li>
 *   <li>{@code webhook_inbound} 테이블 기록</li>
 *   <li>이벤트 타입별 비즈니스 처리 (세션 무효화 등)</li>
 *   <li>{@code agency_event_queue} 적재 (폴링 API용)</li>
 * </ol>
 *
 * <p><b>Webhook 서명 검증 방법</b>:
 * <pre>
 *   signTarget  = X-Webhook-Timestamp + "." + requestBody
 *   expectedSig = "sha256=" + HEX(HmacSHA256(signTarget, sharedSecret))
 *   검증:        X-Webhook-Signature == expectedSig
 *   타임스탬프:  |NOW() - X-Webhook-Timestamp| ≤ 300s (5분)
 * </pre>
 *
 * <p><b>이벤트 타입별 처리</b>:
 * <ul>
 *   <li>HANDOFF_ISSUED    → agency_event_queue 적재 (기관 앱이 폴링으로 수신)</li>
 *   <li>HANDOFF_REVOKED   → 해당 ticketId 세션 즉시 무효화</li>
 *   <li>SESSION_ADVISORY  → qimUserId 기준 세션 경고/강제 종료</li>
 *   <li>MEMBER_REGISTERED → 회원 등록 알림 큐 적재</li>
 *   <li>MEMBER_WITHDRAWN  → 회원 탈퇴 처리</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
public class WebhookInboundController {

    private static final long MAX_TIMESTAMP_DRIFT_SEC = 300L; // 5분

    private final JdbcTemplate         jdbcTemplate;
    private final AgencySessionService  agencySessionService;
    private final ObjectMapper          objectMapper;

    @Value("${idem.sample.webhook.signing-secret:poc-webhook-secret-change-in-production}")
    private String signingSecret;

    @Value("${idem.sample.code:AGENCY_STUB_001}")
    private String agencyCode;

    // ══════════════════════════════════════════════════════════════════════
    // Webhook 수신 엔드포인트
    // ══════════════════════════════════════════════════════════════════════

    /**
     * IdO WebhookDispatchOutboxRelay → 기관 수신
     * POST /api/v1/webhook/inbound
     *
     * @param signatureHeader X-Webhook-Signature 헤더 (sha256=HEX...)
     * @param timestampHeader X-Webhook-Timestamp 헤더 (epoch seconds)
     * @param correlationId   X-Correlation-Id 헤더
     * @param rawBody         요청 body (JSON 문자열)
     */
    @PostMapping("/inbound")
    public ResponseEntity<Map<String, String>> inbound(
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signatureHeader,
            @RequestHeader(value = "X-Webhook-Timestamp", required = false) String timestampHeader,
            @RequestHeader(value = "X-Correlation-Id",    required = false) String correlationId,
            @RequestHeader(value = "X-Source-System",     defaultValue = "ido") String sourceSystem,
            @RequestBody String rawBody) {

        String inboundId = UuidV7.generate();
        log.info("[WebhookInbound] 수신: inboundId={} correlationId={} bodyLen={}",
                inboundId, correlationId, rawBody.length());

        // ① 타임스탬프 검증 (재사용 공격 방지)
        if (!isTimestampValid(timestampHeader)) {
            log.warn("[WebhookInbound] 타임스탬프 유효하지 않음: timestamp={} inboundId={}", timestampHeader, inboundId);
            return ResponseEntity.status(401)
                    .body(Map.of("error", "INVALID_TIMESTAMP", "inboundId", inboundId));
        }

        // ② HMAC-SHA256 서명 검증
        boolean signatureValid = verifySignature(timestampHeader, rawBody, signatureHeader);
        if (!signatureValid) {
            log.warn("[WebhookInbound] 서명 검증 실패: inboundId={} sigHeader={}", inboundId,
                    signatureHeader != null ? signatureHeader.substring(0, Math.min(16, signatureHeader.length())) + "..." : "null");
        }

        // ③ payload 파싱
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(rawBody, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("[WebhookInbound] payload 파싱 실패: inboundId={} err={}", inboundId, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "INVALID_PAYLOAD", "inboundId", inboundId));
        }

        String sourceEventId   = str(payload, "eventId");
        String sourceEventType = str(payload, "eventType");

        if (sourceEventId == null || sourceEventType == null) {
            log.warn("[WebhookInbound] eventId/eventType 누락: inboundId={}", inboundId);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "MISSING_EVENT_FIELDS", "inboundId", inboundId));
        }

        // ④ webhook_inbound 중복 체크 (at-least-once 방지)
        if (isDuplicate(sourceEventId, agencyCode)) {
            log.info("[WebhookInbound] 중복 이벤트 스킵: sourceEventId={} agencyCode={}", sourceEventId, agencyCode);
            return ResponseEntity.ok(Map.of("status", "ALREADY_PROCESSED", "inboundId", inboundId));
        }

        // ⑤ webhook_inbound DB 기록
        saveInbound(inboundId, sourceEventId, sourceEventType, correlationId,
                rawBody, signatureValid, signatureHeader, timestampHeader);

        // ⑥ 서명 실패 이벤트는 기록은 하되 비즈니스 처리 스킵
        if (!signatureValid) {
            updateInboundStatus(inboundId, "SKIPPED", "SIGNATURE_INVALID");
            return ResponseEntity.status(401)
                    .body(Map.of("error", "SIGNATURE_VERIFICATION_FAILED", "inboundId", inboundId));
        }

        // ⑦ 이벤트 타입별 비즈니스 처리
        try {
            processEvent(inboundId, sourceEventId, sourceEventType, payload, correlationId);
            updateInboundStatus(inboundId, "PROCESSED", null);
            log.info("[WebhookInbound] 처리 완료: inboundId={} eventType={}", inboundId, sourceEventType);
        } catch (Exception e) {
            log.error("[WebhookInbound] 처리 실패: inboundId={} eventType={} err={}",
                    inboundId, sourceEventType, e.getMessage(), e);
            updateInboundStatus(inboundId, "FAILED", truncate(e.getMessage(), 500));
            // 200 반환 — IdO가 재시도하지 않도록 (비즈니스 오류는 기관이 직접 처리)
        }

        return ResponseEntity.ok(Map.of("status", "ACCEPTED", "inboundId", inboundId));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 이벤트 타입별 처리
    // ══════════════════════════════════════════════════════════════════════

    private void processEvent(String inboundId, String sourceEventId,
                               String eventType, Map<String, Object> payload,
                               String correlationId) {
        switch (eventType) {
            case "HANDOFF_ISSUED"    -> processHandoffIssued(inboundId, payload, correlationId);
            case "HANDOFF_CONSUMED"  -> processHandoffConsumed(inboundId, payload, correlationId);
            case "HANDOFF_REVOKED"   -> processHandoffRevoked(inboundId, payload, correlationId);
            case "HANDOFF_EXPIRED"   -> processHandoffExpired(inboundId, payload, correlationId);
            case "SESSION_ADVISORY"  -> processSessionAdvisory(inboundId, payload, correlationId);
            case "MEMBER_REGISTERED" -> processMemberRegistered(inboundId, payload, correlationId);
            case "MEMBER_WITHDRAWN"  -> processMemberWithdrawn(inboundId, payload, correlationId);
            default -> {
                log.info("[WebhookInbound] 알 수 없는 이벤트 타입 스킵: eventType={} inboundId={}",
                        eventType, inboundId);
                enqueueEvent(inboundId, eventType, payload, correlationId, 8);
            }
        }
    }

    /** HANDOFF_ISSUED: 기관 앱이 폴링으로 수신할 수 있도록 큐 적재 */
    private void processHandoffIssued(String inboundId, Map<String, Object> payload, String correlationId) {
        String ticketId = str(payload, "ticketId");
        log.info("[WebhookInbound] HANDOFF_ISSUED: ticketId={} correlationId={}", ticketId, correlationId);
        // priority=3: 높은 우선순위 (사용자 인증 대기 중)
        enqueueEvent(inboundId, "HANDOFF_ISSUED", payload, correlationId, 3);
    }

    /** HANDOFF_CONSUMED: 정상 소비 — 큐 적재 (감사용) */
    private void processHandoffConsumed(String inboundId, Map<String, Object> payload, String correlationId) {
        enqueueEvent(inboundId, "HANDOFF_CONSUMED", payload, correlationId, 7);
    }

    /** HANDOFF_REVOKED: 보안 사유 취소 → 해당 ticketId 세션 즉시 무효화 */
    private void processHandoffRevoked(String inboundId, Map<String, Object> payload, String correlationId) {
        String ticketId     = str(payload, "ticketId");
        String revokeReason = str(payload, "revokeReason");

        log.warn("[WebhookInbound] HANDOFF_REVOKED: ticketId={} reason={} correlationId={}",
                ticketId, revokeReason, correlationId);

        if (ticketId != null) {
            int invalidated = agencySessionService.invalidateByTicketId(
                    ticketId, "HANDOFF_REVOKED:" + revokeReason, correlationId);
            log.warn("[WebhookInbound] REVOKED 세션 무효화 {}건: ticketId={}", invalidated, ticketId);
        }

        // priority=1: 최고 우선순위 (보안 이벤트)
        enqueueEvent(inboundId, "HANDOFF_REVOKED", payload, correlationId, 1);
    }

    /** HANDOFF_EXPIRED: 만료 — 캐시 정리 큐 적재 */
    private void processHandoffExpired(String inboundId, Map<String, Object> payload, String correlationId) {
        enqueueEvent(inboundId, "HANDOFF_EXPIRED", payload, correlationId, 8);
    }

    /** SESSION_ADVISORY: 세션 경고 또는 강제 종료 */
    private void processSessionAdvisory(String inboundId, Map<String, Object> payload, String correlationId) {
        String severity  = str(payload, "severity");
        String qimUserId = str(payload, "qimUserId");
        String reason    = str(payload, "reason");

        log.warn("[WebhookInbound] SESSION_ADVISORY: severity={} qimUserId={} correlationId={}",
                severity, qimUserId, correlationId);

        if ("Mandatory".equalsIgnoreCase(severity) && qimUserId != null) {
            int invalidated = agencySessionService.invalidateByQimUserId(
                    qimUserId, "MANDATORY_SECURITY_TERMINATE", correlationId);
            log.warn("[WebhookInbound] MANDATORY 세션 무효화 {}건: qimUserId={}", invalidated, qimUserId);
            enqueueEvent(inboundId, "SESSION_TERMINATED", payload, correlationId, 1);
        } else {
            enqueueEvent(inboundId, "SESSION_ADVISORY", payload, correlationId, 4);
        }
    }

    /** MEMBER_REGISTERED: 회원 등록 알림 큐 적재 */
    private void processMemberRegistered(String inboundId, Map<String, Object> payload, String correlationId) {
        log.info("[WebhookInbound] MEMBER_REGISTERED: correlationId={}", correlationId);
        enqueueEvent(inboundId, "MEMBER_REGISTERED", payload, correlationId, 5);
    }

    /** MEMBER_WITHDRAWN: 회원 탈퇴 → 관련 세션 일괄 무효화 */
    private void processMemberWithdrawn(String inboundId, Map<String, Object> payload, String correlationId) {
        String instMbrId = str(payload, "instMbrId");
        log.warn("[WebhookInbound] MEMBER_WITHDRAWN: instMbrId={} correlationId={}", instMbrId, correlationId);
        // instMbrId → agencySubjectId 매핑하여 세션 무효화 (agencySubjectId는 instMbrId와 동일 처리)
        if (instMbrId != null) {
            jdbcTemplate.update("""
                    UPDATE agency_stub.agency_local_session als
                    SET    invalidated_at = NOW(), invalidate_reason = 'MEMBER_WITHDRAWN'
                    FROM   agency_stub.agency_user au
                    WHERE  als.agency_user_id = au.agency_user_id
                      AND  au.agency_subject_id = ?
                      AND  als.invalidated_at IS NULL
                    """, instMbrId);
        }
        enqueueEvent(inboundId, "MEMBER_WITHDRAWN", payload, correlationId, 2);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 내부 헬퍼
    // ══════════════════════════════════════════════════════════════════════

    /** HMAC-SHA256 서명 검증 */
    private boolean verifySignature(String timestampHeader, String body, String signatureHeader) {
        if (signatureHeader == null || timestampHeader == null) return false;
        try {
            String signTarget = timestampHeader + "." + body;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmac = mac.doFinal(signTarget.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(hmac);
            return MessageDigestCompare.equal(expected, signatureHeader);
        } catch (Exception e) {
            log.warn("[WebhookInbound] 서명 계산 실패: {}", e.getMessage());
            return false;
        }
    }

    /** 타임스탬프 유효성 검증 (±5분) */
    private boolean isTimestampValid(String timestampHeader) {
        if (timestampHeader == null) return false;
        try {
            long ts    = Long.parseLong(timestampHeader);
            long now   = Instant.now().getEpochSecond();
            long drift = Math.abs(now - ts);
            return drift <= MAX_TIMESTAMP_DRIFT_SEC;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isDuplicate(String sourceEventId, String agency) {
        try {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(1) FROM agency_stub.webhook_inbound
                    WHERE source_event_id = ? AND agency_code = ?
                    """, Integer.class, sourceEventId, agency);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void saveInbound(String inboundId, String sourceEventId, String sourceEventType,
                              String correlationId, String rawBody,
                              boolean signatureValid, String signatureHeader, String timestampHeader) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO agency_stub.webhook_inbound
                        (inbound_id, source_event_id, source_event_type,
                         agency_code, correlation_id, payload_json,
                         signature_valid, signature_header, timestamp_header,
                         processing_status, received_at)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, 'PENDING', NOW())
                    """,
                    inboundId, sourceEventId, sourceEventType,
                    agencyCode, correlationId, rawBody,
                    signatureValid, signatureHeader, timestampHeader);
        } catch (Exception e) {
            log.error("[WebhookInbound] inbound 저장 실패: inboundId={} err={}", inboundId, e.getMessage());
        }
    }

    private void updateInboundStatus(String inboundId, String status, String error) {
        try {
            jdbcTemplate.update("""
                    UPDATE agency_stub.webhook_inbound
                    SET    processing_status = ?, processing_error = ?, processed_at = NOW()
                    WHERE  inbound_id = ?
                    """, status, truncate(error, 500), inboundId);
        } catch (Exception e) {
            log.warn("[WebhookInbound] status 갱신 실패: inboundId={}", inboundId);
        }
    }

    private void enqueueEvent(String inboundId, String eventType,
                               Map<String, Object> payload, String correlationId, int priority) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            jdbcTemplate.update("""
                    INSERT INTO agency_stub.agency_event_queue
                        (event_queue_id, inbound_id, agency_code, event_type,
                         correlation_id, event_payload, priority, created_at)
                    VALUES (gen_random_uuid()::text, ?, ?, ?, ?, ?::jsonb, ?, NOW())
                    """,
                    inboundId, agencyCode, eventType, correlationId, payloadJson, priority);
        } catch (Exception e) {
            log.warn("[WebhookInbound] 이벤트 큐 적재 실패: eventType={} err={}", eventType, e.getMessage());
        }
    }

    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private String truncate(String s, int max) {
        return (s != null && s.length() > max) ? s.substring(0, max) + "..." : s;
    }

    /** 타이밍 공격 방지용 상수 시간 문자열 비교 */
    private static class MessageDigestCompare {
        static boolean equal(String a, String b) {
            if (a == null || b == null) return false;
            byte[] ab = a.getBytes(StandardCharsets.UTF_8);
            byte[] bb = b.getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(ab, bb);
        }

        static boolean isEqual(byte[] a, byte[] b) {
            return java.security.MessageDigest.isEqual(a, b);
        }
    }
}

package io.github.hipstermin.idem.tenant.mock;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * BRIDGE 패턴 Mock 서버 컨트롤러 — {@code @Profile("bridge")}
 *
 * <p><b>목적</b>: 폐쇄망 기관이 운영하는 Bridge 서버를 흉내 냅니다.
 * {@link io.github.hipstermin.idem.hub.handoff.strategy.BridgeHandoffStrategy}가 호출하는
 * 두 엔드포인트를 구현합니다.
 *
 * <p><b>엔드포인트</b>:
 * <ul>
 *   <li>{@code POST /mock/bridge/api/handoff/push}   — IdO → Bridge Push 수신, in-memory 저장</li>
 *   <li>{@code GET  /mock/bridge/api/handoff/{ticketId}} — 기관 내부 조회 (Pull)</li>
 *   <li>{@code GET  /mock/bridge/pending}             — 현재 저장된 Handoff 목록 조회 (진단용)</li>
 *   <li>{@code DELETE /mock/bridge/pending}           — 저장 데이터 초기화 (테스트 격리)</li>
 * </ul>
 *
 * <p><b>활성화</b>: {@code --spring.profiles.active=bridge} 또는
 * 테스트에서 {@code @ActiveProfiles("bridge")} 사용.
 *
 * <p><b>보안 주의</b>: 테스트 전용. 운영 프로파일에서 비활성화 필수.
 */
@Slf4j
@RestController
@RequestMapping("/mock/bridge")
@Profile("bridge")
public class MockBridgeController {

    /** ticketId → Handoff Payload (in-memory, 테스트 격리 단위로 초기화) */
    private final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();

    // ════════════════════════════════════════════════════════════════════════
    // Push 수신
    // ════════════════════════════════════════════════════════════════════════

    /**
     * POST /mock/bridge/api/handoff/push
     *
     * <p>IdO {@code BridgeHandoffStrategy}가 {@code bridgeEndpoint + /api/handoff/push}로
     * 전송하는 Handoff Payload를 수신합니다.
     *
     * <p>예상 요청 헤더:
     * <ul>
     *   <li>{@code X-Agency-Code}  — 기관 코드</li>
     *   <li>{@code X-Handoff-Token} — JWT 형태의 일회성 토큰 (검증 생략, Mock)</li>
     *   <li>{@code X-Correlation-Id} — 추적 ID</li>
     * </ul>
     *
     * @param headers  HTTP 헤더 맵 (X-Agency-Code, X-Handoff-Token, X-Correlation-Id)
     * @param body     Handoff Payload JSON (ticketId, agencySubjectId, authLevel 등)
     * @return 202 Accepted + 저장된 ticketId
     */
    @PostMapping("/api/handoff/push")
    public ResponseEntity<?> receivePush(
            @RequestHeader Map<String, String> headers,
            @RequestBody Map<String, Object> body) {

        String ticketId     = safeStr(body.get("ticketId"));
        String agencyCode   = headers.getOrDefault("x-agency-code", "UNKNOWN");
        String correlationId = headers.getOrDefault("x-correlation-id", UUID.randomUUID().toString());

        if (ticketId == null || ticketId.isBlank()) {
            log.warn("[MockBridge][PUSH] ticketId 누락: correlationId={}", correlationId);
            return ResponseEntity.badRequest().body(Map.of(
                    "error",   "MISSING_TICKET_ID",
                    "message", "ticketId 는 필수 항목입니다"
            ));
        }

        Map<String, Object> entry = new LinkedHashMap<>(body);
        entry.put("_receivedAt",   Instant.now().toString());
        entry.put("_agencyCode",   agencyCode);
        entry.put("_correlationId", correlationId);
        store.put(ticketId, entry);

        log.info("[MockBridge][PUSH] 수신 OK: ticketId={} agencyCode={} correlationId={}",
                ticketId, agencyCode, correlationId);

        return ResponseEntity.accepted().body(Map.of(
                "status",        "ACCEPTED",
                "ticketId",      ticketId,
                "receivedAt",    entry.get("_receivedAt"),
                "correlationId", correlationId
        ));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Pull 조회 (기관 내부 시스템이 Bridge에서 꺼내가는 흐름)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * GET /mock/bridge/api/handoff/{ticketId}
     *
     * <p>기관 내부 업무시스템이 Bridge 서버로부터 Handoff Payload를 Pull합니다.
     * 실제 Bridge 서버와 동일한 응답 구조를 반환합니다.
     *
     * @param ticketId  조회할 ticketId (경로 변수)
     * @return 200 OK + Payload, 404 NOT_FOUND
     */
    @GetMapping("/api/handoff/{ticketId}")
    public ResponseEntity<?> pullHandoff(@PathVariable String ticketId) {
        Map<String, Object> entry = store.get(ticketId);

        if (entry == null) {
            log.warn("[MockBridge][PULL] ticketId 없음: ticketId={}", ticketId);
            return ResponseEntity.status(404).body(Map.of(
                    "error",   "TICKET_NOT_FOUND",
                    "message", "Bridge 서버에서 ticketId 를 찾을 수 없습니다: " + ticketId
            ));
        }

        log.info("[MockBridge][PULL] ticketId={} 조회 성공", ticketId);
        return ResponseEntity.ok(Map.of(
                "status",    "FOUND",
                "ticketId",  ticketId,
                "payload",   entry,
                "queriedAt", Instant.now().toString()
        ));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 진단 / 테스트 격리
    // ════════════════════════════════════════════════════════════════════════

    /**
     * GET /mock/bridge/pending
     * 현재 Bridge에 쌓여 있는 Handoff 목록 전체 조회 (테스트 진단용).
     */
    @GetMapping("/pending")
    public ResponseEntity<?> listPending() {
        log.debug("[MockBridge][PENDING] 조회: size={}", store.size());
        return ResponseEntity.ok(Map.of(
                "total",   store.size(),
                "pending", store
        ));
    }

    /**
     * DELETE /mock/bridge/pending
     * 저장된 모든 Handoff 초기화 (단위 테스트 격리용).
     */
    @DeleteMapping("/pending")
    public ResponseEntity<?> clearPending() {
        int before = store.size();
        store.clear();
        log.info("[MockBridge][CLEAR] {} 건 삭제", before);
        return ResponseEntity.ok(Map.of(
                "status",  "CLEARED",
                "removed", before
        ));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 헬퍼
    // ════════════════════════════════════════════════════════════════════════

    private String safeStr(Object o) { return o != null ? o.toString() : null; }
}

package io.github.hipstermin.idem.tenant.mock;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * INTERNAL_SSO 패턴 Mock SSO 세션 수신 컨트롤러 — {@code @Profile("bridge")}
 *
 * <p><b>목적</b>: 기관 내부 SSO 서버를 흉내 냅니다.
 * {@link io.github.hipstermin.idem.hub.handoff.strategy.InternalSsoHandoffStrategy}가
 * {@code ssoDomain + /internal/sso-session}으로 전송하는 세션 사전 등록 요청을 수신합니다.
 *
 * <p><b>흐름 (설계서 §8.4)</b>:
 * <pre>
 *   IdO issues Ticket
 *     → InternalSsoHandoffStrategy.postIssue()
 *         → POST {ssoDomain}/internal/sso-session
 *               headers: X-Agency-Code, X-Correlation-Id, X-Source-System
 *               body:    { ticketId, qimUserId, authLevel, expiresAt, correlationId }
 *     → (Mock) 세션 사전 등록 in-memory 저장
 *     → 사용자 콜백 도달 시 SSO 쿠키 자동 발급 흉내
 * </pre>
 *
 * <p><b>엔드포인트</b>:
 * <ul>
 *   <li>{@code POST /internal/sso-session}           — SSO 세션 사전 등록 수신</li>
 *   <li>{@code GET  /internal/sso-session/{ticketId}} — 등록된 세션 확인 (진단)</li>
 *   <li>{@code GET  /internal/sso-session}            — 전체 등록 목록 조회 (진단)</li>
 *   <li>{@code DELETE /internal/sso-session}          — 등록 목록 초기화 (테스트 격리)</li>
 * </ul>
 *
 * <p><b>검증 항목</b>:
 * <ul>
 *   <li>{@code X-Agency-Code}     — 반드시 존재</li>
 *   <li>{@code X-Source-System}   — 반드시 존재 (IdO 식별)</li>
 *   <li>body {@code ticketId}     — 반드시 존재</li>
 * </ul>
 *
 * <p><b>보안 주의</b>: 테스트 전용. 운영 프로파일에서 비활성화 필수.
 */
@Slf4j
@RestController
@RequestMapping("/internal/sso-session")
@Profile("bridge")
public class MockSsoSessionController {

    /** ticketId → SSO 사전 등록 세션 (in-memory) */
    private final Map<String, Map<String, Object>> ssoSessionStore = new ConcurrentHashMap<>();

    // ════════════════════════════════════════════════════════════════════════
    // SSO 세션 사전 등록 수신
    // ════════════════════════════════════════════════════════════════════════

    /**
     * POST /internal/sso-session
     *
     * <p>IdO {@code InternalSsoHandoffStrategy.postIssue()}가 전송하는
     * SSO 세션 사전 등록 요청을 수신합니다.
     *
     * <p>필수 헤더:
     * <ul>
     *   <li>{@code X-Agency-Code}   — 기관 코드</li>
     *   <li>{@code X-Source-System} — 발신 시스템 (IdO)</li>
     *   <li>{@code X-Correlation-Id} — 추적 ID</li>
     * </ul>
     *
     * <p>필수 Body 필드:
     * <ul>
     *   <li>{@code ticketId}   — 발급된 Handoff Ticket ID</li>
     *   <li>{@code qimUserId}  — QIM 사용자 ID</li>
     *   <li>{@code authLevel}  — 인증 수준 (L1/L2/L3)</li>
     *   <li>{@code expiresAt}  — Ticket 만료 시각 (ISO-8601)</li>
     * </ul>
     *
     * @param headers  HTTP 헤더 맵
     * @param body     SSO 세션 등록 요청 Body
     * @return 201 Created + 등록 결과, 400 필수 항목 누락
     */
    @PostMapping
    public ResponseEntity<?> registerSsoSession(
            @RequestHeader Map<String, String> headers,
            @RequestBody Map<String, Object> body) {

        String agencyCode    = headers.get("x-agency-code");
        String sourceSystem  = headers.get("x-source-system");
        String correlationId = headers.getOrDefault("x-correlation-id", UUID.randomUUID().toString());
        String ticketId      = safeStr(body.get("ticketId"));

        // ── 필수 항목 검증 ───────────────────────────────────────────────
        List<String> missing = new ArrayList<>();
        if (agencyCode   == null || agencyCode.isBlank())   missing.add("X-Agency-Code (header)");
        if (sourceSystem == null || sourceSystem.isBlank()) missing.add("X-Source-System (header)");
        if (ticketId     == null || ticketId.isBlank())     missing.add("ticketId (body)");

        if (!missing.isEmpty()) {
            log.warn("[MockSsoSession][REGISTER] 필수 항목 누락: {} cid={}", missing, correlationId);
            return ResponseEntity.badRequest().body(Map.of(
                    "error",   "MISSING_REQUIRED_FIELDS",
                    "missing", missing,
                    "message", "SSO 세션 사전 등록 필수 항목이 누락되었습니다"
            ));
        }

        // ── 세션 사전 등록 저장 ──────────────────────────────────────────
        Map<String, Object> session = new LinkedHashMap<>(body);
        session.put("_agencyCode",    agencyCode);
        session.put("_sourceSystem",  sourceSystem);
        session.put("_correlationId", correlationId);
        session.put("_registeredAt",  Instant.now().toString());
        session.put("_ssoStatus",     "PRE_REGISTERED");  // 사용자 도달 시 ACTIVATED로 변경

        ssoSessionStore.put(ticketId, session);

        log.info("[MockSsoSession][REGISTER] SSO 사전 등록 OK: ticketId={} agencyCode={} authLevel={} cid={}",
                ticketId, agencyCode, body.get("authLevel"), correlationId);

        return ResponseEntity.status(201).body(Map.of(
                "status",        "PRE_REGISTERED",
                "ticketId",      ticketId,
                "agencyCode",    agencyCode,
                "registeredAt",  session.get("_registeredAt"),
                "correlationId", correlationId,
                "message",       "SSO 세션이 사전 등록되었습니다. 사용자 콜백 도달 시 자동 활성화됩니다."
        ));
    }

    // ════════════════════════════════════════════════════════════════════════
    // SSO 쿠키 활성화 시뮬레이션 (콜백 도달 흉내)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * POST /internal/sso-session/{ticketId}/activate
     *
     * <p>사용자가 콜백 URL에 도달하면 SSO 쿠키를 활성화하는 흐름을 흉내냅니다.
     * 단위 테스트에서 사전 등록 → 활성화 전체 흐름 검증에 사용.
     *
     * @param ticketId  활성화할 ticketId
     * @return 200 OK + 활성화 결과, 404 미등록
     */
    @PostMapping("/{ticketId}/activate")
    public ResponseEntity<?> activateSsoSession(@PathVariable String ticketId) {
        Map<String, Object> session = ssoSessionStore.get(ticketId);

        if (session == null) {
            log.warn("[MockSsoSession][ACTIVATE] ticketId 없음: ticketId={}", ticketId);
            return ResponseEntity.status(404).body(Map.of(
                    "error",   "SSO_SESSION_NOT_FOUND",
                    "message", "사전 등록된 SSO 세션이 없습니다: " + ticketId
            ));
        }

        session.put("_ssoStatus",    "ACTIVATED");
        session.put("_activatedAt",  Instant.now().toString());

        log.info("[MockSsoSession][ACTIVATE] SSO 세션 활성화: ticketId={}", ticketId);
        return ResponseEntity.ok(Map.of(
                "status",      "ACTIVATED",
                "ticketId",    ticketId,
                "activatedAt", session.get("_activatedAt"),
                "session",     session
        ));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 진단 / 테스트 격리
    // ════════════════════════════════════════════════════════════════════════

    /**
     * GET /internal/sso-session/{ticketId}
     * 특정 ticketId의 SSO 사전 등록 세션 조회 (테스트 진단용).
     */
    @GetMapping("/{ticketId}")
    public ResponseEntity<?> getSession(@PathVariable String ticketId) {
        Map<String, Object> session = ssoSessionStore.get(ticketId);
        if (session == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error",   "NOT_FOUND",
                    "ticketId", ticketId
            ));
        }
        return ResponseEntity.ok(Map.of(
                "ticketId", ticketId,
                "session",  session
        ));
    }

    /**
     * GET /internal/sso-session
     * 전체 등록된 SSO 세션 목록 조회 (테스트 진단용).
     */
    @GetMapping
    public ResponseEntity<?> listSessions() {
        return ResponseEntity.ok(Map.of(
                "total",    ssoSessionStore.size(),
                "sessions", ssoSessionStore
        ));
    }

    /**
     * DELETE /internal/sso-session
     * 전체 SSO 세션 초기화 (단위 테스트 격리용).
     */
    @DeleteMapping
    public ResponseEntity<?> clearSessions() {
        int before = ssoSessionStore.size();
        ssoSessionStore.clear();
        log.info("[MockSsoSession][CLEAR] {} 건 삭제", before);
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

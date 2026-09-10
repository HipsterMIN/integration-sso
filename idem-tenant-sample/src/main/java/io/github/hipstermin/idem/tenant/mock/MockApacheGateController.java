package io.github.hipstermin.idem.tenant.mock;

import java.time.Instant;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * APACHE_GATE 패턴 Mock 수신 컨트롤러 — {@code @Profile("bridge")}
 *
 * <p><b>목적</b>: 레거시 Apache httpd + mod_auth 환경에서 동작하는 Apache Gate를 흉내 냅니다.
 * {@link io.github.hipstermin.idem.hub.handoff.strategy.ApacheGateHandoffStrategy}가 전달하는
 * 커스텀 헤더(X-Remote-User, X-Auth-Level, X-Handoff-Token, X-Session-Expiry)를 검증합니다.
 *
 * <p><b>엔드포인트</b>:
 * <ul>
 *   <li>{@code POST /mock/apache-gate}          — Apache Gate 헤더 수신 + 검증</li>
 *   <li>{@code GET  /mock/apache-gate/received} — 수신 로그 조회 (진단용)</li>
 *   <li>{@code DELETE /mock/apache-gate/received} — 수신 로그 초기화 (테스트 격리)</li>
 * </ul>
 *
 * <p><b>검증 항목</b>:
 * <ul>
 *   <li>{@code X-Remote-User}   — 반드시 존재</li>
 *   <li>{@code X-Auth-Level}    — L1 / L2 / L3 중 하나</li>
 *   <li>{@code X-Handoff-Token} — 반드시 존재 (JWT 구조 검증 생략, Mock)</li>
 *   <li>{@code X-Session-Expiry} — ISO-8601 형식 권장 (검증 생략, 로그만)</li>
 * </ul>
 *
 * <p><b>보안 주의</b>: 테스트 전용. 운영 프로파일에서 비활성화 필수.
 */
@Slf4j
@RestController
@RequestMapping("/mock/apache-gate")
@Profile("bridge")
public class MockApacheGateController {

    /** 수신된 Apache Gate 요청 로그 (in-memory) */
    private final List<Map<String, Object>> receivedLog = Collections.synchronizedList(new ArrayList<>());

    private static final Set<String> VALID_AUTH_LEVELS = Set.of("L1", "L2", "L3");

    // ════════════════════════════════════════════════════════════════════════
    // Apache Gate 헤더 수신
    // ════════════════════════════════════════════════════════════════════════

    /**
     * POST /mock/apache-gate
     *
     * <p>IdO {@code ApacheGateHandoffStrategy}가 전달하는 커스텀 헤더를 수신합니다.
     * Apache httpd + mod_auth 환경에서 기관 WAS로 전달되는 헤더를 검증합니다.
     *
     * <p>필수 헤더:
     * <ul>
     *   <li>{@code X-Remote-User}    — 기관 식별 사용자 ID</li>
     *   <li>{@code X-Auth-Level}     — 인증 수준 (L1/L2/L3)</li>
     *   <li>{@code X-Handoff-Token}  — 일회성 Handoff JWT</li>
     *   <li>{@code X-Session-Expiry} — 세션 만료 시간 (참조용)</li>
     * </ul>
     *
     * @param headers 전체 HTTP 헤더 맵
     * @param body    선택적 Body (빈 POST 허용)
     * @return 200 OK + 검증 결과, 400 헤더 누락
     */
    @PostMapping
    public ResponseEntity<?> receiveApacheGate(
            @RequestHeader Map<String, String> headers,
            @RequestBody(required = false) Map<String, Object> body) {

        String remoteUser    = headers.get("x-remote-user");
        String authLevel     = headers.get("x-auth-level");
        String handoffToken  = headers.get("x-handoff-token");
        String sessionExpiry = headers.get("x-session-expiry");
        String correlationId = headers.getOrDefault("x-correlation-id", UUID.randomUUID().toString());

        // ── 필수 헤더 검증 ──────────────────────────────────────────────
        List<String> missing = new ArrayList<>();
        if (remoteUser   == null || remoteUser.isBlank())   missing.add("X-Remote-User");
        if (authLevel    == null || authLevel.isBlank())    missing.add("X-Auth-Level");
        if (handoffToken == null || handoffToken.isBlank()) missing.add("X-Handoff-Token");

        if (!missing.isEmpty()) {
            log.warn("[MockApacheGate] 필수 헤더 누락: {} correlationId={}", missing, correlationId);
            return ResponseEntity.badRequest().body(Map.of(
                    "error",   "MISSING_REQUIRED_HEADERS",
                    "missing", missing,
                    "message", "Apache Gate 필수 헤더가 누락되었습니다"
            ));
        }

        // ── Auth Level 값 검증 ───────────────────────────────────────────
        if (!VALID_AUTH_LEVELS.contains(authLevel.toUpperCase())) {
            log.warn("[MockApacheGate] 잘못된 X-Auth-Level: {} correlationId={}", authLevel, correlationId);
            return ResponseEntity.badRequest().body(Map.of(
                    "error",        "INVALID_AUTH_LEVEL",
                    "received",     authLevel,
                    "validValues",  VALID_AUTH_LEVELS,
                    "message",      "X-Auth-Level 은 L1/L2/L3 중 하나여야 합니다"
            ));
        }

        // ── 수신 로그 저장 ───────────────────────────────────────────────
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("receivedAt",    Instant.now().toString());
        entry.put("correlationId", correlationId);
        entry.put("xRemoteUser",   remoteUser);
        entry.put("xAuthLevel",    authLevel);
        entry.put("xHandoffToken", handoffToken.length() > 20
                ? handoffToken.substring(0, 20) + "…" : handoffToken);  // 로그 단축
        entry.put("xSessionExpiry", sessionExpiry);
        entry.put("body",          body);
        receivedLog.add(entry);

        log.info("[MockApacheGate] 수신 OK: remoteUser={} authLevel={} correlationId={}",
                remoteUser, authLevel, correlationId);

        return ResponseEntity.ok(Map.of(
                "status",        "ACCEPTED",
                "validation",    "PASSED",
                "xRemoteUser",   remoteUser,
                "xAuthLevel",    authLevel,
                "xSessionExpiry", sessionExpiry != null ? sessionExpiry : "(없음)",
                "receivedAt",    entry.get("receivedAt"),
                "correlationId", correlationId
        ));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 진단 / 테스트 격리
    // ════════════════════════════════════════════════════════════════════════

    /**
     * GET /mock/apache-gate/received
     * 수신된 Apache Gate 요청 로그 전체 조회 (테스트 진단용).
     */
    @GetMapping("/received")
    public ResponseEntity<?> listReceived() {
        return ResponseEntity.ok(Map.of(
                "total",    receivedLog.size(),
                "received", receivedLog
        ));
    }

    /**
     * DELETE /mock/apache-gate/received
     * 수신 로그 초기화 (단위 테스트 격리용).
     */
    @DeleteMapping("/received")
    public ResponseEntity<?> clearReceived() {
        int before = receivedLog.size();
        receivedLog.clear();
        log.info("[MockApacheGate][CLEAR] {} 건 삭제", before);
        return ResponseEntity.ok(Map.of(
                "status",  "CLEARED",
                "removed", before
        ));
    }
}

package kr.go.smes.agency.simulator;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.go.smes.agency.client.IdoTicketClient;
import kr.go.smes.agency.client.IdoTicketClient.IdoTicketIssuanceException;
import kr.go.smes.agency.client.IdoTicketClient.TicketResult;
import kr.go.smes.agency.client.IdoVerifyClient;
import kr.go.smes.agency.session.AgencySessionService;
import kr.go.smes.agency.session.AgencySessionService.SessionCreateResult;
import kr.go.smes.common.domain.HandoffPayload;
import kr.go.smes.common.util.CorrelationIdHolder;
import kr.go.smes.common.util.UuidV7;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 유관기관 E2E 테스트 시뮬레이터 컨트롤러
 *
 * <p><b>목적</b>: 실제 유관기관이 IdO 와 연동하는 전체 흐름을 agency-stub 내부에서
 * 재현합니다. 외부 QIM 인증이 없이도 아래 3단계 흐름을 하나의 API 호출로 시뮬레이션합니다.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  agency-stub 시뮬레이터 E2E 흐름                                         │
 * │                                                                         │
 * │  POST /api/v1/simulator/run                                             │
 * │    │                                                                    │
 * │    ├─ [STEP 1] IdoTicketClient.issue()                                  │
 * │    │     POST {ido}/api/v1/handoff/issue                                │
 * │    │     Headers: X-Agency-Code, X-Agency-Key (검증됨)                  │
 * │    │     → ticketId, expiresAt                                          │
 * │    │                                                                    │
 * │    ├─ [STEP 2] IdoVerifyClient.verify(ticketId)                         │
 * │    │     POST {ido}/api/v1/handoff/verify                               │
 * │    │     Headers: X-Agency-Code, X-Agency-Key (IdO 측 인터셉터 검증)    │
 * │    │     Resilience4j CB + Retry 적용                                    │
 * │    │     → HandoffPayload (APPROVED / REJECTED / HOLD)                  │
 * │    │                                                                    │
 * │    ├─ [STEP 3] AgencySessionService.createSession()                     │
 * │    │     192-bit SecureRandom AGSID → SHA-256 저장                      │
 * │    │     → agencyUserId, agencySubjectId, rawAgsid                      │
 * │    │                                                                    │
 * │    └─ AGSID 쿠키 발급 (Secure/HttpOnly/SameSite=Strict)                 │
 * │         + SimulationResult JSON 반환                                     │
 * └─────────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>추가 엔드포인트</b>:
 * <ul>
 *   <li>{@code GET  /api/v1/simulator/status}   — 시뮬레이터 상태 및 연결 진단</li>
 *   <li>{@code POST /api/v1/simulator/ticket}   — Step 1 만 수행 (Ticket 발급)</li>
 *   <li>{@code POST /api/v1/simulator/verify}   — Step 2 만 수행 (Ticket 검증)</li>
 *   <li>{@code GET  /api/v1/simulator/sessions} — 최근 시뮬레이션 세션 목록</li>
 *   <li>{@code DELETE /api/v1/simulator/sessions/{sessionId}} — 세션 강제 무효화</li>
 * </ul>
 *
 * <p><b>보안 주의</b>: 이 컨트롤러는 PoC / 테스트 전용입니다.
 * 운영 배포 시 {@code spring.profiles.active=prod} 프로파일에서 비활성화하거나
 * 내부망 접근만 허용해야 합니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/simulator")
@RequiredArgsConstructor
public class AgencySimulatorController {

    private static final String COOKIE_NAME = "AGSID";

    private final IdoTicketClient      idoTicketClient;
    private final IdoVerifyClient      idoVerifyClient;
    private final AgencySessionService agencySessionService;
    private final JdbcTemplate         jdbcTemplate;

    @Value("${agency-stub.code:AGENCY_STUB_001}")
    private String agencyCode;

    @Value("${agency-stub.ido.base-url:http://localhost:8083}")
    private String idoBaseUrl;

    @Value("${agency-stub.session.idle-timeout-minutes:30}")
    private int idleTimeoutMinutes;

    // ════════════════════════════════════════════════════════════════════════
    // E2E 전체 흐름 시뮬레이션
    // ════════════════════════════════════════════════════════════════════════

    /**
     * POST /api/v1/simulator/run
     *
     * <p>Ticket 발급 → Verify → 세션 생성까지 3단계를 한 번에 수행합니다.
     *
     * @param req  시뮬레이션 파라미터
     * @param httpReq  Servlet request
     * @param httpResp Servlet response (AGSID 쿠키 발급)
     */
    @PostMapping("/run")
    public ResponseEntity<?> runFullFlow(
            @RequestBody(required = false) SimulationRequest req,
            HttpServletRequest  httpReq,
            HttpServletResponse httpResp) {

        if (req == null) req = SimulationRequest.defaults();

        String cid = CorrelationIdHolder.generate();
        CorrelationIdHolder.set(cid);

        String ip = extractClientIp(httpReq);
        String ua = httpReq.getHeader("User-Agent");

        log.info("[Simulator] === E2E 시뮬레이션 시작 === qimUserId={} authLevel={} correlationId={}",
                req.getQimUserId(), req.getAuthLevel(), cid);

        SimulationResultBuilder result = SimulationResultBuilder.start(cid, req);

        // ── STEP 1: Ticket 발급 ─────────────────────────────────────────
        TicketResult ticket;
        try {
            result.stepStart("TICKET_ISSUE");
            ticket = idoTicketClient.issue(
                    req.getQimUserId(),
                    req.getAuthResultId(),
                    req.getAuthLevel(),
                    req.getProviderCode(),
                    cid
            );
            result.stepOk("TICKET_ISSUE", Map.of(
                    "ticketId",  ticket.ticketId(),
                    "expiresAt", ticket.expiresAt() != null ? ticket.expiresAt().toString() : ""
            ));
            log.info("[Simulator] STEP 1 완료: ticketId={}", ticket.ticketId());

        } catch (IdoTicketIssuanceException e) {
            log.error("[Simulator] STEP 1 실패: {}", e.getMessage());
            return ResponseEntity.status(502)
                    .body(result.failed("TICKET_ISSUE", e.getErrorCode(), e.getMessage()));

        } catch (Exception e) {
            log.error("[Simulator] STEP 1 예외: {}", e.getMessage(), e);
            return ResponseEntity.status(502)
                    .body(result.failed("TICKET_ISSUE", "UNEXPECTED_ERROR", e.getMessage()));
        }

        // ── STEP 2: Ticket 검증 ─────────────────────────────────────────
        HandoffPayload payload;
        try {
            result.stepStart("TICKET_VERIFY");
            payload = idoVerifyClient.verify(ticket.ticketId(), cid);
            result.stepOk("TICKET_VERIFY", Map.of(
                    "state",           payload.getState() != null ? payload.getState().name() : "null",
                    "agencySubjectId", safeStr(payload.getSubject() != null
                            ? payload.getSubject().getAgencySubjectId() : null),
                    "authLevel",       safeStr(payload.getAuthContext() != null
                            ? payload.getAuthContext().getAuthLevel() : null)
            ));
            log.info("[Simulator] STEP 2 완료: state={}", payload.getState());

        } catch (Exception e) {
            log.error("[Simulator] STEP 2 예외: {}", e.getMessage(), e);
            return ResponseEntity.status(502)
                    .body(result.failed("TICKET_VERIFY", "VERIFY_EXCEPTION", e.getMessage()));
        }

        // HandoffState 검증
        if (payload.getState() == null) {
            return ResponseEntity.status(502)
                    .body(result.failed("TICKET_VERIFY", "NULL_STATE", "Verify 응답 state 가 null"));
        }
        switch (payload.getState()) {
            case HOLD -> {
                return ResponseEntity.status(503)
                        .body(result.failed("TICKET_VERIFY", "HOLD",
                                "IdO 서비스 일시 불가 — 잠시 후 재시도하세요"));
            }
            case REJECTED, MANUAL_REVIEW -> {
                return ResponseEntity.status(403)
                        .body(result.failed("TICKET_VERIFY", payload.getState().name(),
                                "Handoff 거부됨"));
            }
            case APPROVED -> { /* 계속 */ }
            default -> {
                return ResponseEntity.status(502)
                        .body(result.failed("TICKET_VERIFY", "UNKNOWN_STATE",
                                "알 수 없는 state: " + payload.getState()));
            }
        }

        // ── STEP 3: 세션 생성 ──────────────────────────────────────────
        SessionCreateResult session;
        try {
            result.stepStart("SESSION_CREATE");

            // 기존 AGSID 쿠키 무효화 (Session Fixation 방지)
            String existingAgsid = extractCookieValue(httpReq, COOKIE_NAME);
            if (existingAgsid != null) {
                agencySessionService.invalidateByAgsid(existingAgsid, "SESSION_FIXATION_PREVENTION", cid);
                clearCookie(COOKIE_NAME, httpResp);
            }

            session = agencySessionService.createSession(payload, ticket.ticketId(), cid, ip, ua);
            result.stepOk("SESSION_CREATE", Map.of(
                    "sessionId",       session.sessionId(),
                    "agencyUserId",    session.agencyUserId(),
                    "agencySubjectId", session.agencySubjectId(),
                    "authLevel",       session.authLevel()
            ));
            log.info("[Simulator] STEP 3 완료: sessionId={}", session.sessionId());

        } catch (Exception e) {
            log.error("[Simulator] STEP 3 실패: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(result.failed("SESSION_CREATE", "SESSION_FAILED", e.getMessage()));
        }

        // ── AGSID 쿠키 발급 ────────────────────────────────────────────
        ResponseCookie agsidCookie = ResponseCookie.from(COOKIE_NAME, session.rawAgsid())
                .httpOnly(true)
                .secure(false)          // 시뮬레이터: localhost 는 http
                .sameSite("Lax")        // 시뮬레이터: SameSite=Lax (크로스사이트 테스트 허용)
                .path("/")
                .maxAge(Duration.ofMinutes(idleTimeoutMinutes))
                .build();
        httpResp.addHeader(HttpHeaders.SET_COOKIE, agsidCookie.toString());

        log.info("[Simulator] === E2E 시뮬레이션 완료 === sessionId={} correlationId={}",
                session.sessionId(), cid);

        return ResponseEntity.ok(result.success(session));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Step별 단독 실행
    // ════════════════════════════════════════════════════════════════════════

    /**
     * POST /api/v1/simulator/ticket
     * Ticket 발급만 수행 (Step 1)
     */
    @PostMapping("/ticket")
    public ResponseEntity<?> issueTicket(
            @RequestBody(required = false) SimulationRequest req) {

        if (req == null) req = SimulationRequest.defaults();
        String cid = CorrelationIdHolder.generate();

        log.info("[Simulator] Ticket 발급 단독: qimUserId={} authLevel={} correlationId={}",
                req.getQimUserId(), req.getAuthLevel(), cid);
        try {
            TicketResult ticket = idoTicketClient.issue(
                    req.getQimUserId(), req.getAuthResultId(),
                    req.getAuthLevel(), req.getProviderCode(), cid);

            return ResponseEntity.ok(Map.of(
                    "step",          "TICKET_ISSUE",
                    "status",        "OK",
                    "ticketId",      ticket.ticketId(),
                    "expiresAt",     ticket.expiresAt() != null ? ticket.expiresAt().toString() : "",
                    "correlationId", cid
            ));
        } catch (IdoTicketIssuanceException e) {
            return ResponseEntity.status(502).body(Map.of(
                    "step",   "TICKET_ISSUE", "status", "FAILED",
                    "error",  e.getErrorCode(), "message", e.getMessage(),
                    "correlationId", cid));
        }
    }

    /**
     * POST /api/v1/simulator/verify?ticketId={ticketId}
     * Ticket 검증만 수행 (Step 2)
     */
    @PostMapping("/verify")
    public ResponseEntity<?> verifyTicket(
            @RequestParam String ticketId) {

        String cid = CorrelationIdHolder.generate();
        log.info("[Simulator] Ticket 검증 단독: ticketId={} correlationId={}", ticketId, cid);

        try {
            HandoffPayload payload = idoVerifyClient.verify(ticketId, cid);
            return ResponseEntity.ok(Map.of(
                    "step",            "TICKET_VERIFY",
                    "status",          "OK",
                    "handoffState",    payload.getState() != null ? payload.getState().name() : "null",
                    "agencySubjectId", safeStr(payload.getSubject() != null
                            ? payload.getSubject().getAgencySubjectId() : null),
                    "authLevel",       safeStr(payload.getAuthContext() != null
                            ? payload.getAuthContext().getAuthLevel() : null),
                    "correlationId",   cid
            ));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of(
                    "step",   "TICKET_VERIFY", "status", "FAILED",
                    "error",  "VERIFY_ERROR",  "message", e.getMessage(),
                    "correlationId", cid));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 상태 / 진단
    // ════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/v1/simulator/status
     * 시뮬레이터 상태 및 IdO 연결 진단
     */
    @GetMapping("/status")
    public ResponseEntity<?> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agencyCode",  agencyCode);
        result.put("idoBaseUrl",  idoBaseUrl);
        result.put("timestamp",   Instant.now().toString());

        // DB 연결 확인
        try {
            Integer dbCheck = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM agency_stub.agency_api_key WHERE agency_code = ?",
                    Integer.class, agencyCode);
            result.put("dbStatus",    "OK");
            result.put("apiKeyCount", dbCheck != null ? dbCheck : 0);
        } catch (Exception e) {
            result.put("dbStatus", "ERROR: " + e.getMessage());
        }

        // 세션 통계
        try {
            Map<String, Object> sessionStats = jdbcTemplate.queryForMap(
                    "SELECT COUNT(*) AS total, " +
                    "       COUNT(CASE WHEN invalidated_at IS NULL AND idle_expires_at > NOW() THEN 1 END) AS active " +
                    "FROM agency_stub.agency_local_session WHERE agency_code = ?",
                    agencyCode);
            result.put("sessionStats", sessionStats);
        } catch (Exception e) {
            result.put("sessionStats", "ERROR: " + e.getMessage());
        }

        // IdO 연결 테스트 (actuator health)
        try {
            var rt = new org.springframework.web.client.RestTemplate();
            var resp = rt.getForEntity(idoBaseUrl + "/actuator/health", String.class);
            result.put("idoHealth", resp.getStatusCode().value() == 200 ? "UP" : "DOWN");
        } catch (Exception e) {
            result.put("idoHealth", "UNREACHABLE: " + e.getMessage());
        }

        // 최근 시뮬레이션 수행 통계
        try {
            List<Map<String, Object>> recentLogs = jdbcTemplate.queryForList(
                    "SELECT event_type, COUNT(*) AS cnt " +
                    "FROM agency_stub.session_event_log " +
                    "WHERE occurred_at > NOW() - INTERVAL '1 hour' " +
                    "GROUP BY event_type ORDER BY cnt DESC LIMIT 5");
            result.put("recentEvents1h", recentLogs);
        } catch (Exception e) {
            result.put("recentEvents1h", List.of());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/v1/simulator/sessions?limit=20
     * 최근 시뮬레이션 세션 목록
     */
    @GetMapping("/sessions")
    public ResponseEntity<?> sessions(
            @RequestParam(defaultValue = "20") int limit) {

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT s.session_id, s.auth_level, s.ip_address, s.created_at, " +
                    "       s.idle_expires_at, s.invalidated_at, s.invalidate_reason, " +
                    "       u.agency_subject_id, u.qim_user_id " +
                    "FROM agency_stub.agency_local_session s " +
                    "JOIN agency_stub.agency_user u ON u.agency_user_id = s.agency_user_id " +
                    "WHERE s.agency_code = ? " +
                    "ORDER BY s.created_at DESC " +
                    "LIMIT ?",
                    agencyCode, Math.min(limit, 100));

            return ResponseEntity.ok(Map.of(
                    "total",    rows.size(),
                    "sessions", rows
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * DELETE /api/v1/simulator/sessions/{sessionId}
     * 특정 세션 강제 무효화 (테스트용)
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<?> invalidateSession(@PathVariable String sessionId) {
        String cid = CorrelationIdHolder.generate();
        try {
            int rows = jdbcTemplate.update(
                    "UPDATE agency_stub.agency_local_session " +
                    "SET invalidated_at = NOW(), invalidate_reason = 'SIMULATOR_FORCED_INVALIDATION' " +
                    "WHERE session_id = ? AND agency_code = ? AND invalidated_at IS NULL",
                    sessionId, agencyCode);

            if (rows == 0) {
                return ResponseEntity.status(404).body(Map.of(
                        "error", "SESSION_NOT_FOUND_OR_ALREADY_INVALID",
                        "sessionId", sessionId));
            }
            log.info("[Simulator] 세션 강제 무효화: sessionId={} correlationId={}", sessionId, cid);
            return ResponseEntity.ok(Map.of("status", "INVALIDATED", "sessionId", sessionId));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/v1/simulator/events?limit=20
     * 최근 이벤트 큐 항목 조회
     */
    @GetMapping("/events")
    public ResponseEntity<?> events(@RequestParam(defaultValue = "20") int limit) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT event_queue_id, event_type, priority, delivered, " +
                    "       correlation_id, created_at, delivered_at " +
                    "FROM agency_stub.agency_event_queue " +
                    "WHERE agency_code = ? " +
                    "ORDER BY created_at DESC LIMIT ?",
                    agencyCode, Math.min(limit, 100));
            return ResponseEntity.ok(Map.of("events", rows, "total", rows.size()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 내부 헬퍼
    // ════════════════════════════════════════════════════════════════════════

    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private String extractCookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst().orElse(null);
    }

    private void clearCookie(String name, HttpServletResponse response) {
        ResponseCookie clear = ResponseCookie.from(name, "")
                .maxAge(0).httpOnly(true).secure(false).sameSite("Lax").path("/").build();
        response.addHeader(HttpHeaders.SET_COOKIE, clear.toString());
    }

    private String safeStr(Object o) { return o != null ? o.toString() : ""; }

    // ════════════════════════════════════════════════════════════════════════
    // Inner DTO
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 시뮬레이션 요청 파라미터
     *
     * <p>모든 필드에 기본값이 있어 빈 body {@code {}} 로도 호출 가능.
     */
    public static class SimulationRequest {
        /** QIM 사용자 ID (없으면 임의 UUID 생성) */
        private String qimUserId    = "sim-user-" + UuidV7.generate().substring(0, 8);
        /** 인증 결과 ID (없으면 임의 UUID 생성) */
        private String authResultId = UuidV7.generate();
        /** 인증 수준: L1 / L2 / L3 */
        private String authLevel    = "L2";
        /** 인증 수단 코드 */
        private String providerCode = "QSIGN_CERT";

        public static SimulationRequest defaults() { return new SimulationRequest(); }

        public String getQimUserId()    { return qimUserId; }
        public String getAuthResultId() { return authResultId; }
        public String getAuthLevel()    { return authLevel; }
        public String getProviderCode() { return providerCode; }

        public void setQimUserId(String v)    { this.qimUserId    = v; }
        public void setAuthResultId(String v) { this.authResultId = v; }
        public void setAuthLevel(String v)    { this.authLevel    = v; }
        public void setProviderCode(String v) { this.providerCode = v; }
    }

    // ════════════════════════════════════════════════════════════════════════
    // SimulationResultBuilder — 단계별 실행 결과 집계
    // ════════════════════════════════════════════════════════════════════════

    private static class SimulationResultBuilder {
        private final String correlationId;
        private final SimulationRequest req;
        private final Instant startedAt = Instant.now();
        private final List<Map<String, Object>> steps = new ArrayList<>();

        private SimulationResultBuilder(String correlationId, SimulationRequest req) {
            this.correlationId = correlationId;
            this.req = req;
        }

        static SimulationResultBuilder start(String cid, SimulationRequest req) {
            return new SimulationResultBuilder(cid, req);
        }

        void stepStart(String stepName) {
            // 로깅 목적 — 실제 timing 은 stepOk/stepFail 에서 기록
        }

        void stepOk(String stepName, Map<String, Object> data) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("step",   stepName);
            step.put("status", "OK");
            step.putAll(data);
            steps.add(step);
        }

        Map<String, Object> failed(String stepName, String errorCode, String message) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("step",   stepName);
            step.put("status", "FAILED");
            step.put("error",  errorCode);
            step.put("message", message);
            steps.add(step);
            return buildResult("FAILED", null);
        }

        Map<String, Object> success(SessionCreateResult session) {
            return buildResult("OK", session);
        }

        private Map<String, Object> buildResult(String overallStatus, SessionCreateResult session) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status",        overallStatus);
            r.put("correlationId", correlationId);
            r.put("startedAt",     startedAt.toString());
            r.put("elapsedMs",     Instant.now().toEpochMilli() - startedAt.toEpochMilli());
            r.put("input",         Map.of(
                    "qimUserId",    req.getQimUserId(),
                    "authLevel",    req.getAuthLevel(),
                    "providerCode", req.getProviderCode()
            ));
            r.put("steps",         steps);
            if (session != null) {
                r.put("session", Map.of(
                        "sessionId",       session.sessionId(),
                        "agencyUserId",    session.agencyUserId(),
                        "agencySubjectId", session.agencySubjectId(),
                        "authLevel",       session.authLevel()
                ));
            }
            return r;
        }
    }
}

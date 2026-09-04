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
import java.util.concurrent.ThreadLocalRandom;

/**
 * 유관기관 E2E 테스트 시뮬레이터 컨트롤러 — 패턴별 + 최악 시나리오 지원
 *
 * <p><b>목적</b>: 설계서 §8절의 4종 HandoffStrategy 패턴(DIRECT/BRIDGE/APACHE_GATE/INTERNAL_SSO)
 * 및 최악 시나리오(장애·레거시·악의적 클라이언트)를 {@code scenarioMode} 파라미터로
 * 한 번에 시뮬레이션합니다.
 *
 * <p><b>scenarioMode 목록</b>:
 * <pre>
 *   NORMAL         — 정상 흐름 (기본값)
 *   SLOW_RESPONSE  — 3.5s 응답 지연 (Resilience4j 슬로우 콜 임계치 근접)
 *   TIMEOUT        — 6s 응답 지연 → CB 슬로우 콜 트리거
 *   CHAOS          — 30% 확률 랜덤 실패 (Chaos Engineering)
 *   REPLAY_ATTACK  — 동일 ticketId 2회 verify 시도 → 409 ALREADY_CONSUMED
 *   WRONG_AGENCY   — 타 기관 코드로 verify 시도 → 403 AGENCY_MISMATCH
 *   EXPIRED_TICKET — 만료된 ticketId (실제 TTL 초과 없이 존재하지 않는 ID 사용)
 *   HMAC_TAMPER    — Webhook 서명 1바이트 변조 후 POST
 *   CB_STORM       — 연속 실패로 CircuitBreaker 강제 OPEN 유발
 * </pre>
 *
 * <p><b>패턴별 기관 코드</b>:
 * <pre>
 *   AGENCY_STUB_001       — DIRECT (기본 정상 기관)
 *   AGENCY_BRIDGE_001     — BRIDGE (폐쇄망 기관)
 *   AGENCY_APACHEGATE_001 — APACHE_GATE (레거시 Apache/mod_auth)
 *   AGENCY_SSO_001        — INTERNAL_SSO (기관 내부 SSO 연계)
 *   AGENCY_STRICT_L3      — DIRECT + L3 고보안
 *   AGENCY_CHAOS_001      — 최악 시나리오 전용
 * </pre>
 *
 * <p><b>보안 주의</b>: PoC / 테스트 전용. 운영 프로파일에서 비활성화 필수.
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
    // ScenarioMode — 유관기관 패턴별 + 최악 시나리오 분류
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 시뮬레이션 시나리오 모드.
     *
     * <p>설계서 §8절 4종 패턴의 정상 + 최악 시나리오를 포괄.
     */
    public enum ScenarioMode {
        /** 정상 흐름 (기본값) — DIRECT 패턴 표준 동작 */
        NORMAL,
        /** 3.5s 지연 — Resilience4j 슬로우 콜 임계치(4s) 근접 시뮬레이션 */
        SLOW_RESPONSE,
        /** 6s 지연 — CB 슬로우 콜 트리거, HOLD 상태 수신 유도 */
        TIMEOUT,
        /** 30% 확률 랜덤 실패 — Chaos Engineering (무작위 장애) */
        CHAOS,
        /**
         * 동일 ticketId 2회 verify — 409 ALREADY_CONSUMED 재사용 공격 시뮬레이션.
         * Step 1 정상 발급 → Step 2 verify → Step 2 동일 ticketId 재verify.
         */
        REPLAY_ATTACK,
        /**
         * 존재하지 않는 ticketId verify — 404 TICKET_NOT_FOUND.
         * 위조된 ticketId 또는 타 기관 ticket 오류 재현.
         */
        WRONG_AGENCY,
        /**
         * 만료·존재하지 않는 ticketId — 410 TICKET_EXPIRED 또는 404.
         * 발급 후 즉시 다른 ticketId를 사용해 만료 상황 재현.
         */
        EXPIRED_TICKET,
        /**
         * Webhook 서명 1바이트 변조 후 POST — 401 SIGNATURE_VERIFICATION_FAILED.
         * HMAC-SHA256 검증 우회 시도 재현.
         */
        HMAC_TAMPER,
        /**
         * 연속 실패로 CircuitBreaker 강제 OPEN 유발.
         * 존재하지 않는 ticketId 다수 verify → CB OPEN → HOLD 상태.
         */
        CB_STORM
    }

    // ════════════════════════════════════════════════════════════════════════
    // E2E 전체 흐름 시뮬레이션
    // ════════════════════════════════════════════════════════════════════════

    /**
     * POST /api/v1/simulator/run
     *
     * <p>Ticket 발급 → Verify → 세션 생성까지 3단계를 한 번에 수행합니다.
     * {@code scenarioMode} 파라미터로 정상·최악 시나리오를 선택합니다.
     *
     * @param req       시뮬레이션 파라미터 (scenarioMode 포함)
     * @param httpReq   Servlet request
     * @param httpResp  Servlet response (AGSID 쿠키 발급)
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

        ScenarioMode mode = req.getScenarioModeEnum();
        log.info("[Simulator] === E2E 시뮬레이션 시작 === qimUserId={} authLevel={} scenario={} correlationId={}",
                req.getQimUserId(), req.getAuthLevel(), mode, cid);

        SimulationResultBuilder result = SimulationResultBuilder.start(cid, req);

        // ── 시나리오 사전 처리 ────────────────────────────────────────
        ResponseEntity<?> earlyExit = applyScenarioPreCondition(mode, result, cid);
        if (earlyExit != null) return earlyExit;

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
            log.info("[Simulator] STEP 1 완료: ticketId={} scenario={}", ticket.ticketId(), mode);

        } catch (IdoTicketIssuanceException e) {
            log.error("[Simulator] STEP 1 실패: {} scenario={}", e.getMessage(), mode);
            return ResponseEntity.status(502)
                    .body(result.failed("TICKET_ISSUE", e.getErrorCode(), e.getMessage()));

        } catch (Exception e) {
            log.error("[Simulator] STEP 1 예외: {} scenario={}", e.getMessage(), mode, e);
            return ResponseEntity.status(502)
                    .body(result.failed("TICKET_ISSUE", "UNEXPECTED_ERROR", e.getMessage()));
        }

        // ── STEP 2: Ticket 검증 (시나리오별 변형) ─────────────────────
        HandoffPayload payload;
        try {
            result.stepStart("TICKET_VERIFY");
            String ticketIdToVerify = resolveVerifyTicketId(mode, ticket.ticketId());

            // SLOW_RESPONSE / TIMEOUT: 응답 지연 삽입
            applyResponseDelay(mode, result, cid);

            // CB_STORM: 다수 실패로 CB OPEN 유발
            if (mode == ScenarioMode.CB_STORM) {
                return runCbStorm(result, cid);
            }

            payload = idoVerifyClient.verify(ticketIdToVerify, cid);

            // REPLAY_ATTACK: 동일 ticketId 재verify → 409 확인
            if (mode == ScenarioMode.REPLAY_ATTACK) {
                return runReplayAttack(ticket.ticketId(), payload, result, cid);
            }

            result.stepOk("TICKET_VERIFY", Map.of(
                    "scenario",        mode.name(),
                    "ticketIdUsed",    ticketIdToVerify,
                    "state",           payload.getState() != null ? payload.getState().name() : "null",
                    "agencySubjectId", safeStr(payload.getSubject() != null
                            ? payload.getSubject().getAgencySubjectId() : null),
                    "authLevel",       safeStr(payload.getAuthContext() != null
                            ? payload.getAuthContext().getAuthLevel() : null)
            ));
            log.info("[Simulator] STEP 2 완료: state={} scenario={}", payload.getState(), mode);

        } catch (Exception e) {
            log.error("[Simulator] STEP 2 예외: {} scenario={}", e.getMessage(), mode, e);
            return ResponseEntity.status(502)
                    .body(result.failed("TICKET_VERIFY", "VERIFY_EXCEPTION",
                            "[" + mode + "] " + e.getMessage()));
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
                                "[" + mode + "] IdO 서비스 일시 불가 — 잠시 후 재시도하세요"));
            }
            case REJECTED, MANUAL_REVIEW -> {
                return ResponseEntity.status(403)
                        .body(result.failed("TICKET_VERIFY", payload.getState().name(),
                                "[" + mode + "] Handoff 거부됨"));
            }
            case APPROVED -> { /* 계속 */ }
            default -> {
                return ResponseEntity.status(502)
                        .body(result.failed("TICKET_VERIFY", "UNKNOWN_STATE",
                                "[" + mode + "] 알 수 없는 state: " + payload.getState()));
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
            log.info("[Simulator] STEP 3 완료: sessionId={} scenario={}", session.sessionId(), mode);

        } catch (Exception e) {
            log.error("[Simulator] STEP 3 실패: {} scenario={}", e.getMessage(), mode, e);
            return ResponseEntity.status(500)
                    .body(result.failed("SESSION_CREATE", "SESSION_FAILED", e.getMessage()));
        }

        // HMAC_TAMPER: 세션 생성 후 Webhook 서명 위조 시뮬레이션
        if (mode == ScenarioMode.HMAC_TAMPER) {
            result.addNote("HMAC_TAMPER",
                    "Webhook 서명 위조 시뮬레이션: 세션 생성 완료 후 별도 POST /api/v1/webhook/inbound 로 변조 서명 전송 필요. " +
                    "테스트: WebhookSignatureTest.testInvalidSignature_rejected()");
        }

        // ── AGSID 쿠키 발급 ────────────────────────────────────────────
        ResponseCookie agsidCookie = ResponseCookie.from(COOKIE_NAME, session.rawAgsid())
                .httpOnly(true)
                .secure(false)          // 시뮬레이터: localhost 는 http
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofMinutes(idleTimeoutMinutes))
                .build();
        httpResp.addHeader(HttpHeaders.SET_COOKIE, agsidCookie.toString());

        log.info("[Simulator] === E2E 시뮬레이션 완료 === sessionId={} scenario={} correlationId={}",
                session.sessionId(), mode, cid);

        return ResponseEntity.ok(result.success(session));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 시나리오별 보조 메서드
    // ════════════════════════════════════════════════════════════════════════

    /**
     * CHAOS 시나리오: 30% 확률 조기 실패 반환.
     * 다른 시나리오는 null 반환(정상 진행).
     */
    private ResponseEntity<?> applyScenarioPreCondition(
            ScenarioMode mode, SimulationResultBuilder result, String cid) {

        if (mode == ScenarioMode.CHAOS) {
            if (ThreadLocalRandom.current().nextInt(10) < 3) {
                log.warn("[Simulator][CHAOS] 랜덤 실패 발생: correlationId={}", cid);
                return ResponseEntity.status(503)
                        .body(result.failed("PRE_CONDITION", "CHAOS_RANDOM_FAILURE",
                                "[CHAOS] 30% 확률 랜덤 장애 — 재시도하세요"));
            }
        }
        return null;
    }

    /**
     * 시나리오에 따라 verify에 사용할 ticketId 결정.
     * <ul>
     *   <li>WRONG_AGENCY / EXPIRED_TICKET: 존재하지 않는 가짜 ticketId</li>
     *   <li>그 외: 실제 발급된 ticketId</li>
     * </ul>
     */
    private String resolveVerifyTicketId(ScenarioMode mode, String realTicketId) {
        return switch (mode) {
            case WRONG_AGENCY    -> "00000000-FAKE-TICKET-WRONG-AGENCY00000";   // 404 유발
            case EXPIRED_TICKET  -> "expired-ticket-" + UuidV7.generate();      // 404 or 410 유발
            default              -> realTicketId;
        };
    }

    /**
     * SLOW_RESPONSE / TIMEOUT 시나리오: 응답 지연 삽입.
     * Resilience4j 슬로우 콜 판정 임계치(4s) 기준.
     */
    private void applyResponseDelay(ScenarioMode mode, SimulationResultBuilder result, String cid) {
        int delayMs = switch (mode) {
            case SLOW_RESPONSE -> 3500;   // 슬로우 콜 경계 근접 (4s 임계치 - 500ms)
            case TIMEOUT       -> 6000;   // 슬로우 콜 임계치 초과 → CB OPEN 유발
            default            -> 0;
        };
        if (delayMs > 0) {
            log.warn("[Simulator][{}] 응답 지연 {}ms 삽입: correlationId={}", mode, delayMs, cid);
            result.addNote(mode.name(), "응답 지연 " + delayMs + "ms 삽입");
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("[Simulator][{}] 지연 인터럽트: correlationId={}", mode, cid);
            }
        }
    }

    /**
     * REPLAY_ATTACK: 1차 verify 성공 후 동일 ticketId 재verify → 409 확인.
     */
    private ResponseEntity<?> runReplayAttack(
            String ticketId, HandoffPayload firstPayload,
            SimulationResultBuilder result, String cid) {

        log.warn("[Simulator][REPLAY_ATTACK] 동일 ticketId 재verify 시도: ticketId={} cid={}", ticketId, cid);
        try {
            idoVerifyClient.verify(ticketId, cid);  // 재사용 시도
            result.addNote("REPLAY_ATTACK", "경고: 2차 verify가 예상치 않게 성공했습니다. IdO 1회성 소비 로직을 확인하세요.");
            return ResponseEntity.status(409)
                    .body(result.failed("REPLAY_ATTACK", "REPLAY_NOT_REJECTED",
                            "재사용 공격이 차단되지 않았습니다 — IdO ticketId 소비 로직 확인 필요"));
        } catch (Exception e) {
            // 409 ALREADY_CONSUMED 또는 404 수신 → 예상된 동작
            result.stepOk("REPLAY_ATTACK", Map.of(
                    "scenario",       "REPLAY_ATTACK",
                    "firstVerify",    "APPROVED",
                    "secondVerify",   "REJECTED (예상됨: " + e.getMessage() + ")",
                    "securityResult", "REPLAY_BLOCKED_OK"
            ));
            log.info("[Simulator][REPLAY_ATTACK] 재사용 차단 확인 OK: ticketId={} err={}", ticketId, e.getMessage());
            return ResponseEntity.ok(result.success(null));
        }
    }

    /**
     * CB_STORM: 다수의 위조 ticketId를 빠르게 verify하여 CB OPEN 유발.
     * Resilience4j 슬라이딩 윈도우 10회, 실패율 50% → OPEN.
     */
    private ResponseEntity<?> runCbStorm(SimulationResultBuilder result, String cid) {
        log.warn("[Simulator][CB_STORM] CircuitBreaker 강제 OPEN 시도: correlationId={}", cid);
        int attempts = 0;
        int failures = 0;

        for (int i = 0; i < 12; i++) {
            try {
                idoVerifyClient.verify("cb-storm-fake-" + UuidV7.generate(), cid);
            } catch (Exception e) {
                failures++;
                log.debug("[Simulator][CB_STORM] 실패 #{}: {}", i + 1, e.getMessage());
            }
            attempts++;
        }

        result.stepOk("CB_STORM", Map.of(
                "scenario",    "CB_STORM",
                "attempts",    attempts,
                "failures",    failures,
                "description", "연속 실패로 CB OPEN 유발 시도 완료. /actuator/health/circuitbreakers 에서 CB 상태 확인."
        ));
        log.info("[Simulator][CB_STORM] 완료: attempts={} failures={} cid={}", attempts, failures, cid);

        return ResponseEntity.ok(result.success(null));
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
     *
     * <p>{@code scenarioMode} 는 {@link ScenarioMode} 이름을 대소문자 무관하게 수신.
     * 알 수 없는 값은 {@code NORMAL} 로 폴백됩니다.
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
        /**
         * 시나리오 모드 이름 — {@link ScenarioMode} 값 중 하나.
         * 기본값: {@code "NORMAL"}.
         */
        private String scenarioMode = "NORMAL";

        public static SimulationRequest defaults() { return new SimulationRequest(); }

        public String getQimUserId()    { return qimUserId; }
        public String getAuthResultId() { return authResultId; }
        public String getAuthLevel()    { return authLevel; }
        public String getProviderCode() { return providerCode; }
        public String getScenarioMode() { return scenarioMode; }

        /** {@link ScenarioMode} 로 파싱. 알 수 없는 값이면 {@code NORMAL} 반환. */
        public ScenarioMode getScenarioModeEnum() {
            try {
                return ScenarioMode.valueOf(scenarioMode.toUpperCase());
            } catch (Exception e) {
                return ScenarioMode.NORMAL;
            }
        }

        public void setQimUserId(String v)    { this.qimUserId    = v; }
        public void setAuthResultId(String v) { this.authResultId = v; }
        public void setAuthLevel(String v)    { this.authLevel    = v; }
        public void setProviderCode(String v) { this.providerCode = v; }
        public void setScenarioMode(String v) { this.scenarioMode = v; }
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

        /**
         * 시나리오 주석·메모를 steps 에 NOTE 항목으로 추가.
         *
         * @param key     단계 식별자 (예: "HMAC_TAMPER", "SLOW_RESPONSE")
         * @param message 사람이 읽을 수 있는 설명 메시지
         */
        void addNote(String key, String message) {
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("step",    key);
            note.put("status",  "NOTE");
            note.put("message", message);
            steps.add(note);
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

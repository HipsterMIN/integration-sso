package kr.go.smes.agency.pattern;

import kr.go.smes.agency.client.IdoTicketClient;
import kr.go.smes.agency.client.IdoVerifyClient;
import kr.go.smes.agency.session.AgencySessionService;
import kr.go.smes.agency.simulator.AgencySimulatorController;
import kr.go.smes.agency.simulator.AgencySimulatorController.ScenarioMode;
import kr.go.smes.agency.simulator.AgencySimulatorController.SimulationRequest;
import kr.go.smes.common.domain.AuthResult;
import kr.go.smes.common.domain.HandoffPayload;
import kr.go.smes.common.domain.HandoffPayload.HandoffState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * 최악 시나리오(Worst-Case) 단위 테스트 — 공통 8종
 *
 * <p>모든 기관 패턴에 공통으로 적용되는 장애·공격·혼돈 시나리오를 검증합니다.
 *
 * <p>검증 시나리오 (8종):
 * <ol>
 *   <li>REPLAY_ATTACK       — 동일 ticketId 2회 verify → 2차는 예외 발생, 보안 차단 확인</li>
 *   <li>WRONG_AGENCY        — 위조 ticketId verify → 502 (예외 응답)</li>
 *   <li>EXPIRED_TICKET      — 만료 ticketId verify → 502 (예외 응답)</li>
 *   <li>SLOW_RESPONSE       — 3.5s 지연 삽입 후 정상 완료 → 200 OK</li>
 *   <li>TIMEOUT             — 6s 지연 삽입 후 정상 완료 → 200 OK (CB 트리거 유도)</li>
 *   <li>CB_STORM            — 12회 위조 verify → 200 + CB_STORM steps 포함</li>
 *   <li>HMAC_TAMPER         — 세션 생성 후 HMAC_TAMPER NOTE steps 포함 확인</li>
 *   <li>UNKNOWN_SCENARIO    — 알 수 없는 scenarioMode → NORMAL 폴백 처리</li>
 * </ol>
 *
 * <p><b>참고</b>: SLOW_RESPONSE/TIMEOUT은 실제 Thread.sleep을 수행하므로
 * {@code @Timeout}으로 상한을 설정합니다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("최악 시나리오(Worst-Case) — 공통 장애·공격·혼돈 단위 테스트")
class AgencyWorstCaseTest {

    @Mock private IdoTicketClient      idoTicketClient;
    @Mock private IdoVerifyClient      idoVerifyClient;
    @Mock private AgencySessionService agencySessionService;
    @Mock private JdbcTemplate         jdbcTemplate;

    private AgencySimulatorController controller;
    private MockHttpServletRequest     httpReq;
    private MockHttpServletResponse    httpResp;

    private static final String TICKET_ID    = "worst-ticket-001";
    private static final String AGENCY_CHAOS = "AGENCY_CHAOS_001";

    @BeforeEach
    void setUp() {
        controller = new AgencySimulatorController(
                idoTicketClient, idoVerifyClient, agencySessionService, jdbcTemplate);
        ReflectionTestUtils.setField(controller, "agencyCode",         AGENCY_CHAOS);
        ReflectionTestUtils.setField(controller, "idoBaseUrl",         "http://localhost:8083");
        ReflectionTestUtils.setField(controller, "idleTimeoutMinutes", 30);
        httpReq  = new MockHttpServletRequest();
        httpResp = new MockHttpServletResponse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // [1] REPLAY_ATTACK — 동일 ticketId 2회 verify
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[WORST-1] REPLAY_ATTACK: 1차 verify 성공 → 2차 예외 → 보안 차단 확인 → 200")
    void replayAttack_secondVerifyFails_securityOk() throws Exception {
        // Given
        givenTicketIssued(TICKET_ID);
        // 1차 verify 성공, 2차 verify 예외 (ALREADY_CONSUMED 흉내)
        when(idoVerifyClient.verify(eq(TICKET_ID), anyString()))
                .thenReturn(buildApproved(AGENCY_CHAOS, AuthResult.AuthLevel.L2))  // 1차
                .thenThrow(new RuntimeException("409 ALREADY_CONSUMED"));           // 2차

        SimulationRequest req = scenarioReq(ScenarioMode.REPLAY_ATTACK);

        // When
        ResponseEntity<?> resp = controller.runFullFlow(req, httpReq, httpResp);

        // Then: REPLAY_ATTACK은 2차 예외 → 보안 차단 OK → 200 반환
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("status")).isEqualTo("OK");
        // steps 에 REPLAY_ATTACK 포함 확인
        assertThat(body.get("steps").toString()).contains("REPLAY_ATTACK");
        // idoVerifyClient.verify 2회 호출 확인 (1차 정상 + 2차 재사용 시도)
        verify(idoVerifyClient, times(2)).verify(eq(TICKET_ID), anyString());
        // 세션 생성 없음 (REPLAY_ATTACK 후 즉시 반환)
        verify(agencySessionService, never()).createSession(any(), anyString(), anyString(), anyString(), any());
    }

    // ════════════════════════════════════════════════════════════════════════
    // [2] WRONG_AGENCY — 위조 ticketId
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[WORST-2] WRONG_AGENCY: 위조 ticketId verify → 예외 → 502 반환")
    void wrongAgency_fakeTicketId_returns502() throws Exception {
        // Given
        givenTicketIssued(TICKET_ID);
        // 위조 ticketId (resolveVerifyTicketId 가 반환하는 가짜 ID)로 verify 시 예외
        when(idoVerifyClient.verify(
                argThat(id -> id != null && id.startsWith("00000000-FAKE-TICKET")),
                anyString()))
                .thenThrow(new RuntimeException("404 TICKET_NOT_FOUND"));

        SimulationRequest req = scenarioReq(ScenarioMode.WRONG_AGENCY);

        // When
        ResponseEntity<?> resp = controller.runFullFlow(req, httpReq, httpResp);

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        assertBodyStatus(resp, "FAILED");
        verify(agencySessionService, never()).createSession(any(), anyString(), anyString(), anyString(), any());
    }

    // ════════════════════════════════════════════════════════════════════════
    // [3] EXPIRED_TICKET — 만료 ticketId
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[WORST-3] EXPIRED_TICKET: 만료 ticketId verify → 예외 → 502 반환")
    void expiredTicket_returns502() throws Exception {
        // Given
        givenTicketIssued(TICKET_ID);
        // expired- 로 시작하는 가짜 ticketId로 verify 시 예외
        when(idoVerifyClient.verify(
                argThat(id -> id != null && id.startsWith("expired-ticket-")),
                anyString()))
                .thenThrow(new RuntimeException("410 TICKET_EXPIRED"));

        SimulationRequest req = scenarioReq(ScenarioMode.EXPIRED_TICKET);

        // When
        ResponseEntity<?> resp = controller.runFullFlow(req, httpReq, httpResp);

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        assertBodyStatus(resp, "FAILED");
        verify(agencySessionService, never()).createSession(any(), anyString(), anyString(), anyString(), any());
    }

    // ════════════════════════════════════════════════════════════════════════
    // [4] SLOW_RESPONSE — 3.5s 지연
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("[WORST-4] SLOW_RESPONSE: 3.5s 지연 후 정상 완료 → 200 OK + NOTE 포함")
    void slowResponse_insertDelay_returns200() throws Exception {
        // Given
        givenTicketIssued(TICKET_ID);
        givenVerifyApproved(TICKET_ID, AGENCY_CHAOS, AuthResult.AuthLevel.L2);
        givenSessionCreated("slow-session-001", "slow-user-001", AGENCY_CHAOS + "_SUBJ", "L2");

        SimulationRequest req = scenarioReq(ScenarioMode.SLOW_RESPONSE);

        // When
        long start = System.currentTimeMillis();
        ResponseEntity<?> resp = controller.runFullFlow(req, httpReq, httpResp);
        long elapsed = System.currentTimeMillis() - start;

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertBodyStatus(resp, "OK");
        // 3.5s 지연이 실제로 발생했는지 확인 (최소 3000ms)
        assertThat(elapsed).isGreaterThanOrEqualTo(3000L);
        // steps에 SLOW_RESPONSE NOTE 포함
        assertThat(bodyAsMap(resp).get("steps").toString()).contains("SLOW_RESPONSE");
    }

    // ════════════════════════════════════════════════════════════════════════
    // [5] TIMEOUT — 6s 지연
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("[WORST-5] TIMEOUT: 6s 지연 후 정상 완료 → 200 OK (CB 트리거 유도)")
    void timeout_insertDelay_returns200() throws Exception {
        // Given
        givenTicketIssued(TICKET_ID);
        givenVerifyApproved(TICKET_ID, AGENCY_CHAOS, AuthResult.AuthLevel.L2);
        givenSessionCreated("timeout-session-001", "timeout-user-001", AGENCY_CHAOS + "_SUBJ", "L2");

        SimulationRequest req = scenarioReq(ScenarioMode.TIMEOUT);

        // When
        long start = System.currentTimeMillis();
        ResponseEntity<?> resp = controller.runFullFlow(req, httpReq, httpResp);
        long elapsed = System.currentTimeMillis() - start;

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertBodyStatus(resp, "OK");
        // 6s 지연 확인 (최소 5500ms)
        assertThat(elapsed).isGreaterThanOrEqualTo(5500L);
        assertThat(bodyAsMap(resp).get("steps").toString()).contains("TIMEOUT");
    }

    // ════════════════════════════════════════════════════════════════════════
    // [6] CB_STORM — 12회 위조 verify → CB OPEN 유발
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[WORST-6] CB_STORM: 12회 위조 ticketId verify → 200 + CB_STORM steps 포함")
    void cbStorm_forcesCircuitBreakerOpen() throws Exception {
        // Given
        givenTicketIssued(TICKET_ID);
        // cb-storm-fake- 로 시작하는 모든 ticketId → 예외
        when(idoVerifyClient.verify(
                argThat(id -> id != null && id.startsWith("cb-storm-fake-")),
                anyString()))
                .thenThrow(new RuntimeException("404 TICKET_NOT_FOUND"));

        SimulationRequest req = scenarioReq(ScenarioMode.CB_STORM);

        // When
        ResponseEntity<?> resp = controller.runFullFlow(req, httpReq, httpResp);

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("status")).isEqualTo("OK");
        // steps에 CB_STORM 포함
        assertThat(body.get("steps").toString()).contains("CB_STORM");
        // 12회 verify 호출 확인
        verify(idoVerifyClient, times(12)).verify(
                argThat(id -> id != null && id.startsWith("cb-storm-fake-")),
                anyString());
    }

    // ════════════════════════════════════════════════════════════════════════
    // [7] HMAC_TAMPER — Webhook 서명 변조 NOTE 확인
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[WORST-7] HMAC_TAMPER: 세션 생성 완료 → HMAC_TAMPER NOTE steps 포함 → 200 OK")
    void hmacTamper_sessionCreated_notePresent() throws Exception {
        // Given
        givenTicketIssued(TICKET_ID);
        givenVerifyApproved(TICKET_ID, AGENCY_CHAOS, AuthResult.AuthLevel.L2);
        givenSessionCreated("hmac-session-001", "hmac-user-001", AGENCY_CHAOS + "_SUBJ", "L2");

        SimulationRequest req = scenarioReq(ScenarioMode.HMAC_TAMPER);

        // When
        ResponseEntity<?> resp = controller.runFullFlow(req, httpReq, httpResp);

        // Then: 세션 생성까지 정상 완료 → 200 OK
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertBodyStatus(resp, "OK");
        // steps에 HMAC_TAMPER NOTE 포함
        String stepsStr = bodyAsMap(resp).get("steps").toString();
        assertThat(stepsStr).contains("HMAC_TAMPER");
        assertThat(stepsStr).contains("NOTE");
        // 실제 Webhook 변조는 별도 테스트(WebhookSignatureTest)에서 수행
    }

    // ════════════════════════════════════════════════════════════════════════
    // [8] UNKNOWN_SCENARIO — 폴백 처리
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[WORST-8] UNKNOWN_SCENARIO: 알 수 없는 scenarioMode → NORMAL 폴백 → 200 OK")
    void unknownScenarioMode_fallbackToNormal_returns200() throws Exception {
        // Given
        givenTicketIssued(TICKET_ID);
        givenVerifyApproved(TICKET_ID, AGENCY_CHAOS, AuthResult.AuthLevel.L2);
        givenSessionCreated("fallback-session-001", "fallback-user-001", AGENCY_CHAOS + "_SUBJ", "L2");

        SimulationRequest req = new SimulationRequest();
        req.setScenarioMode("THIS_DOES_NOT_EXIST");  // 존재하지 않는 모드 → NORMAL 폴백

        // When
        ResponseEntity<?> resp = controller.runFullFlow(req, httpReq, httpResp);

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertBodyStatus(resp, "OK");
        // getScenarioModeEnum() 폴백 검증
        assertThat(req.getScenarioModeEnum()).isEqualTo(ScenarioMode.NORMAL);
    }

    // ════════════════════════════════════════════════════════════════════════
    // ScenarioMode enum 직접 검증
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[WORST-ENUM] ScenarioMode: 대소문자 무관 파싱 + 잘못된 값 NORMAL 폴백")
    void scenarioModeEnum_parsing() {
        SimulationRequest req = new SimulationRequest();

        req.setScenarioMode("chaos");
        assertThat(req.getScenarioModeEnum()).isEqualTo(ScenarioMode.CHAOS);

        req.setScenarioMode("REPLAY_ATTACK");
        assertThat(req.getScenarioModeEnum()).isEqualTo(ScenarioMode.REPLAY_ATTACK);

        req.setScenarioMode("Timeout");
        assertThat(req.getScenarioModeEnum()).isEqualTo(ScenarioMode.TIMEOUT);

        req.setScenarioMode("INVALID_XYZ");
        assertThat(req.getScenarioModeEnum()).isEqualTo(ScenarioMode.NORMAL);

        req.setScenarioMode("");
        assertThat(req.getScenarioModeEnum()).isEqualTo(ScenarioMode.NORMAL);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 공통 헬퍼
    // ════════════════════════════════════════════════════════════════════════

    private SimulationRequest scenarioReq(ScenarioMode mode) {
        SimulationRequest req = new SimulationRequest();
        req.setScenarioMode(mode.name());
        req.setAuthLevel("L2");
        req.setProviderCode("QSIGN_CERT");
        return req;
    }

    private void givenTicketIssued(String ticketId) throws Exception {
        lenient().when(idoTicketClient.issue(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IdoTicketClient.TicketResult(
                        ticketId, Instant.now().plusSeconds(60), "cid-worst-test"));
    }

    private void givenVerifyApproved(String ticketId, String agencyCode,
                                     AuthResult.AuthLevel authLevel) {
        lenient().when(idoVerifyClient.verify(eq(ticketId), anyString()))
                .thenReturn(buildApproved(agencyCode, authLevel));
    }

    private void givenSessionCreated(String sessionId, String userId,
                                     String subjectId, String authLevel) {
        // record: (sessionId, rawAgsid, agencyUserId, agencySubjectId, authLevel)
        var result = new AgencySessionService.SessionCreateResult(
                sessionId, "raw-" + sessionId, userId, subjectId, authLevel);
        lenient().when(agencySessionService.createSession(
                        any(HandoffPayload.class), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(result);
        lenient().when(agencySessionService.createSession(
                        any(HandoffPayload.class), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyAsMap(ResponseEntity<?> resp) {
        return (Map<String, Object>) resp.getBody();
    }

    private void assertBodyStatus(ResponseEntity<?> resp, String expected) {
        assertThat(bodyAsMap(resp).get("status")).isEqualTo(expected);
    }

    private HandoffPayload buildApproved(String agencyCode, AuthResult.AuthLevel authLevel) {
        return HandoffPayload.builder()
                .state(HandoffState.APPROVED)
                .agencyCode(agencyCode)
                .subject(HandoffPayload.SubjectIdentifier.builder()
                        .agencySubjectId(agencyCode + "_WORST_SUBJ")
                        .build())
                .authContext(HandoffPayload.AuthContext.builder()
                        .authLevel(authLevel)
                        .build())
                .build();
    }
}

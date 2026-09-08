package io.github.hipstermin.idem.tenant.pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.domain.HandoffPayload.HandoffState;
import io.github.hipstermin.idem.tenant.client.IdoTicketClient;
import io.github.hipstermin.idem.tenant.client.IdoVerifyClient;
import io.github.hipstermin.idem.tenant.session.AgencySessionService;
import io.github.hipstermin.idem.tenant.simulator.AgencySimulatorController;
import io.github.hipstermin.idem.tenant.simulator.AgencySimulatorController.SimulationRequest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * DIRECT 패턴 — AgencySimulatorController scenarioMode 단위 테스트
 *
 * <p>대상 기관: AGENCY_STUB_001 (정상 DIRECT), AGENCY_STRICT_L3 (L3 고보안 DIRECT)
 *
 * <p>검증 시나리오 (6종):
 * <ol>
 *   <li>NORMAL    — 정상 흐름: 3단계 성공 → 200 OK + sessionId</li>
 *   <li>NORMAL/L3 — L3 고보안 정상 흐름: authLevel=L3, providerCode=GPKI_CERT</li>
 *   <li>HOLD      — IdO가 HOLD 반환 → 503 (세션 생성 없음)</li>
 *   <li>REJECTED  — IdO가 REJECTED 반환 → 403 (세션 생성 없음)</li>
 *   <li>TICKET_ISSUE_FAIL — Step 1 발급 실패 → 502 (verify 호출 없음)</li>
 *   <li>SESSION_FAIL — Step 3 세션 생성 예외 → 500</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DIRECT 패턴 — AgencySimulatorController 시나리오 단위 테스트")
class AgencyPatternDirectTest {

    @Mock private IdoTicketClient      idoTicketClient;
    @Mock private IdoVerifyClient      idoVerifyClient;
    @Mock private AgencySessionService agencySessionService;
    @Mock private JdbcTemplate         jdbcTemplate;

    private AgencySimulatorController controller;
    private MockHttpServletRequest     httpReq;
    private MockHttpServletResponse    httpResp;

    private static final String TICKET_ID     = "direct-ticket-001";
    private static final String AGENCY_DIRECT = "AGENCY_STUB_001";

    @BeforeEach
    void setUp() {
        controller = new AgencySimulatorController(
                idoTicketClient, idoVerifyClient, agencySessionService, jdbcTemplate);
        ReflectionTestUtils.setField(controller, "agencyCode",         AGENCY_DIRECT);
        ReflectionTestUtils.setField(controller, "idoBaseUrl",         "http://localhost:8083");
        ReflectionTestUtils.setField(controller, "idleTimeoutMinutes", 30);
        httpReq  = new MockHttpServletRequest();
        httpResp = new MockHttpServletResponse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // [1] NORMAL — 정상 흐름
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[DIRECT-1] NORMAL: 3단계 정상 완료 → 200 OK + sessionId 반환")
    void normal_fullFlow_returns200WithSessionId() throws Exception {
        // Given
        givenTicketIssued(TICKET_ID);
        givenVerifyApproved(TICKET_ID, AGENCY_DIRECT, AuthResult.AuthLevel.L2);
        givenSessionCreated("session-001", "user-001", AGENCY_DIRECT + "_USER", "L2");

        SimulationRequest req = normalReq("L2", "QSIGN_CERT");

        // When
        ResponseEntity<?> resp = controller.runFullFlow(req, httpReq, httpResp);

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertBodyStatus(resp, "OK");
        assertThat(bodyAsMap(resp)).containsKey("session");
        verify(idoTicketClient).issue(anyString(), anyString(), eq("L2"), eq("QSIGN_CERT"), anyString());
        verify(idoVerifyClient).verify(eq(TICKET_ID), anyString());
        verify(agencySessionService).createSession(any(), eq(TICKET_ID), anyString(), anyString(), any());
    }

    // ════════════════════════════════════════════════════════════════════════
    // [2] NORMAL/L3 — L3 고보안
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[DIRECT-2] NORMAL/L3: authLevel=L3, GPKI_CERT → 200 OK")
    void normal_l3_authLevel_returns200() throws Exception {
        // Given
        givenTicketIssued(TICKET_ID);
        givenVerifyApproved(TICKET_ID, "AGENCY_STRICT_L3", AuthResult.AuthLevel.L3);
        givenSessionCreated("session-l3-001", "user-l3-001", "STRICT_L3_USER", "L3");

        SimulationRequest req = normalReq("L3", "GPKI_CERT");

        // When
        ResponseEntity<?> resp = controller.runFullFlow(req, httpReq, httpResp);

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(idoTicketClient).issue(anyString(), anyString(), eq("L3"), eq("GPKI_CERT"), anyString());
    }

    // ════════════════════════════════════════════════════════════════════════
    // [3] HOLD — IdO 일시 불가
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[DIRECT-3] HOLD: verify HOLD 상태 → 503 (세션 생성 없음)")
    void hold_state_returns503() throws Exception {
        // Given
        givenTicketIssued(TICKET_ID);
        givenVerifyState(HandoffState.HOLD, AGENCY_DIRECT, AuthResult.AuthLevel.L2);

        // When
        ResponseEntity<?> resp = controller.runFullFlow(normalReq("L2", "QSIGN_CERT"), httpReq, httpResp);

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(503);
        assertBodyStatus(resp, "FAILED");
        verify(agencySessionService, never()).createSession(any(), anyString(), anyString(), anyString(), any());
    }

    // ════════════════════════════════════════════════════════════════════════
    // [4] REJECTED
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[DIRECT-4] REJECTED: verify REJECTED 상태 → 403 (세션 생성 없음)")
    void rejected_state_returns403() throws Exception {
        // Given
        givenTicketIssued(TICKET_ID);
        givenVerifyState(HandoffState.REJECTED, AGENCY_DIRECT, AuthResult.AuthLevel.L2);

        // When
        ResponseEntity<?> resp = controller.runFullFlow(normalReq("L2", "QSIGN_CERT"), httpReq, httpResp);

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        assertBodyStatus(resp, "FAILED");
        verify(agencySessionService, never()).createSession(any(), anyString(), anyString(), anyString(), any());
    }

    // ════════════════════════════════════════════════════════════════════════
    // [5] Ticket 발급 실패 → 502
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[DIRECT-5] TICKET_ISSUE_FAIL: Step1 예외 → 502 (verify 호출 없음)")
    void ticketIssueFail_returns502() throws Exception {
        // Given
        when(idoTicketClient.issue(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IdoTicketClient.IdoTicketIssuanceException(
                        "TICKET_ISSUE_FAILED", "IdO 응답 오류"));

        // When
        ResponseEntity<?> resp = controller.runFullFlow(normalReq("L2", "QSIGN_CERT"), httpReq, httpResp);

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        assertBodyStatus(resp, "FAILED");
        verify(idoVerifyClient, never()).verify(anyString(), anyString());
    }

    // ════════════════════════════════════════════════════════════════════════
    // [6] 세션 생성 실패 → 500
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[DIRECT-6] SESSION_FAIL: Step3 예외 → 500")
    void sessionCreateFail_returns500() throws Exception {
        // Given
        givenTicketIssued(TICKET_ID);
        givenVerifyApproved(TICKET_ID, AGENCY_DIRECT, AuthResult.AuthLevel.L2);
        when(agencySessionService.createSession(any(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("DB 연결 실패"));

        // When
        ResponseEntity<?> resp = controller.runFullFlow(normalReq("L2", "QSIGN_CERT"), httpReq, httpResp);

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        assertBodyStatus(resp, "FAILED");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 공통 헬퍼
    // ════════════════════════════════════════════════════════════════════════

    private SimulationRequest normalReq(String authLevel, String providerCode) {
        SimulationRequest req = new SimulationRequest();
        req.setScenarioMode("NORMAL");
        req.setAuthLevel(authLevel);
        req.setProviderCode(providerCode);
        return req;
    }

    private void givenTicketIssued(String ticketId) throws Exception {
        var t = new IdoTicketClient.TicketResult(ticketId, Instant.now().plusSeconds(60), "cid-test");
        lenient().when(idoTicketClient.issue(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(t);
    }

    private void givenVerifyApproved(String ticketId, String agencyCode,
                                     AuthResult.AuthLevel authLevel) {
        var p = buildPayload(HandoffState.APPROVED, agencyCode, authLevel);
        lenient().when(idoVerifyClient.verify(eq(ticketId), anyString())).thenReturn(p);
    }

    private void givenVerifyState(HandoffState state, String agencyCode,
                                  AuthResult.AuthLevel authLevel) {
        var p = buildPayload(state, agencyCode, authLevel);
        lenient().when(idoVerifyClient.verify(anyString(), anyString())).thenReturn(p);
    }

    private void givenSessionCreated(String sessionId, String userId,
                                     String subjectId, String authLevel) {
        // record: (sessionId, rawAgsid, agencyUserId, agencySubjectId, authLevel)
        var s = new AgencySessionService.SessionCreateResult(
                sessionId, "raw-agsid-" + sessionId, userId, subjectId, authLevel);
        lenient().when(agencySessionService.createSession(
                        any(HandoffPayload.class), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(s);
        lenient().when(agencySessionService.createSession(
                        any(HandoffPayload.class), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(s);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyAsMap(ResponseEntity<?> resp) {
        return (Map<String, Object>) resp.getBody();
    }

    private void assertBodyStatus(ResponseEntity<?> resp, String expected) {
        assertThat(bodyAsMap(resp).get("status")).isEqualTo(expected);
    }

    private HandoffPayload buildPayload(HandoffState state, String agencyCode,
                                        AuthResult.AuthLevel authLevel) {
        return HandoffPayload.builder()
                .state(state)
                .agencyCode(agencyCode)
                .subject(HandoffPayload.SubjectIdentifier.builder()
                        .agencySubjectId(agencyCode + "_SUBJECT")
                        .build())
                .authContext(HandoffPayload.AuthContext.builder()
                        .authLevel(authLevel)
                        .build())
                .build();
    }
}

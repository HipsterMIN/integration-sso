package kr.go.smes.agency.pattern;

import kr.go.smes.agency.client.IdoTicketClient;
import kr.go.smes.agency.client.IdoVerifyClient;
import kr.go.smes.agency.mock.MockBridgeController;
import kr.go.smes.agency.session.AgencySessionService;
import kr.go.smes.agency.simulator.AgencySimulatorController;
import kr.go.smes.agency.simulator.AgencySimulatorController.SimulationRequest;
import kr.go.smes.common.domain.AuthResult;
import kr.go.smes.common.domain.HandoffPayload;
import kr.go.smes.common.domain.HandoffPayload.HandoffState;
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

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * BRIDGE 패턴 — {@code AGENCY_BRIDGE_001} 단위 테스트
 *
 * <p>BRIDGE 패턴은 폐쇄망 기관이 Bridge 서버를 두고 IdO와 통신합니다.
 * {@link MockBridgeController}가 Bridge Mock 서버 역할을 합니다.
 *
 * <p>검증 시나리오 (5종):
 * <ol>
 *   <li>BRIDGE_PUSH_RECEIVED  — Bridge Mock POST /api/handoff/push → 202 + ticketId</li>
 *   <li>BRIDGE_PULL_FOUND     — Bridge Mock GET /api/handoff/{ticketId} → 200 + payload</li>
 *   <li>BRIDGE_PULL_NOT_FOUND — 미등록 ticketId → 404 NOT_FOUND</li>
 *   <li>BRIDGE_CLEAR          — DELETE /mock/bridge/pending → 초기화 확인</li>
 *   <li>BRIDGE_SIM_NORMAL     — AgencySimulatorController NORMAL 흐름 (BRIDGE 기관으로)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BRIDGE 패턴 — MockBridgeController + SimulationRequest 단위 테스트")
class AgencyPatternBridgeTest {

    @Mock private IdoTicketClient      idoTicketClient;
    @Mock private IdoVerifyClient      idoVerifyClient;
    @Mock private AgencySessionService agencySessionService;
    @Mock private JdbcTemplate         jdbcTemplate;

    private AgencySimulatorController controller;
    private MockBridgeController       bridgeMock;
    private MockHttpServletRequest     httpReq;
    private MockHttpServletResponse    httpResp;

    private static final String TICKET_ID      = "bridge-ticket-001";
    private static final String AGENCY_BRIDGE  = "AGENCY_BRIDGE_001";

    @BeforeEach
    void setUp() {
        controller = new AgencySimulatorController(
                idoTicketClient, idoVerifyClient, agencySessionService, jdbcTemplate);
        ReflectionTestUtils.setField(controller, "agencyCode",         AGENCY_BRIDGE);
        ReflectionTestUtils.setField(controller, "idoBaseUrl",         "http://localhost:8083");
        ReflectionTestUtils.setField(controller, "idleTimeoutMinutes", 30);

        bridgeMock = new MockBridgeController();
        httpReq    = new MockHttpServletRequest();
        httpResp   = new MockHttpServletResponse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // [1] Bridge Push 수신
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[BRIDGE-1] PUSH: Bridge Mock이 Handoff Push 수신 → 202 + ticketId")
    void bridge_push_received_returns202() {
        // Given
        Map<String, String> headers = Map.of(
                "x-agency-code",   AGENCY_BRIDGE,
                "x-handoff-token", "eyJhbGciOiJSUzI1NiJ9.mockToken",
                "x-correlation-id", "cid-bridge-001"
        );
        Map<String, Object> body = Map.of(
                "ticketId",      TICKET_ID,
                "qimUserId",     "bridge-user-001",
                "authLevel",     "L2",
                "expiresAt",     Instant.now().plusSeconds(60).toString()
        );

        // When
        ResponseEntity<?> resp = bridgeMock.receivePush(headers, body);

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(202);
        @SuppressWarnings("unchecked")
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody.get("status")).isEqualTo("ACCEPTED");
        assertThat(respBody.get("ticketId")).isEqualTo(TICKET_ID);
    }

    // ════════════════════════════════════════════════════════════════════════
    // [2] Bridge Pull — 등록된 ticketId 조회
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[BRIDGE-2] PULL_FOUND: 사전 Push된 ticketId Pull 조회 → 200 + payload")
    void bridge_pull_found_returns200() {
        // Given: 먼저 Push로 등록
        Map<String, String> headers = Map.of(
                "x-agency-code",    AGENCY_BRIDGE,
                "x-handoff-token",  "mock-token",
                "x-correlation-id", "cid-bridge-002"
        );
        Map<String, Object> pushBody = Map.of(
                "ticketId",  TICKET_ID,
                "qimUserId", "bridge-user-002",
                "authLevel", "L2"
        );
        bridgeMock.receivePush(headers, pushBody);

        // When
        ResponseEntity<?> resp = bridgeMock.pullHandoff(TICKET_ID);

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody.get("status")).isEqualTo("FOUND");
        assertThat(respBody.get("ticketId")).isEqualTo(TICKET_ID);
        assertThat(respBody).containsKey("payload");
    }

    // ════════════════════════════════════════════════════════════════════════
    // [3] Bridge Pull — 미등록 ticketId → 404
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[BRIDGE-3] PULL_NOT_FOUND: 미등록 ticketId Pull → 404 NOT_FOUND")
    void bridge_pull_notFound_returns404() {
        // When
        ResponseEntity<?> resp = bridgeMock.pullHandoff("non-existent-ticket-id");

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        @SuppressWarnings("unchecked")
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody.get("error")).isEqualTo("TICKET_NOT_FOUND");
    }

    // ════════════════════════════════════════════════════════════════════════
    // [4] Bridge Clear — pending 초기화
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[BRIDGE-4] CLEAR: DELETE /pending → 저장 데이터 초기화 확인")
    void bridge_clear_removesAllPending() {
        // Given: 2건 Push
        for (int i = 0; i < 2; i++) {
            Map<String, String> h = Map.of(
                    "x-agency-code",    AGENCY_BRIDGE,
                    "x-handoff-token",  "tok",
                    "x-correlation-id", "cid-" + i
            );
            bridgeMock.receivePush(h, Map.of("ticketId", "ticket-clear-" + i, "qimUserId", "u" + i));
        }

        // When
        ResponseEntity<?> clearResp = bridgeMock.clearPending();

        // Then
        assertThat(clearResp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> clearBody = (Map<String, Object>) clearResp.getBody();
        assertThat((int) clearBody.get("removed")).isEqualTo(2);

        // 이후 pending 0건
        @SuppressWarnings("unchecked")
        Map<String, Object> listBody = (Map<String, Object>) bridgeMock.listPending().getBody();
        assertThat((int) listBody.get("total")).isEqualTo(0);
    }

    // ════════════════════════════════════════════════════════════════════════
    // [5] BRIDGE 기관으로 시뮬레이터 NORMAL 흐름
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[BRIDGE-5] SIM_NORMAL: BRIDGE 기관으로 E2E 시뮬레이션 NORMAL → 200 OK")
    void bridge_sim_normal_returns200() throws Exception {
        // Given
        givenTicketIssued(TICKET_ID);
        givenVerifyApproved(TICKET_ID, AGENCY_BRIDGE, AuthResult.AuthLevel.L2);
        givenSessionCreated("bridge-session-001", "bridge-user-001", AGENCY_BRIDGE + "_SUBJ", "L2");

        SimulationRequest req = new SimulationRequest();
        req.setScenarioMode("NORMAL");
        req.setAuthLevel("L2");
        req.setProviderCode("NPKI_CERT");

        // When
        ResponseEntity<?> resp = controller.runFullFlow(req, httpReq, httpResp);

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("status")).isEqualTo("OK");
        verify(idoTicketClient).issue(anyString(), anyString(), eq("L2"), eq("NPKI_CERT"), anyString());
    }

    // ════════════════════════════════════════════════════════════════════════
    // 헬퍼
    // ════════════════════════════════════════════════════════════════════════

    private void givenTicketIssued(String ticketId) throws Exception {
        lenient().when(idoTicketClient.issue(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IdoTicketClient.TicketResult(ticketId, Instant.now().plusSeconds(60), "cid-test"));
    }

    private void givenVerifyApproved(String ticketId, String agencyCode, AuthResult.AuthLevel authLevel) {
        lenient().when(idoVerifyClient.verify(eq(ticketId), anyString()))
                .thenReturn(buildPayload(HandoffState.APPROVED, agencyCode, authLevel));
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

    private HandoffPayload buildPayload(HandoffState state, String agencyCode,
                                        AuthResult.AuthLevel authLevel) {
        return HandoffPayload.builder()
                .state(state)
                .agencyCode(agencyCode)
                .subject(HandoffPayload.SubjectIdentifier.builder()
                        .agencySubjectId(agencyCode + "_SUBJ")
                        .build())
                .authContext(HandoffPayload.AuthContext.builder()
                        .authLevel(authLevel)
                        .build())
                .build();
    }
}

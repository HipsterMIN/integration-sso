package io.github.hipstermin.idem.tenant.pattern;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hipstermin.idem.tenant.mock.MockSsoSessionController;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * INTERNAL_SSO 패턴 — {@code MockSsoSessionController} 단위 테스트
 *
 * <p>INTERNAL_SSO 패턴은 IdO가 Ticket 발급 후 기관 SSO 엔드포인트
 * ({@code /internal/sso-session})에 세션을 사전 등록합니다.
 * 사용자가 콜백 URL에 도달하면 SSO 쿠키가 자동 발급됩니다.
 *
 * <p>검증 시나리오 (4종):
 * <ol>
 *   <li>REGISTER_OK       — 필수 헤더+바디 완비 → 201 PRE_REGISTERED</li>
 *   <li>REGISTER_MISSING  — X-Agency-Code 누락 → 400 MISSING_REQUIRED_FIELDS</li>
 *   <li>ACTIVATE_OK       — 사전 등록 → 활성화 → 200 ACTIVATED</li>
 *   <li>ACTIVATE_NOT_FOUND — 미등록 ticketId 활성화 → 404</li>
 * </ol>
 */
@DisplayName("INTERNAL_SSO 패턴 — MockSsoSessionController 단위 테스트")
class AgencyPatternSsoTest {

    private MockSsoSessionController ssoMock;

    private static final String TICKET_ID   = "sso-ticket-001";
    private static final String AGENCY_SSO  = "AGENCY_SSO_001";

    @BeforeEach
    void setUp() {
        ssoMock = new MockSsoSessionController();
    }

    // ════════════════════════════════════════════════════════════════════════
    // [1] SSO 세션 사전 등록 성공 → 201
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[SSO-1] REGISTER_OK: 필수 헤더+바디 완비 → 201 PRE_REGISTERED")
    void register_validRequest_returns201() {
        // Given
        Map<String, String> headers = Map.of(
                "x-agency-code",    AGENCY_SSO,
                "x-source-system",  "IdO",
                "x-correlation-id", "cid-sso-001"
        );
        Map<String, Object> body = Map.of(
                "ticketId",  TICKET_ID,
                "qimUserId", "sso-qim-user-001",
                "authLevel", "L2",
                "expiresAt", Instant.now().plusSeconds(300).toString()
        );

        // When
        ResponseEntity<?> resp = ssoMock.registerSsoSession(headers, body);

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        @SuppressWarnings("unchecked")
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody.get("status")).isEqualTo("PRE_REGISTERED");
        assertThat(respBody.get("ticketId")).isEqualTo(TICKET_ID);
        assertThat(respBody.get("agencyCode")).isEqualTo(AGENCY_SSO);
    }

    // ════════════════════════════════════════════════════════════════════════
    // [2] 필수 항목 누락 → 400
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[SSO-2] REGISTER_MISSING: X-Agency-Code 누락 → 400 MISSING_REQUIRED_FIELDS")
    void register_missingAgencyCode_returns400() {
        // Given: X-Agency-Code 없음
        Map<String, String> headers = Map.of(
                "x-source-system",  "IdO",
                "x-correlation-id", "cid-sso-002"
        );
        Map<String, Object> body = Map.of(
                "ticketId",  TICKET_ID,
                "qimUserId", "user-002"
        );

        // When
        ResponseEntity<?> resp = ssoMock.registerSsoSession(headers, body);

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> respBody = (Map<String, Object>) resp.getBody();
        assertThat(respBody.get("error")).isEqualTo("MISSING_REQUIRED_FIELDS");
    }

    // ════════════════════════════════════════════════════════════════════════
    // [3] 사전 등록 → 활성화 흐름
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[SSO-3] ACTIVATE_OK: 사전 등록 후 활성화 → 200 ACTIVATED + status 변경")
    void register_then_activate_returns200Activated() {
        // Given: 먼저 등록
        Map<String, String> headers = Map.of(
                "x-agency-code",    AGENCY_SSO,
                "x-source-system",  "IdO",
                "x-correlation-id", "cid-sso-003"
        );
        Map<String, Object> body = Map.of(
                "ticketId",  TICKET_ID,
                "qimUserId", "sso-user-003",
                "authLevel", "L2",
                "expiresAt", Instant.now().plusSeconds(300).toString()
        );
        ssoMock.registerSsoSession(headers, body);

        // When: 사용자 콜백 도달 시뮬레이션 (활성화)
        ResponseEntity<?> activateResp = ssoMock.activateSsoSession(TICKET_ID);

        // Then
        assertThat(activateResp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> respBody = (Map<String, Object>) activateResp.getBody();
        assertThat(respBody.get("status")).isEqualTo("ACTIVATED");
        assertThat(respBody.get("ticketId")).isEqualTo(TICKET_ID);

        // 세션 조회로 ACTIVATED 상태 확인
        @SuppressWarnings("unchecked")
        Map<String, Object> getResp = (Map<String, Object>) ssoMock.getSession(TICKET_ID).getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> session = (Map<String, Object>) getResp.get("session");
        assertThat(session.get("_ssoStatus")).isEqualTo("ACTIVATED");
    }

    // ════════════════════════════════════════════════════════════════════════
    // [4] 미등록 ticketId 활성화 → 404
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[SSO-4] ACTIVATE_NOT_FOUND: 미등록 ticketId 활성화 → 404 SSO_SESSION_NOT_FOUND")
    void activate_notFound_returns404() {
        // When: 등록 없이 바로 활성화
        ResponseEntity<?> resp = ssoMock.activateSsoSession("non-existent-sso-ticket");

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("error")).isEqualTo("SSO_SESSION_NOT_FOUND");
    }
}

package io.github.hipstermin.idem.tenant.pattern;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hipstermin.idem.tenant.mock.MockApacheGateController;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * APACHE_GATE 패턴 — {@code MockApacheGateController} 단위 테스트
 *
 * <p>APACHE_GATE 패턴은 레거시 Apache httpd + mod_auth 환경에서
 * {@code ApacheGateHandoffStrategy}가 커스텀 헤더를 전달합니다.
 * {@link MockApacheGateController}가 헤더 검증 및 수신을 담당합니다.
 *
 * <p>검증 시나리오 (4종):
 * <ol>
 *   <li>VALID_HEADERS     — 필수 헤더 완비 → 200 OK + validation=PASSED</li>
 *   <li>MISSING_HEADER    — X-Remote-User 누락 → 400 MISSING_REQUIRED_HEADERS</li>
 *   <li>INVALID_AUTH_LEVEL — X-Auth-Level=INVALID → 400 INVALID_AUTH_LEVEL</li>
 *   <li>CLEAR_LOG         — DELETE /received → 수신 로그 초기화</li>
 * </ol>
 */
@DisplayName("APACHE_GATE 패턴 — MockApacheGateController 헤더 검증 단위 테스트")
class AgencyPatternApacheGateTest {

    private MockApacheGateController apacheMock;

    private static final String AGENCY_APACHE = "AGENCY_APACHEGATE_001";

    @BeforeEach
    void setUp() {
        apacheMock = new MockApacheGateController();
    }

    // ════════════════════════════════════════════════════════════════════════
    // [1] 필수 헤더 완비 → 200 OK
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[APACHEGATE-1] VALID_HEADERS: 필수 헤더 완비 → 200 OK + validation=PASSED")
    void validHeaders_returns200() {
        // Given
        Map<String, String> headers = Map.of(
                "x-remote-user",    "apachegate-user-001",
                "x-auth-level",     "L2",
                "x-handoff-token",  "eyJhbGciOiJSUzI1NiJ9.mockPayload",
                "x-session-expiry", Instant.now().plusSeconds(1800).toString(),
                "x-correlation-id", "cid-apache-001"
        );

        // When
        ResponseEntity<?> resp = apacheMock.receiveApacheGate(headers, null);

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("status")).isEqualTo("ACCEPTED");
        assertThat(body.get("validation")).isEqualTo("PASSED");
        assertThat(body.get("xRemoteUser")).isEqualTo("apachegate-user-001");
        assertThat(body.get("xAuthLevel")).isEqualTo("L2");
    }

    // ════════════════════════════════════════════════════════════════════════
    // [2] 필수 헤더 누락 → 400
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[APACHEGATE-2] MISSING_HEADER: X-Remote-User 누락 → 400 MISSING_REQUIRED_HEADERS")
    void missingRemoteUser_returns400() {
        // Given: X-Remote-User 없음
        Map<String, String> headers = Map.of(
                "x-auth-level",     "L2",
                "x-handoff-token",  "mock-token",
                "x-correlation-id", "cid-apache-002"
        );

        // When
        ResponseEntity<?> resp = apacheMock.receiveApacheGate(headers, null);

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("error")).isEqualTo("MISSING_REQUIRED_HEADERS");
        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) body.get("missing");
        assertThat(missing).contains("X-Remote-User");
    }

    // ════════════════════════════════════════════════════════════════════════
    // [3] 잘못된 Auth Level → 400
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[APACHEGATE-3] INVALID_AUTH_LEVEL: X-Auth-Level=INVALID → 400 INVALID_AUTH_LEVEL")
    void invalidAuthLevel_returns400() {
        // Given: X-Auth-Level 잘못된 값
        Map<String, String> headers = Map.of(
                "x-remote-user",    "apachegate-user-003",
                "x-auth-level",     "LEVEL_X",            // 유효하지 않은 값
                "x-handoff-token",  "mock-token",
                "x-correlation-id", "cid-apache-003"
        );

        // When
        ResponseEntity<?> resp = apacheMock.receiveApacheGate(headers, null);

        // Then
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("error")).isEqualTo("INVALID_AUTH_LEVEL");
        assertThat(body.get("received")).isEqualTo("LEVEL_X");
    }

    // ════════════════════════════════════════════════════════════════════════
    // [4] 수신 로그 초기화
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[APACHEGATE-4] CLEAR_LOG: 수신 후 DELETE → 로그 초기화 확인")
    void clearLog_removesAllReceived() {
        // Given: 2건 수신
        Map<String, String> headers = Map.of(
                "x-remote-user",    "user-clear-test",
                "x-auth-level",     "L1",
                "x-handoff-token",  "tok",
                "x-correlation-id", "cid-clear"
        );
        apacheMock.receiveApacheGate(headers, null);
        apacheMock.receiveApacheGate(headers, null);

        // When
        ResponseEntity<?> clearResp = apacheMock.clearReceived();

        // Then
        assertThat(clearResp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> clearBody = (Map<String, Object>) clearResp.getBody();
        assertThat((int) clearBody.get("removed")).isEqualTo(2);

        // 이후 목록 0건
        @SuppressWarnings("unchecked")
        Map<String, Object> listBody = (Map<String, Object>) apacheMock.listReceived().getBody();
        assertThat((int) listBody.get("total")).isEqualTo(0);
    }
}

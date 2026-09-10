package io.github.hipstermin.idem.tenant.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hipstermin.idem.tenant.session.AgencySessionService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * WebhookInboundController — HMAC 서명 검증 및 이벤트 처리 단위 테스트
 *
 * <p>설계서 §15.2 — Webhook 수신 보안 요구사항 검증:
 * <ul>
 *   <li>HMAC-SHA256 서명 검증</li>
 *   <li>타임스탬프 ±5분 이내 검증</li>
 *   <li>중복 이벤트 방지</li>
 *   <li>이벤트 타입별 처리 (HANDOFF_REVOKED, SESSION_ADVISORY 등)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookInboundController — 서명/이벤트 처리 단위 테스트")
class WebhookSignatureTest {

    private static final String SIGNING_SECRET = "test-signing-secret-for-unit-tests";
    private static final String AGENCY_CODE    = "AGENCY_TEST_001";

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private AgencySessionService agencySessionService;

    private WebhookInboundController controller;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        controller = new WebhookInboundController(jdbcTemplate, agencySessionService, objectMapper);
        ReflectionTestUtils.setField(controller, "signingSecret", SIGNING_SECRET);
        ReflectionTestUtils.setField(controller, "agencyCode", AGENCY_CODE);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 서명 검증
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("HMAC 서명 정상 — 이벤트 수신 성공 (ACCEPTED)")
    void testValidSignature_accepted() throws Exception {
        String eventId    = UUID.randomUUID().toString();
        String timestamp  = String.valueOf(Instant.now().getEpochSecond());
        String body       = buildPayload(eventId, "MEMBER_REGISTERED");
        String signature  = computeSignature(timestamp, body);

        // 중복 체크: 미처리
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql.contains("webhook_inbound")),
                eq(Integer.class), eq(eventId), eq(AGENCY_CODE)
        )).thenReturn(0);

        // saveInbound / enqueueEvent / updateInboundStatus — lenient (실제 호출 패턴에 유연하게)
        lenient().when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, String>> response = (ResponseEntity<Map<String, String>>)
                controller.inbound(signature, timestamp, "corr-001", "ido", body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("status", "ACCEPTED");
    }

    @Test
    @DisplayName("HMAC 서명 불일치 — 401 SIGNATURE_VERIFICATION_FAILED 반환")
    void testInvalidSignature_rejected() throws Exception {
        String eventId   = UUID.randomUUID().toString();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String body      = buildPayload(eventId, "HANDOFF_ISSUED");
        String badSig    = "sha256=aaaa000000000000000000000000000000000000000000000000000000000000";

        // 중복 체크
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql.contains("webhook_inbound")),
                eq(Integer.class), eq(eventId), eq(AGENCY_CODE)
        )).thenReturn(0);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, String>> response = (ResponseEntity<Map<String, String>>)
                controller.inbound(badSig, timestamp, "corr-001", "ido", body);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).containsKey("error");
        assertThat(response.getBody().get("error")).isEqualTo("SIGNATURE_VERIFICATION_FAILED");
    }

    @Test
    @DisplayName("타임스탬프 5분 초과 — 401 INVALID_TIMESTAMP 반환")
    void testExpiredTimestamp_rejected() throws Exception {
        String eventId   = UUID.randomUUID().toString();
        String oldTs     = String.valueOf(Instant.now().minusSeconds(400).getEpochSecond()); // 6분 40초 전
        String body      = buildPayload(eventId, "HANDOFF_ISSUED");
        String signature = computeSignature(oldTs, body);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, String>> response = (ResponseEntity<Map<String, String>>)
                controller.inbound(signature, oldTs, "corr-001", "ido", body);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().get("error")).isEqualTo("INVALID_TIMESTAMP");
    }

    @Test
    @DisplayName("타임스탬프 null — 401 INVALID_TIMESTAMP 반환")
    void testNullTimestamp_rejected() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String body    = buildPayload(eventId, "HANDOFF_ISSUED");

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, String>> response = (ResponseEntity<Map<String, String>>)
                controller.inbound("sha256=somevalue", null, "corr-001", "ido", body);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().get("error")).isEqualTo("INVALID_TIMESTAMP");
    }

    @Test
    @DisplayName("타임스탬프 ±5분 이내 — 유효 처리 (경계값)")
    void testTimestampBoundary_valid() throws Exception {
        String eventId = UUID.randomUUID().toString();
        // 정확히 299초 전 (5분-1초)
        String ts      = String.valueOf(Instant.now().minusSeconds(299).getEpochSecond());
        String body    = buildPayload(eventId, "HANDOFF_EXPIRED");
        String sig     = computeSignature(ts, body);

        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql.contains("webhook_inbound")),
                eq(Integer.class), eq(eventId), eq(AGENCY_CODE)
        )).thenReturn(0);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, String>> response = (ResponseEntity<Map<String, String>>)
                controller.inbound(sig, ts, "corr-001", "ido", body);

        // 서명은 정상, 타임스탬프도 유효 → ACCEPTED
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 중복 이벤트 방지
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("중복 이벤트 수신 — 200 ALREADY_PROCESSED 반환 (멱등)")
    void testDuplicateEvent_skipped() throws Exception {
        String eventId   = UUID.randomUUID().toString();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String body      = buildPayload(eventId, "HANDOFF_ISSUED");
        String signature = computeSignature(timestamp, body);

        // 중복 체크: 이미 처리됨
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql.contains("webhook_inbound")),
                eq(Integer.class), eq(eventId), eq(AGENCY_CODE)
        )).thenReturn(1);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, String>> response = (ResponseEntity<Map<String, String>>)
                controller.inbound(signature, timestamp, "corr-001", "ido", body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("status")).isEqualTo("ALREADY_PROCESSED");
    }

    // ─────────────────────────────────────────────────────────────────────
    // HANDOFF_REVOKED — 세션 무효화
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("HANDOFF_REVOKED — AgencySessionService.invalidateByTicketId 호출 검증")
    void testHandoffRevoked_invalidateSession() throws Exception {
        String eventId   = UUID.randomUUID().toString();
        String ticketId  = "ticket-revoked-001";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        String body = objectMapper.writeValueAsString(Map.of(
                "eventId",      eventId,
                "eventType",    "HANDOFF_REVOKED",
                "ticketId",     ticketId,
                "revokeReason", "SECURITY_POLICY"
        ));
        String sig = computeSignature(timestamp, body);

        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql.contains("webhook_inbound")),
                eq(Integer.class), eq(eventId), eq(AGENCY_CODE)
        )).thenReturn(0);
        when(agencySessionService.invalidateByTicketId(anyString(), anyString(), anyString()))
                .thenReturn(1);

        controller.inbound(sig, timestamp, "corr-001", "ido", body);

        verify(agencySessionService, times(1))
                .invalidateByTicketId(eq(ticketId), contains("HANDOFF_REVOKED"), any());
    }

    // ─────────────────────────────────────────────────────────────────────
    // SESSION_ADVISORY — Mandatory 세션 강제 종료
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SESSION_ADVISORY severity=Mandatory — invalidateByQimUserId 호출")
    void testSessionAdvisory_mandatory_invalidate() throws Exception {
        String eventId   = UUID.randomUUID().toString();
        String qimUserId = "qim-user-789";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        String body = objectMapper.writeValueAsString(Map.of(
                "eventId",   eventId,
                "eventType", "SESSION_ADVISORY",
                "severity",  "Mandatory",
                "qimUserId", qimUserId,
                "reason",    "ACCOUNT_COMPROMISED"
        ));
        String sig = computeSignature(timestamp, body);

        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql.contains("webhook_inbound")),
                eq(Integer.class), eq(eventId), eq(AGENCY_CODE)
        )).thenReturn(0);
        when(agencySessionService.invalidateByQimUserId(anyString(), anyString(), anyString()))
                .thenReturn(2);

        controller.inbound(sig, timestamp, "corr-001", "ido", body);

        verify(agencySessionService, times(1))
                .invalidateByQimUserId(eq(qimUserId), eq("MANDATORY_SECURITY_TERMINATE"), any());
    }

    @Test
    @DisplayName("SESSION_ADVISORY severity=Warning — invalidateByQimUserId 호출 안 함")
    void testSessionAdvisory_warning_noInvalidate() throws Exception {
        String eventId   = UUID.randomUUID().toString();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        String body = objectMapper.writeValueAsString(Map.of(
                "eventId",   eventId,
                "eventType", "SESSION_ADVISORY",
                "severity",  "Warning",
                "qimUserId", "qim-user-001",
                "reason",    "SUSPICIOUS_LOGIN"
        ));
        String sig = computeSignature(timestamp, body);

        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql.contains("webhook_inbound")),
                eq(Integer.class), eq(eventId), eq(AGENCY_CODE)
        )).thenReturn(0);

        controller.inbound(sig, timestamp, "corr-001", "ido", body);

        verify(agencySessionService, never()).invalidateByQimUserId(any(), any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────
    // payload 파싱 오류
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("잘못된 JSON payload — 400 INVALID_PAYLOAD 반환")
    void testMalformedPayload_badRequest() throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String badBody   = "{ invalid json }}}";
        String sig       = computeSignature(timestamp, badBody);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, String>> response = (ResponseEntity<Map<String, String>>)
                controller.inbound(sig, timestamp, "corr-001", "ido", badBody);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("error")).isEqualTo("INVALID_PAYLOAD");
    }

    @Test
    @DisplayName("eventId 누락 payload — 400 MISSING_EVENT_FIELDS 반환")
    void testMissingEventId_badRequest() throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        // eventId 없는 payload
        String body = objectMapper.writeValueAsString(Map.of("eventType", "HANDOFF_ISSUED"));
        String sig  = computeSignature(timestamp, body);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, String>> response = (ResponseEntity<Map<String, String>>)
                controller.inbound(sig, timestamp, "corr-001", "ido", body);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("error")).isEqualTo("MISSING_EVENT_FIELDS");
    }

    // ─────────────────────────────────────────────────────────────────────
    // 헬퍼
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 테스트용 HMAC-SHA256 서명 계산
     */
    private String computeSignature(String timestamp, String body) throws Exception {
        String signTarget = timestamp + "." + body;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hmac = mac.doFinal(signTarget.getBytes(StandardCharsets.UTF_8));
        return "sha256=" + HexFormat.of().formatHex(hmac);
    }

    /**
     * 이벤트 payload JSON 생성
     */
    private String buildPayload(String eventId, String eventType) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "eventId",   eventId,
                "eventType", eventType,
                "ticketId",  "ticket-" + eventId.substring(0, 8),
                "issuedAt",  Instant.now().toString()
        ));
    }
}

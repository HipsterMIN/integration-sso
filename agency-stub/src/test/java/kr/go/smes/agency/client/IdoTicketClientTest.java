package kr.go.smes.agency.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * IdoTicketClient — Handoff Ticket 발급 클라이언트 단위 테스트
 *
 * <p>RestTemplate Mock으로 HTTP 호출 없이 클라이언트 로직 검증
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IdoTicketClient — Ticket 발급 단위 테스트")
class IdoTicketClientTest {

    @Mock
    private RestTemplate restTemplate;

    private IdoTicketClient client;

    @BeforeEach
    void setUp() {
        client = new IdoTicketClient(restTemplate);
        ReflectionTestUtils.setField(client, "idoBaseUrl", "http://localhost:8083");
        ReflectionTestUtils.setField(client, "agencyCode",  "AGENCY_TEST_001");
        ReflectionTestUtils.setField(client, "apiKey",      "test-api-key-dev-001");
    }

    // ─────────────────────────────────────────────────────────────────────
    // 정상 발급
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Ticket 발급 성공 — ticketId / expiresAt 반환")
    void testIssue_success() {
        IdoTicketClient.TicketResponse ticketResponse = new IdoTicketClient.TicketResponse();
        ReflectionTestUtils.setField(ticketResponse, "ticketId", "ticket-abc-123");
        ReflectionTestUtils.setField(ticketResponse, "expiresAt", Instant.now().plusSeconds(300));

        when(restTemplate.exchange(
                eq("http://localhost:8083/api/v1/handoff/issue"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(IdoTicketClient.TicketResponse.class)
        )).thenReturn(ResponseEntity.ok(ticketResponse));

        IdoTicketClient.TicketResult result = client.issue(
                "qim-user-001",
                "auth-result-001",
                "L2",
                "QSIGN_CERT",
                "corr-001"
        );

        assertThat(result.ticketId()).isEqualTo("ticket-abc-123");
        assertThat(result.correlationId()).isEqualTo("corr-001");
        assertThat(result.expiresAt()).isAfter(Instant.now());
    }

    // ─────────────────────────────────────────────────────────────────────
    // HTTP 헤더 검증
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("요청 헤더에 X-Agency-Code, X-Agency-Key, X-Correlation-Id, Idempotency-Key 포함 확인")
    void testIssue_headers() {
        IdoTicketClient.TicketResponse ticketResponse = new IdoTicketClient.TicketResponse();
        ReflectionTestUtils.setField(ticketResponse, "ticketId", "ticket-hdr-check");
        ReflectionTestUtils.setField(ticketResponse, "expiresAt", Instant.now().plusSeconds(300));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Object>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);

        when(restTemplate.exchange(
                anyString(), eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(IdoTicketClient.TicketResponse.class)
        )).thenReturn(ResponseEntity.ok(ticketResponse));

        client.issue("qim-001", "auth-001", "L1", "QSIGN_CERT", "corr-hdr");

        HttpHeaders headers = entityCaptor.getValue().getHeaders();
        assertThat(headers.getFirst("X-Agency-Code")).isEqualTo("AGENCY_TEST_001");
        assertThat(headers.getFirst("X-Agency-Key")).isEqualTo("test-api-key-dev-001");
        assertThat(headers.getFirst("X-Correlation-Id")).isEqualTo("corr-hdr");
        assertThat(headers.getFirst("Idempotency-Key")).isNotBlank(); // UuidV7 생성됨
        assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 오류 케이스
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("응답 body null — IdoTicketIssuanceException 발생")
    void testIssue_nullResponseBody() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(IdoTicketClient.TicketResponse.class)))
                .thenReturn(ResponseEntity.ok(null));

        assertThatThrownBy(() ->
                client.issue("qim", "auth", "L2", "QSIGN", "corr")
        ).isInstanceOf(IdoTicketClient.IdoTicketIssuanceException.class)
         .hasMessageContaining("ticketId");
    }

    @Test
    @DisplayName("응답 ticketId null — IdoTicketIssuanceException 발생")
    void testIssue_nullTicketId() {
        IdoTicketClient.TicketResponse resp = new IdoTicketClient.TicketResponse();
        // ticketId 미설정 (null)

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(IdoTicketClient.TicketResponse.class)))
                .thenReturn(ResponseEntity.ok(resp));

        assertThatThrownBy(() ->
                client.issue("qim", "auth", "L2", "QSIGN", "corr")
        ).isInstanceOf(IdoTicketClient.IdoTicketIssuanceException.class)
         .satisfies(ex -> {
             IdoTicketClient.IdoTicketIssuanceException e = (IdoTicketClient.IdoTicketIssuanceException) ex;
             assertThat(e.getErrorCode()).isEqualTo("EMPTY_TICKET_RESPONSE");
         });
    }

    @Test
    @DisplayName("HTTP 4xx 오류 — IdoTicketIssuanceException (CLIENT_ERROR_401) 발생")
    void testIssue_http401Error() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(IdoTicketClient.TicketResponse.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED, "Unauthorized",
                        HttpHeaders.EMPTY, "UNAUTHORIZED".getBytes(), null
                ));

        assertThatThrownBy(() ->
                client.issue("qim", "auth", "L2", "QSIGN", "corr")
        ).isInstanceOf(IdoTicketClient.IdoTicketIssuanceException.class)
         .satisfies(ex -> {
             IdoTicketClient.IdoTicketIssuanceException e = (IdoTicketClient.IdoTicketIssuanceException) ex;
             assertThat(e.getErrorCode()).isEqualTo("CLIENT_ERROR_401");
         });
    }

    @Test
    @DisplayName("HTTP 4xx 403 오류 — IdoTicketIssuanceException (CLIENT_ERROR_403) 발생")
    void testIssue_http403Error() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(IdoTicketClient.TicketResponse.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.FORBIDDEN, "Forbidden",
                        HttpHeaders.EMPTY, "FORBIDDEN".getBytes(), null
                ));

        assertThatThrownBy(() ->
                client.issue("qim", "auth", "L2", "QSIGN", "corr")
        ).isInstanceOf(IdoTicketClient.IdoTicketIssuanceException.class)
         .satisfies(ex -> {
             IdoTicketClient.IdoTicketIssuanceException e = (IdoTicketClient.IdoTicketIssuanceException) ex;
             assertThat(e.getErrorCode()).isEqualTo("CLIENT_ERROR_403");
         });
    }

    @Test
    @DisplayName("네트워크 오류 (ResourceAccessException) — 상위로 전파 (Retry 대상)")
    void testIssue_networkError() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(IdoTicketClient.TicketResponse.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() ->
                client.issue("qim", "auth", "L2", "QSIGN", "corr")
        ).isInstanceOf(ResourceAccessException.class)
         .hasMessageContaining("Connection refused");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Idempotency Key 유일성
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("동일 파라미터로 두 번 호출 시 Idempotency-Key는 서로 다름")
    void testIdempotencyKey_uniquePerCall() {
        IdoTicketClient.TicketResponse ticketResponse = new IdoTicketClient.TicketResponse();
        ReflectionTestUtils.setField(ticketResponse, "ticketId", "ticket-idempotency-test");
        ReflectionTestUtils.setField(ticketResponse, "expiresAt", Instant.now().plusSeconds(300));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Object>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);

        when(restTemplate.exchange(
                anyString(), eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(IdoTicketClient.TicketResponse.class)
        )).thenReturn(ResponseEntity.ok(ticketResponse));

        client.issue("qim", "auth", "L2", "QSIGN", "corr-1");
        client.issue("qim", "auth", "L2", "QSIGN", "corr-2");

        java.util.List<HttpEntity<Object>> calls = entityCaptor.getAllValues();
        String key1 = calls.get(0).getHeaders().getFirst("Idempotency-Key");
        String key2 = calls.get(1).getHeaders().getFirst("Idempotency-Key");

        assertThat(key1).isNotEqualTo(key2);
        assertThat(key1).isNotBlank();
        assertThat(key2).isNotBlank();
    }
}

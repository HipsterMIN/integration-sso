package kr.go.smes.ido.webhook;

import kr.go.smes.ido.audit.AuditLogPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * WebhookDispatchOutboxRelay 단위 테스트
 *
 * <p>테스트 전략: Observable behavior 기반 — DB 내부 SQL 대신
 * 감사 로그(auditLogPublisher) + HTTP 호출(restTemplate)을 통해 검증.
 *
 * <p>검증 범위:
 * <ul>
 *   <li>HTTP 200 성공 → WEBHOOK_DISPATCHED 감사 로그 발행, WEBHOOK_DISPATCH_FAILED 미발행</li>
 *   <li>HTTP 5xx / 네트워크 오류 → maxRetry 미초과 시 WEBHOOK_DISPATCH_FAILED 감사 로그 미발행</li>
 *   <li>maxRetry 초과 → WEBHOOK_DISPATCH_FAILED 감사 로그 발행</li>
 *   <li>HTTP 404/410 → 즉시 WEBHOOK_DISPATCH_FAILED 발행 (재시도 없음)</li>
 *   <li>PENDING 레코드 없으면 HTTP 요청 없음</li>
 *   <li>HMAC 서명 — computeHmacSignature() 호출 및 "timestamp.payload" 형식 검증</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WebhookDispatchOutboxRelay 단위 테스트")
class WebhookDispatchOutboxRelayTest {

    @Mock JdbcTemplate               jdbcTemplate;
    @Mock RestTemplate               restTemplate;
    @Mock WebhookDispatcherService   webhookDispatcherService;
    @Mock AuditLogPublisher          auditLogPublisher;

    WebhookDispatchOutboxRelay sut;

    private static final String DISPATCH_ID     = "dispatch-001";
    private static final String AGENCY_CODE     = "AGENCY-001";
    private static final String ENDPOINT_URL    = "https://agency.go.kr/webhook";
    private static final String PAYLOAD_JSON    = "{\"eventType\":\"HANDOFF_ISSUED\"}";
    private static final String CORRELATION_ID  = "corr-001";
    private static final String SOURCE_EVENT_ID = "src-evt-001";
    private static final String EVENT_TYPE      = "HANDOFF_ISSUED";

    @BeforeEach
    void setUp() {
        sut = new WebhookDispatchOutboxRelay(
                jdbcTemplate, restTemplate, webhookDispatcherService, auditLogPublisher);
        ReflectionTestUtils.setField(sut, "relayIntervalMs",   500L);
        ReflectionTestUtils.setField(sut, "relayBatchSize",    50);
        ReflectionTestUtils.setField(sut, "defaultMaxRetry",   3);
        ReflectionTestUtils.setField(sut, "connectTimeoutMs",  3000);
        ReflectionTestUtils.setField(sut, "readTimeoutMs",     8000);
        ReflectionTestUtils.setField(sut, "platformVersion",   "1.0");

        given(webhookDispatcherService.computeHmacSignature(any(), any()))
                .willReturn("sha256=aabbccdd");
        willDoNothing().given(auditLogPublisher).publish(any());

        // jdbcTemplate.update() — varargs 모두 허용, 리턴 1
        given(jdbcTemplate.update(anyString(), (Object[]) any())).willReturn(1);
    }

    // ── 픽스처 헬퍼 ─────────────────────────────────────────────────────────

    /**
     * fetchPendingBatch() 결과 Mock 데이터 생성
     * retry_count와 max_retry를 주입하여 재시도 상태를 시뮬레이션
     */
    private Map<String, Object> buildRow(int retryCount, int maxRetry) {
        return Map.of(
                "dispatch_id",       DISPATCH_ID,
                "agency_code",       AGENCY_CODE,
                "endpoint_url",      ENDPOINT_URL,
                "source_event_id",   SOURCE_EVENT_ID,
                "source_event_type", EVENT_TYPE,
                "correlation_id",    CORRELATION_ID,
                "payload",           PAYLOAD_JSON,
                "retry_count",       retryCount,
                "max_retry",         maxRetry,
                "signing_secret_hash", "signing-secret"
        );
    }

    /** fetchPendingBatch() queryForList stub 설정 헬퍼 */
    private void stubPendingBatch(int retryCount, int maxRetry) {
        given(jdbcTemplate.queryForList(anyString(), (Object[]) any()))
                .willReturn(List.of(buildRow(retryCount, maxRetry)));
    }

    /** fetchPendingBatch() 빈 결과 stub */
    private void stubEmptyBatch() {
        given(jdbcTemplate.queryForList(anyString(), (Object[]) any()))
                .willReturn(List.of());
    }

    // ════════════════════════════════════════════════════════════════════════
    // 정상 발송 성공
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("HTTP 2xx 발송 성공")
    class SuccessTests {

        @Test
        @DisplayName("HTTP 200 응답 → WEBHOOK_DISPATCHED 감사 로그 발행")
        void http200_publishesAuditDispatched() {
            stubPendingBatch(0, 3);
            given(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                    .willReturn(ResponseEntity.ok("OK"));

            sut.relay();

            then(auditLogPublisher).should(times(1)).publish(argThat(entry ->
                    "WEBHOOK_DISPATCHED".equals(entry.eventAction())
                    && AGENCY_CODE.equals(entry.agencyCode())
                    && "WEBHOOK".equals(entry.resourceType())
            ));
        }

        @Test
        @DisplayName("HTTP 200 성공 시 WEBHOOK_DISPATCH_FAILED 감사 로그 미발행")
        void http200_doesNotPublishFailureAudit() {
            stubPendingBatch(0, 3);
            given(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                    .willReturn(ResponseEntity.ok("OK"));

            sut.relay();

            then(auditLogPublisher).should(never()).publish(argThat(entry ->
                    "WEBHOOK_DISPATCH_FAILED".equals(entry.eventAction())
            ));
        }

        @Test
        @DisplayName("PENDING 레코드 없으면 HTTP 요청 없음")
        void noPendingRecords_skipsHttpCall() {
            stubEmptyBatch();

            sut.relay();

            then(restTemplate).should(never()).exchange(anyString(), any(), any(), eq(String.class));
        }

        @Test
        @DisplayName("PENDING 레코드 없으면 감사 로그 발행 없음")
        void noPendingRecords_noAuditLog() {
            stubEmptyBatch();

            sut.relay();

            then(auditLogPublisher).should(never()).publish(any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 재시도 로직 (retry_count < maxRetry)
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("재시도 로직 — maxRetry 미초과 시 FAILED 미전환")
    class RetryTests {

        @Test
        @DisplayName("HTTP 5xx (retry_count=0, max=3) → WEBHOOK_DISPATCH_FAILED 미발행 (RETRYING)")
        void http5xx_firstAttempt_doesNotPublishFailureAudit() {
            stubPendingBatch(0, 3);  // retry_count=0 < maxRetry=3
            given(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                    .willThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

            sut.relay();

            // RETRYING 상태 → FAILED 감사 로그 미발행
            then(auditLogPublisher).should(never()).publish(argThat(entry ->
                    "WEBHOOK_DISPATCH_FAILED".equals(entry.eventAction())
            ));
        }

        @Test
        @DisplayName("HTTP 5xx (retry_count=2, max=3) → WEBHOOK_DISPATCH_FAILED 미발행 (RETRYING)")
        void http5xx_secondToLastAttempt_doesNotPublishFailureAudit() {
            stubPendingBatch(2, 3);  // retry_count=2 < maxRetry=3
            given(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                    .willThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

            sut.relay();

            then(auditLogPublisher).should(never()).publish(argThat(entry ->
                    "WEBHOOK_DISPATCH_FAILED".equals(entry.eventAction())
            ));
        }

        @Test
        @DisplayName("네트워크 타임아웃(ResourceAccessException, retry_count=0) → RETRYING")
        void networkTimeout_firstAttempt_doesNotPublishFailureAudit() {
            stubPendingBatch(0, 3);
            given(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                    .willThrow(new ResourceAccessException("Connection timed out"));

            sut.relay();

            then(auditLogPublisher).should(never()).publish(argThat(entry ->
                    "WEBHOOK_DISPATCH_FAILED".equals(entry.eventAction())
            ));
        }

        @Test
        @DisplayName("HTTP 5xx → HTTP 요청 1회만 시도 (동일 relay() 내 재시도 없음)")
        void http5xx_onlyOneHttpAttemptPerRelay() {
            stubPendingBatch(0, 3);
            given(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                    .willThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

            sut.relay();

            // relay()는 1건당 1회 HTTP 시도 — next_retry_at 예약 후 종료
            then(restTemplate).should(times(1))
                    .exchange(anyString(), any(), any(), eq(String.class));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // DEAD_LETTER 전환 (retry_count >= maxRetry)
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("FAILED(DEAD_LETTER) 전환 — 최대 재시도 초과")
    class DeadLetterTests {

        @Test
        @DisplayName("retry_count=3 == maxRetry(3) 에서 5xx → WEBHOOK_DISPATCH_FAILED 발행")
        void retryExhausted_publishesAuditFailure() {
            stubPendingBatch(3, 3);  // retry_count == maxRetry → 즉시 FAILED
            given(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                    .willThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

            sut.relay();

            then(auditLogPublisher).should(times(1)).publish(argThat(entry ->
                    "WEBHOOK_DISPATCH_FAILED".equals(entry.eventAction())
                    && AGENCY_CODE.equals(entry.agencyCode())
            ));
        }

        @Test
        @DisplayName("retry_count=3 == maxRetry(3) 에서 5xx → WEBHOOK_DISPATCHED 미발행")
        void retryExhausted_doesNotPublishSuccess() {
            stubPendingBatch(3, 3);
            given(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                    .willThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

            sut.relay();

            then(auditLogPublisher).should(never()).publish(argThat(entry ->
                    "WEBHOOK_DISPATCHED".equals(entry.eventAction())
            ));
        }

        @Test
        @DisplayName("HTTP 404 → retry_count=0이어도 즉시 WEBHOOK_DISPATCH_FAILED 발행")
        void http404_immediatelyFails_publishesAuditFailure() {
            stubPendingBatch(0, 3);  // retry_count=0 이어도 404는 즉시 FAILED
            given(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                    .willThrow(HttpClientErrorException.create(
                            HttpStatus.NOT_FOUND, "Not Found", null, null, null));

            sut.relay();

            then(auditLogPublisher).should(times(1)).publish(argThat(entry ->
                    "WEBHOOK_DISPATCH_FAILED".equals(entry.eventAction())
                    && AGENCY_CODE.equals(entry.agencyCode())
            ));
        }

        @Test
        @DisplayName("HTTP 410 → retry_count=0이어도 즉시 WEBHOOK_DISPATCH_FAILED 발행")
        void http410_immediatelyFails_publishesAuditFailure() {
            stubPendingBatch(0, 3);
            given(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                    .willThrow(HttpClientErrorException.create(
                            HttpStatus.GONE, "Gone", null, null, null));

            sut.relay();

            then(auditLogPublisher).should(times(1)).publish(argThat(entry ->
                    "WEBHOOK_DISPATCH_FAILED".equals(entry.eventAction())
            ));
        }

        @Test
        @DisplayName("3회 relay() 시나리오: retry_count=0→2→3, 3번째에서 FAILED 감사 로그 발행")
        void threeConsecutiveRelays_thirdPublishesFailure() {
            // relay 1: retry_count=0 (< 3) → RETRYING
            stubPendingBatch(0, 3);
            given(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                    .willThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));
            sut.relay();
            then(auditLogPublisher).should(never()).publish(argThat(entry ->
                    "WEBHOOK_DISPATCH_FAILED".equals(entry.eventAction())));

            // relay 2: retry_count=2 (< 3) → RETRYING
            reset(restTemplate, auditLogPublisher);
            willDoNothing().given(auditLogPublisher).publish(any());
            given(webhookDispatcherService.computeHmacSignature(any(), any()))
                    .willReturn("sha256=aabbccdd");
            given(jdbcTemplate.queryForList(anyString(), (Object[]) any()))
                    .willReturn(List.of(buildRow(2, 3)));
            given(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                    .willThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));
            sut.relay();
            then(auditLogPublisher).should(never()).publish(argThat(entry ->
                    "WEBHOOK_DISPATCH_FAILED".equals(entry.eventAction())));

            // relay 3: retry_count=3 (== 3) → FAILED
            reset(restTemplate, auditLogPublisher);
            willDoNothing().given(auditLogPublisher).publish(any());
            given(webhookDispatcherService.computeHmacSignature(any(), any()))
                    .willReturn("sha256=aabbccdd");
            given(jdbcTemplate.queryForList(anyString(), (Object[]) any()))
                    .willReturn(List.of(buildRow(3, 3)));
            given(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                    .willThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));
            sut.relay();

            then(auditLogPublisher).should(times(1)).publish(argThat(entry ->
                    "WEBHOOK_DISPATCH_FAILED".equals(entry.eventAction())
                    && AGENCY_CODE.equals(entry.agencyCode())
            ));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // HMAC 서명 헤더 첨부 검증
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("X-Webhook-Signature 헤더 — HMAC-SHA256 서명")
    class SignatureHeaderTests {

        @Test
        @DisplayName("relay()가 단건당 computeHmacSignature() 1회 호출")
        void relay_callsComputeHmacSignatureOnce() {
            stubPendingBatch(0, 3);
            given(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                    .willReturn(ResponseEntity.ok("OK"));

            sut.relay();

            then(webhookDispatcherService).should(times(1))
                    .computeHmacSignature(any(), eq("signing-secret"));
        }

        @Test
        @DisplayName("서명 대상은 'epochSec.payloadJson' 형식 — 재사용 공격 방지")
        void relay_signsTimestampDotPayload() {
            stubPendingBatch(0, 3);
            given(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                    .willReturn(ResponseEntity.ok("OK"));

            sut.relay();

            // computeHmacSignature(signTarget, secret) — signTarget = "{epochSec}.{payloadJson}"
            ArgumentCaptor<String> signTargetCaptor = ArgumentCaptor.forClass(String.class);
            then(webhookDispatcherService).should(times(1))
                    .computeHmacSignature(signTargetCaptor.capture(), any());

            String signTarget = signTargetCaptor.getValue();
            // "{epochSec}.{payloadJson}" 형식
            assertThat(signTarget).contains("." + PAYLOAD_JSON);
            // 앞부분은 숫자 (Unix epoch 초)
            String[] parts = signTarget.split("\\.", 2);
            assertThat(parts[0]).matches("\\d+");
        }

        @Test
        @DisplayName("서명 실패(computeHmacSignature 예외)해도 relay()가 예외 전파하지 않음")
        void signatureFailure_doesNotPropagateException() {
            stubPendingBatch(0, 3);
            given(webhookDispatcherService.computeHmacSignature(any(), any()))
                    .willThrow(new RuntimeException("HMAC key error"));

            assertThatNoException().isThrownBy(() -> sut.relay());
        }
    }
}

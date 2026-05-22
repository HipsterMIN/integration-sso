package kr.go.smes.ido.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.common.event.HandoffEvent;
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
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * WebhookDispatcherService 단위 테스트
 *
 * <p>검증 범위:
 * <ul>
 *   <li>HMAC-SHA256 서명 포맷/정확성 — "sha256=" 접두사, 동일 입력 → 동일 출력, 빈 secret 시 기본값 사용</li>
 *   <li>enqueueForHandoffEvent() — 대상기관 없음/있음, Outbox INSERT, 중복(DO NOTHING) 스킵</li>
 *   <li>감사 로그 WEBHOOK_ENQUEUED 발행</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WebhookDispatcherService 단위 테스트")
class WebhookDispatcherServiceTest {

    @Mock JdbcTemplate        jdbcTemplate;
    @Mock AuditLogPublisher   auditLogPublisher;

    WebhookDispatcherService sut;

    private static final String DEFAULT_SECRET  = "test-webhook-secret";
    private static final String AGENCY_CODE     = "AGENCY-001";
    private static final String ENDPOINT_URL    = "https://agency.go.kr/webhook";
    private static final String CORRELATION_ID  = "corr-webhook-001";

    @BeforeEach
    void setUp() {
        sut = new WebhookDispatcherService(jdbcTemplate, new ObjectMapper(), auditLogPublisher);
        ReflectionTestUtils.setField(sut, "defaultMaxRetry",     3);
        ReflectionTestUtils.setField(sut, "defaultSigningSecret", DEFAULT_SECRET);
        // Sprint α-3 / F4.3 — 기본 테스트는 default secret이 주입된 상태이므로 escape hatch 비활성.
        // 단, "rawSecret 누락 시 default fallback" 시나리오(별도 nested class)는 ON 으로 전환.
        ReflectionTestUtils.setField(sut, "allowEmptySecret",   false);
        ReflectionTestUtils.setField(sut, "platformVersion",     "1.0");
        willDoNothing().given(auditLogPublisher).publish(any());
    }

    // ════════════════════════════════════════════════════════════════════════
    // HMAC-SHA256 서명 테스트
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("computeHmacSignature() — HMAC-SHA256 서명")
    class HmacSignatureTests {

        @Test
        @DisplayName("서명값은 'sha256=' 접두사로 시작")
        void signature_startsWithSha256Prefix() {
            String sig = sut.computeHmacSignature("payload", DEFAULT_SECRET);
            assertThat(sig).startsWith("sha256=");
        }

        @Test
        @DisplayName("동일 payload + secret → 동일 서명 (결정론적)")
        void signature_isDeterministic() {
            String sig1 = sut.computeHmacSignature("hello-world", DEFAULT_SECRET);
            String sig2 = sut.computeHmacSignature("hello-world", DEFAULT_SECRET);
            assertThat(sig1).isEqualTo(sig2);
        }

        @Test
        @DisplayName("서명값은 'sha256=' + 64자 hex (HmacSHA256 = 32바이트 = 64 hex)")
        void signature_hasCorrectLength() {
            String sig = sut.computeHmacSignature("any-payload", DEFAULT_SECRET);
            // "sha256=" = 7자, hex 64자 = 합계 71자
            assertThat(sig).hasSize(7 + 64);
        }

        @Test
        @DisplayName("서명값이 직접 계산한 HmacSHA256과 일치")
        void signature_matchesManuallyComputedHmac() throws Exception {
            String payload = "{\"ticketId\":\"t1\",\"agencyCode\":\"AGENCY-001\"}";
            String secret  = "my-test-secret";

            // 직접 계산
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest   = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(digest);

            String actual = sut.computeHmacSignature(payload, secret);
            assertThat(actual).isEqualTo(expected);
        }

        @Test
        @DisplayName("[F4.3] rawSecret이 null이고 allow-empty-secret=false면 IllegalArgumentException")
        void signature_throwsWhenRawSecretIsNullAndStrictMode() {
            // 운영 모드(allow-empty-secret=false)에서는 fallback 금지
            ReflectionTestUtils.setField(sut, "allowEmptySecret", false);
            assertThatThrownBy(() -> sut.computeHmacSignature("test-payload", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("F4.3 Guard")
                    .hasMessageContaining("rawSecret 누락");
        }

        @Test
        @DisplayName("[F4.3] rawSecret이 blank이고 allow-empty-secret=false면 IllegalArgumentException")
        void signature_throwsWhenRawSecretIsBlankAndStrictMode() {
            ReflectionTestUtils.setField(sut, "allowEmptySecret", false);
            assertThatThrownBy(() -> sut.computeHmacSignature("test-payload", "   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("F4.3 Guard");
        }

        @Test
        @DisplayName("[F4.3] allow-empty-secret=true 모드에서는 rawSecret 누락 시 default fallback 허용 (테스트 전용)")
        void signature_usesDefaultSecretWhenAllowEmptyMode() throws Exception {
            // 테스트 전용 escape hatch ON
            ReflectionTestUtils.setField(sut, "allowEmptySecret", true);
            String payload = "test-payload";

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(DEFAULT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest   = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(digest);

            assertThat(sut.computeHmacSignature(payload, null)).isEqualTo(expected);
            assertThat(sut.computeHmacSignature(payload, "   ")).isEqualTo(expected);
        }

        @Test
        @DisplayName("다른 payload → 다른 서명 (충돌 방지 검증)")
        void signature_differsForDifferentPayloads() {
            String sig1 = sut.computeHmacSignature("payload-A", DEFAULT_SECRET);
            String sig2 = sut.computeHmacSignature("payload-B", DEFAULT_SECRET);
            assertThat(sig1).isNotEqualTo(sig2);
        }

        @Test
        @DisplayName("다른 secret → 다른 서명 (키 격리 검증)")
        void signature_differsForDifferentSecrets() {
            String sig1 = sut.computeHmacSignature("same-payload", "secret-A");
            String sig2 = sut.computeHmacSignature("same-payload", "secret-B");
            assertThat(sig1).isNotEqualTo(sig2);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Sprint α-3 / F4.3 — validateSigningSecret() 부팅 검증
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("[F4.3] validateSigningSecret() — 부팅 시점 시크릿 강제 검증")
    class ValidateSigningSecretTests {

        private WebhookDispatcherService freshSut() {
            return new WebhookDispatcherService(jdbcTemplate, new ObjectMapper(), auditLogPublisher);
        }

        @Test
        @DisplayName("[F4.3] signing-secret 비어 있고 allow-empty-secret=false → IllegalStateException")
        void validate_throws_whenSecretBlankAndStrictMode() {
            WebhookDispatcherService bean = freshSut();
            ReflectionTestUtils.setField(bean, "defaultSigningSecret", "");
            ReflectionTestUtils.setField(bean, "allowEmptySecret",   false);

            assertThatThrownBy(() ->
                    ReflectionTestUtils.invokeMethod(bean, "validateSigningSecret"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("F4.3 Guard")
                    .hasMessageContaining("ido.webhook.signing-secret");
        }

        @Test
        @DisplayName("[F4.3] signing-secret이 null이고 allow-empty-secret=false → IllegalStateException")
        void validate_throws_whenSecretNullAndStrictMode() {
            WebhookDispatcherService bean = freshSut();
            ReflectionTestUtils.setField(bean, "defaultSigningSecret", null);
            ReflectionTestUtils.setField(bean, "allowEmptySecret",   false);

            assertThatThrownBy(() ->
                    ReflectionTestUtils.invokeMethod(bean, "validateSigningSecret"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("F4.3 Guard");
        }

        @Test
        @DisplayName("[F4.3] signing-secret 정상 주입 시 부팅 성공")
        void validate_passes_whenSecretInjected() {
            WebhookDispatcherService bean = freshSut();
            ReflectionTestUtils.setField(bean, "defaultSigningSecret", "real-secret-from-vault");
            ReflectionTestUtils.setField(bean, "allowEmptySecret",   false);

            assertThatCode(() ->
                    ReflectionTestUtils.invokeMethod(bean, "validateSigningSecret"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("[F4.3] allow-empty-secret=true (escape hatch) — secret 비어 있어도 부팅 성공 (테스트 전용)")
        void validate_passes_whenEscapeHatchEnabled() {
            WebhookDispatcherService bean = freshSut();
            ReflectionTestUtils.setField(bean, "defaultSigningSecret", "");
            ReflectionTestUtils.setField(bean, "allowEmptySecret",   true);

            assertThatCode(() ->
                    ReflectionTestUtils.invokeMethod(bean, "validateSigningSecret"))
                    .doesNotThrowAnyException();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // enqueueForHandoffEvent() 테스트
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("enqueueForHandoffEvent() — Outbox 적재")
    class EnqueueForHandoffEventTests {

        private HandoffEvent issuedEvent;

        @BeforeEach
        void setUpEvent() {
            issuedEvent = new HandoffEvent(
                    HandoffEvent.TYPE_HANDOFF_ISSUED, "ido",
                    CORRELATION_ID, "qim-user-001", 1L,
                    "ticket-abc", AGENCY_CODE,
                    "auth-res-001", "ISSUED", null
            );
        }

        @Test
        @DisplayName("webhook 대상 기관 없으면 Outbox INSERT 호출 안 됨")
        void noTargetAgency_skipsInsert() {
            // findWebhookTargets → 빈 결과
            given(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                    .willReturn(List.of());

            sut.enqueueForHandoffEvent(issuedEvent, CORRELATION_ID);

            // UPDATE/INSERT 없음
            then(jdbcTemplate).should(never()).update(anyString(), (Object[]) any());
        }

        @Test
        @DisplayName("webhook 대상 기관 있으면 Outbox INSERT 1회 실행")
        void targetAgencyExists_insertsOutbox() {
            // findWebhookTargets → 1개 기관 반환
            Map<String, Object> configRow = Map.of(
                    "agency_code",          AGENCY_CODE,
                    "endpoint_url",         ENDPOINT_URL,
                    "signing_secret_hash",  "signing-secret",
                    "connect_timeout_ms",   3000,
                    "read_timeout_ms",      8000,
                    "max_retry_count",      3,
                    "retry_backoff_ms",     1000,
                    "event_type_filter",    "null"  // null → 전체 허용
            );
            given(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                    .willReturn(List.of(configRow));
            // Outbox INSERT: jdbcTemplate.update(String sql, Object... args)
            doReturn(1).when(jdbcTemplate).update(anyString(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any());

            sut.enqueueForHandoffEvent(issuedEvent, CORRELATION_ID);

            // jdbcTemplate.update() 1회 호출 (Outbox INSERT)
            verify(jdbcTemplate, times(1)).update(anyString(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Outbox INSERT가 0(중복 DO NOTHING)이면 enqueued 카운트에 포함 안 됨")
        void duplicateInsert_isSkipped() {
            Map<String, Object> configRow = Map.of(
                    "agency_code",         AGENCY_CODE,
                    "endpoint_url",        ENDPOINT_URL,
                    "signing_secret_hash", "secret",
                    "connect_timeout_ms",  3000,
                    "read_timeout_ms",     8000,
                    "max_retry_count",     3,
                    "retry_backoff_ms",    1000,
                    "event_type_filter",   "null"
            );
            given(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                    .willReturn(List.of(configRow));
            // 중복 → 0 반환 (ON CONFLICT DO NOTHING)
            doReturn(0).when(jdbcTemplate).update(anyString(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any());

            // 예외 없이 정상 종료되어야 함
            assertThatNoException().isThrownBy(
                    () -> sut.enqueueForHandoffEvent(issuedEvent, CORRELATION_ID));
        }

        @Test
        @DisplayName("event_type_filter가 특정 타입만 허용할 때 — 미매칭 이벤트는 INSERT 스킵")
        void eventTypeFilter_mismatch_skipsInsert() {
            // filter: ["HANDOFF_REVOKED"] 만 허용 → HANDOFF_ISSUED는 제외
            Map<String, Object> configRow = Map.of(
                    "agency_code",         AGENCY_CODE,
                    "endpoint_url",        ENDPOINT_URL,
                    "signing_secret_hash", "secret",
                    "connect_timeout_ms",  3000,
                    "read_timeout_ms",     8000,
                    "max_retry_count",     3,
                    "retry_backoff_ms",    1000,
                    "event_type_filter",   "[\"HANDOFF_REVOKED\"]"
            );
            given(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                    .willReturn(List.of(configRow));

            sut.enqueueForHandoffEvent(issuedEvent, CORRELATION_ID);

            // 필터 미매칭 → INSERT 없음
            then(jdbcTemplate).should(never()).update(anyString(), (Object[]) any());
        }

        @Test
        @DisplayName("event_type_filter가 이벤트 타입을 포함하면 INSERT 실행")
        void eventTypeFilter_matches_insertsOutbox() {
            Map<String, Object> configRow = Map.of(
                    "agency_code",         AGENCY_CODE,
                    "endpoint_url",        ENDPOINT_URL,
                    "signing_secret_hash", "secret",
                    "connect_timeout_ms",  3000,
                    "read_timeout_ms",     8000,
                    "max_retry_count",     3,
                    "retry_backoff_ms",    1000,
                    "event_type_filter",   "[\"HANDOFF_ISSUED\",\"HANDOFF_CONSUMED\"]"
            );
            given(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                    .willReturn(List.of(configRow));
            doReturn(1).when(jdbcTemplate).update(anyString(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any());

            sut.enqueueForHandoffEvent(issuedEvent, CORRELATION_ID);

            verify(jdbcTemplate, times(1)).update(anyString(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("REVOKED 이벤트는 revokeReason이 payload에 포함되어야 함")
        void revokedEvent_includesRevokeReasonInPayload() {
            HandoffEvent revokedEvent = new HandoffEvent(
                    HandoffEvent.TYPE_HANDOFF_REVOKED, "ido",
                    CORRELATION_ID, "qim-user-001", 1L,
                    "ticket-abc", AGENCY_CODE,
                    "auth-res-001", "REVOKED", "incident_containment"
            );

            Map<String, Object> configRow = Map.of(
                    "agency_code",         AGENCY_CODE,
                    "endpoint_url",        ENDPOINT_URL,
                    "signing_secret_hash", "secret",
                    "connect_timeout_ms",  3000,
                    "read_timeout_ms",     8000,
                    "max_retry_count",     3,
                    "retry_backoff_ms",    1000,
                    "event_type_filter",   "null"
            );
            given(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                    .willReturn(List.of(configRow));

            // payload 캡처를 위해 ArgumentCaptor 사용 (7번째 인자 = payloadJson)
            ArgumentCaptor<Object> arg1 = ArgumentCaptor.forClass(Object.class);
            ArgumentCaptor<Object> arg2 = ArgumentCaptor.forClass(Object.class);
            ArgumentCaptor<Object> arg3 = ArgumentCaptor.forClass(Object.class);
            ArgumentCaptor<Object> arg4 = ArgumentCaptor.forClass(Object.class);
            ArgumentCaptor<Object> arg5 = ArgumentCaptor.forClass(Object.class);
            ArgumentCaptor<Object> arg6 = ArgumentCaptor.forClass(Object.class);
            ArgumentCaptor<Object> arg7 = ArgumentCaptor.forClass(Object.class);
            ArgumentCaptor<Object> arg8 = ArgumentCaptor.forClass(Object.class);  // payloadJson
            ArgumentCaptor<Object> arg9 = ArgumentCaptor.forClass(Object.class);
            doReturn(1).when(jdbcTemplate).update(anyString(),
                    arg1.capture(), arg2.capture(), arg3.capture(),
                    arg4.capture(), arg5.capture(), arg6.capture(),
                    arg7.capture(), arg8.capture(), arg9.capture());

            sut.enqueueForHandoffEvent(revokedEvent, CORRELATION_ID);

            // arg8 = payloadJson (0-indexed: dispatchId, agencyCode, endpointUrl,
            //        sourceEventId, sourceEventType, sourceTopic, correlationId, payloadJson, maxRetry)
            String payloadJson = (String) arg8.getValue();
            assertThat(payloadJson).contains("revokeReason");
            assertThat(payloadJson).contains("incident_containment");
        }

        @Test
        @DisplayName("enqueue 성공 후 WEBHOOK_ENQUEUED 감사 로그 발행")
        void enqueue_publishesAuditLog() {
            Map<String, Object> configRow = Map.of(
                    "agency_code",         AGENCY_CODE,
                    "endpoint_url",        ENDPOINT_URL,
                    "signing_secret_hash", "secret",
                    "connect_timeout_ms",  3000,
                    "read_timeout_ms",     8000,
                    "max_retry_count",     3,
                    "retry_backoff_ms",    1000,
                    "event_type_filter",   "null"
            );
            given(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                    .willReturn(List.of(configRow));
            doReturn(1).when(jdbcTemplate).update(anyString(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any());

            sut.enqueueForHandoffEvent(issuedEvent, CORRELATION_ID);

            then(auditLogPublisher).should(times(1)).publish(argThat(entry ->
                    "WEBHOOK_ENQUEUED".equals(entry.eventAction())
                    && AGENCY_CODE.equals(entry.agencyCode())
            ));
        }

        @Test
        @DisplayName("대상 기관 없을 때도 감사 로그는 발행하지 않음")
        void noTargetAgency_doesNotPublishAuditLog() {
            given(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                    .willReturn(List.of());

            sut.enqueueForHandoffEvent(issuedEvent, CORRELATION_ID);

            then(auditLogPublisher).should(never()).publish(any());
        }

        @Test
        @DisplayName("2개 기관에 각각 Outbox INSERT 실행")
        void multipleTargets_insertsForEachAgency() {
            Map<String, Object> config1 = Map.of(
                    "agency_code",         "AGENCY-001",
                    "endpoint_url",        "https://agency1.go.kr/webhook",
                    "signing_secret_hash", "secret1",
                    "connect_timeout_ms",  3000, "read_timeout_ms", 8000,
                    "max_retry_count",     3, "retry_backoff_ms", 1000,
                    "event_type_filter",   "null"
            );
            Map<String, Object> config2 = Map.of(
                    "agency_code",         "AGENCY-002",
                    "endpoint_url",        "https://agency2.go.kr/webhook",
                    "signing_secret_hash", "secret2",
                    "connect_timeout_ms",  3000, "read_timeout_ms", 8000,
                    "max_retry_count",     3, "retry_backoff_ms", 1000,
                    "event_type_filter",   "null"
            );
            given(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                    .willReturn(List.of(config1, config2));
            doReturn(1).when(jdbcTemplate).update(anyString(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any());

            sut.enqueueForHandoffEvent(issuedEvent, CORRELATION_ID);

            // 2기관 각각 INSERT → 2회 호출
            verify(jdbcTemplate, times(2)).update(anyString(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any());
        }
    }
}

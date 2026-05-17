package kr.go.smes.batch.job.ido;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * ProvisioningRelayJob 단위 테스트 (D-04)
 *
 * <h2>검증 항목</h2>
 * <ul>
 *   <li>B-01: enabled=false → relay() 즉시 반환, DB 미조회</li>
 *   <li>B-02: PENDING 건 없음 → relay() 빈 반환, HTTP 미호출</li>
 *   <li>B-03: authType=NONE → restTemplate, 2xx → COMPLETED 갱신, 메트릭+1</li>
 *   <li>B-04: authType=API_KEY → X-Api-Key 헤더 포함, 2xx 성공</li>
 *   <li>B-05: authType=HMAC → X-Signature + X-Timestamp 헤더 포함</li>
 *   <li>B-06: authType=MTLS → mtlsRestTemplate 선택, 추가 인증 헤더 없음</li>
 *   <li>B-07: 4xx(403) 응답 → RETRY 예약</li>
 *   <li>B-08: 404 응답 → 즉시 DEAD_LETTER</li>
 *   <li>B-09: 5xx 응답 → RETRY 예약</li>
 *   <li>B-10: 네트워크 오류 → RETRY 예약</li>
 *   <li>B-11: retry_count >= max_retry → DEAD_LETTER 전환</li>
 *   <li>B-12: 엔드포인트 조회 실패 → RETRY 예약</li>
 *   <li>B-13: API_KEY 자격증명 미등록 → RETRY 예약</li>
 * </ul>
 *
 * <h2>테스트 전략</h2>
 * fetchPending()은 {@code JdbcTemplate.query(sql, RowCallbackHandler, batchSize)} 형태로
 * 콜백 내부에서 ResultSet을 파싱해 rows 리스트를 채움.
 * Mockito {@code doAnswer}로 콜백을 직접 호출해 Mock ResultSet으로 rows를 주입.
 */
@DisplayName("ProvisioningRelayJob — 단위 테스트 (D-04)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProvisioningRelayJobTest {

    // ── 상수 ──────────────────────────────────────────────────────────────────
    private static final String AGENCY_NONE     = "AGENCY_NONE";
    private static final String ENDPOINT_URL    = "https://test-agency.go.kr/provisioning";
    private static final String IDEMPOTENCY_KEY = "idem-test-001";
    private static final String ROW_ID          = "row-uuid-001";

    // ── Mock ─────────────────────────────────────────────────────────────────
    @Mock private JdbcTemplate idoJdbcTemplate;
    @Mock private RestTemplate restTemplate;
    @Mock private RestTemplate mtlsRestTemplate;
    @Mock private ResultSet    mockResultSet;

    // ── SUT ──────────────────────────────────────────────────────────────────
    private ProvisioningRelayJob sut;
    private MeterRegistry        meterRegistry;

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        sut = new ProvisioningRelayJob(idoJdbcTemplate, restTemplate, mtlsRestTemplate, meterRegistry);
        ReflectionTestUtils.setField(sut, "batchSize", 50);
        ReflectionTestUtils.setField(sut, "enabled",   true);

        // 기본 ResultSet 스텁 — 공통 필드
        given(mockResultSet.getString("id")).willReturn(ROW_ID);
        given(mockResultSet.getString("qim_user_id")).willReturn("qim-user-001");
        given(mockResultSet.getString("event_type")).willReturn("USER_REGISTERED");
        given(mockResultSet.getString("payload_json")).willReturn("{\"qimUserId\":\"qim-001\"}");
        given(mockResultSet.getString("idempotency_key")).willReturn(IDEMPOTENCY_KEY);
        given(mockResultSet.getString("status")).willReturn("PENDING");
        given(mockResultSet.getInt("retry_count")).willReturn(0);
        given(mockResultSet.getInt("max_retry")).willReturn(3);
        given(mockResultSet.getString("correlation_id")).willReturn("corr-001");
    }

    // =========================================================================
    // B-01~02: enabled 플래그 및 빈 큐
    // =========================================================================

    @Nested
    @DisplayName("B-01~02: enabled 플래그 및 빈 큐 처리")
    class EnabledAndEmptyTests {

        @Test
        @DisplayName("B-01: enabled=false → relay() 즉시 반환, DB 미조회")
        void b01_disabled_noDbAccess() {
            ReflectionTestUtils.setField(sut, "enabled", false);

            sut.relay();

            verifyNoInteractions(idoJdbcTemplate, restTemplate, mtlsRestTemplate);
        }

        @Test
        @DisplayName("B-02: PENDING 건 없음 → HTTP 미호출, 메트릭 0")
        void b02_emptyQueue_noHttpCall() {
            // RowCallbackHandler 콜백을 호출하지 않음 (빈 결과셋)
            doNothing().when(idoJdbcTemplate)
                .query(anyString(), any(RowCallbackHandler.class), any());

            sut.relay();

            verifyNoInteractions(restTemplate, mtlsRestTemplate);
            assertThat(meterRegistry.counter("batch.relay.provisioning.success").count())
                .isEqualTo(0.0);
        }
    }

    // =========================================================================
    // B-03~06: 인증 방식별 정상 처리
    // =========================================================================

    @Nested
    @DisplayName("B-03~06: 인증 방식별 HTTP 헤더 및 RestTemplate 선택")
    class AuthTypeTests {

        @Test
        @DisplayName("B-03: authType=NONE → restTemplate 선택, COMPLETED 갱신, 메트릭+1")
        void b03_authNone_success() throws Exception {
            given(mockResultSet.getString("agency_code")).willReturn(AGENCY_NONE);
            stubFetchPending();
            stubEndpoint(AGENCY_NONE, ENDPOINT_URL, "NONE", null);
            given(restTemplate.postForEntity(eq(ENDPOINT_URL), any(HttpEntity.class), eq(String.class)))
                .willReturn(ResponseEntity.ok("OK"));

            sut.relay();

            verify(restTemplate).postForEntity(eq(ENDPOINT_URL), any(HttpEntity.class), eq(String.class));
            verifyNoInteractions(mtlsRestTemplate);
            verify(idoJdbcTemplate).update(contains("COMPLETED"), eq(ROW_ID));
            assertThat(meterRegistry.counter("batch.relay.provisioning.success").count())
                .isEqualTo(1.0);
        }

        @Test
        @DisplayName("B-04: authType=API_KEY → X-Api-Key 헤더 포함, 2xx 성공")
        void b04_authApiKey_headerPresent() throws Exception {
            String agencyCode = "AGENCY_APIKEY";
            String ref        = "secrets/agency/AGENCY_APIKEY/api-key";
            given(mockResultSet.getString("agency_code")).willReturn(agencyCode);
            stubFetchPending();
            stubEndpoint(agencyCode, ENDPOINT_URL, "API_KEY", ref);
            given(idoJdbcTemplate.queryForObject(
                    contains("agency_credential_config"), eq(String.class), eq(ref)))
                .willReturn("test-api-key-value");
            given(restTemplate.postForEntity(eq(ENDPOINT_URL), any(HttpEntity.class), eq(String.class)))
                .willReturn(ResponseEntity.ok("OK"));

            sut.relay();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).postForEntity(eq(ENDPOINT_URL), captor.capture(), eq(String.class));
            assertThat(captor.getValue().getHeaders().getFirst("X-Api-Key"))
                .isEqualTo("test-api-key-value");
        }

        @Test
        @DisplayName("B-05: authType=HMAC → X-Signature(64자 hex) + X-Timestamp(숫자) 헤더 포함")
        void b05_authHmac_signatureHeaders() throws Exception {
            String agencyCode = "AGENCY_HMAC";
            String ref        = "secrets/agency/AGENCY_HMAC/hmac-secret";
            given(mockResultSet.getString("agency_code")).willReturn(agencyCode);
            stubFetchPending();
            stubEndpoint(agencyCode, ENDPOINT_URL, "HMAC", ref);
            given(idoJdbcTemplate.queryForObject(
                    contains("agency_credential_config"), eq(String.class), eq(ref)))
                .willReturn("hmac-secret-value");
            given(restTemplate.postForEntity(eq(ENDPOINT_URL), any(HttpEntity.class), eq(String.class)))
                .willReturn(ResponseEntity.ok("OK"));

            sut.relay();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).postForEntity(eq(ENDPOINT_URL), captor.capture(), eq(String.class));

            String signature = captor.getValue().getHeaders().getFirst("X-Signature");
            assertThat(signature).isNotNull().hasSize(64).matches("[0-9a-f]+");

            String timestamp = captor.getValue().getHeaders().getFirst("X-Timestamp");
            assertThat(timestamp).isNotNull().matches("\\d+");
        }

        @Test
        @DisplayName("B-06: authType=MTLS → mtlsRestTemplate 선택, 추가 인증 헤더 없음")
        void b06_authMtls_usesMtlsTemplate() throws Exception {
            String agencyCode = "AGENCY_MTLS";
            given(mockResultSet.getString("agency_code")).willReturn(agencyCode);
            stubFetchPending();
            stubEndpoint(agencyCode, ENDPOINT_URL, "MTLS", null);
            given(mtlsRestTemplate.postForEntity(eq(ENDPOINT_URL), any(HttpEntity.class), eq(String.class)))
                .willReturn(ResponseEntity.ok("OK"));

            sut.relay();

            verify(mtlsRestTemplate).postForEntity(eq(ENDPOINT_URL), any(HttpEntity.class), eq(String.class));
            verifyNoInteractions(restTemplate);
            verify(idoJdbcTemplate).update(contains("COMPLETED"), eq(ROW_ID));
        }
    }

    // =========================================================================
    // B-07~11: HTTP 오류 처리
    // =========================================================================

    @Nested
    @DisplayName("B-07~11: HTTP 오류 및 재시도/DEAD_LETTER 처리")
    class ErrorHandlingTests {

        @Test
        @DisplayName("B-07: 4xx(403) 응답 → RETRY 예약, 메트릭+1")
        void b07_4xxNonGone_retry() throws Exception {
            given(mockResultSet.getString("agency_code")).willReturn(AGENCY_NONE);
            stubFetchPending();
            stubEndpoint(AGENCY_NONE, ENDPOINT_URL, "NONE", null);
            given(restTemplate.postForEntity(eq(ENDPOINT_URL), any(), eq(String.class)))
                .willThrow(HttpClientErrorException.create(
                    HttpStatus.FORBIDDEN, "Forbidden", null, null, null));

            sut.relay();

            // next_retry_at 예약 SQL 실행
            verify(idoJdbcTemplate).update(contains("next_retry_at"), any(), any(), eq(ROW_ID));
            // DEAD_LETTER 아님
            verify(idoJdbcTemplate, never()).update(contains("DEAD_LETTER"), any(), eq(ROW_ID));
            assertThat(meterRegistry.counter("batch.relay.provisioning.retry").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("B-08: 404 응답 → 즉시 DEAD_LETTER (엔드포인트 영구 소멸)")
        void b08_404_immediateDeadLetter() throws Exception {
            given(mockResultSet.getString("agency_code")).willReturn(AGENCY_NONE);
            stubFetchPending();
            stubEndpoint(AGENCY_NONE, ENDPOINT_URL, "NONE", null);
            given(restTemplate.postForEntity(eq(ENDPOINT_URL), any(), eq(String.class)))
                .willThrow(HttpClientErrorException.create(
                    HttpStatus.NOT_FOUND, "Not Found", null, null, null));

            sut.relay();

            verify(idoJdbcTemplate).update(contains("DEAD_LETTER"), contains("endpoint_gone"), eq(ROW_ID));
            verify(idoJdbcTemplate, never()).update(contains("next_retry_at"), any(), any(), eq(ROW_ID));
            assertThat(meterRegistry.counter("batch.relay.provisioning.dead_letter").count())
                .isEqualTo(1.0);
        }

        @Test
        @DisplayName("B-09: 5xx 응답 → RETRY 예약")
        void b09_5xx_retry() throws Exception {
            given(mockResultSet.getString("agency_code")).willReturn(AGENCY_NONE);
            given(mockResultSet.getInt("retry_count")).willReturn(1);
            stubFetchPending();
            stubEndpoint(AGENCY_NONE, ENDPOINT_URL, "NONE", null);
            given(restTemplate.postForEntity(eq(ENDPOINT_URL), any(), eq(String.class)))
                .willThrow(HttpServerErrorException.create(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", null, null, null));

            sut.relay();

            verify(idoJdbcTemplate).update(contains("next_retry_at"), any(), any(), eq(ROW_ID));
            assertThat(meterRegistry.counter("batch.relay.provisioning.retry").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("B-10: 네트워크 오류(ResourceAccessException) → RETRY 예약")
        void b10_networkError_retry() throws Exception {
            given(mockResultSet.getString("agency_code")).willReturn(AGENCY_NONE);
            stubFetchPending();
            stubEndpoint(AGENCY_NONE, ENDPOINT_URL, "NONE", null);
            given(restTemplate.postForEntity(eq(ENDPOINT_URL), any(), eq(String.class)))
                .willThrow(new ResourceAccessException("Connection refused",
                    new IOException("connect")));

            sut.relay();

            verify(idoJdbcTemplate).update(contains("next_retry_at"), any(), any(), eq(ROW_ID));
            assertThat(meterRegistry.counter("batch.relay.provisioning.retry").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("B-11: retry_count=2, max_retry=3 → 실패 시 DEAD_LETTER 전환")
        void b11_maxRetryExceeded_deadLetter() throws Exception {
            given(mockResultSet.getString("agency_code")).willReturn(AGENCY_NONE);
            given(mockResultSet.getInt("retry_count")).willReturn(2);
            given(mockResultSet.getInt("max_retry")).willReturn(3);
            stubFetchPending();
            stubEndpoint(AGENCY_NONE, ENDPOINT_URL, "NONE", null);
            given(restTemplate.postForEntity(eq(ENDPOINT_URL), any(), eq(String.class)))
                .willThrow(HttpServerErrorException.create(
                    HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", null, null, null));

            sut.relay();

            // nextRetry(3) >= maxRetry(3) → DEAD_LETTER
            verify(idoJdbcTemplate).update(contains("DEAD_LETTER"), any(), eq(ROW_ID));
            assertThat(meterRegistry.counter("batch.relay.provisioning.dead_letter").count())
                .isEqualTo(1.0);
        }
    }

    // =========================================================================
    // B-12~13: 엔드포인트/자격증명 조회 실패
    // =========================================================================

    @Nested
    @DisplayName("B-12~13: 엔드포인트 및 자격증명 조회 실패")
    class LookupFailureTests {

        @Test
        @DisplayName("B-12: 엔드포인트 조회 결과 없음(EmptyResult) → RETRY 예약")
        void b12_endpointNotFound_retry() throws Exception {
            given(mockResultSet.getString("agency_code")).willReturn(AGENCY_NONE);
            stubFetchPending();
            // 엔드포인트 조회 예외 → findEndpoint() 내부 catch → null 반환
            given(idoJdbcTemplate.queryForObject(
                    contains("agency_endpoint_registry"),
                    any(RowMapper.class),
                    eq(AGENCY_NONE), eq("PROVISIONING")))
                .willThrow(new org.springframework.dao.EmptyResultDataAccessException(1));

            sut.relay();

            verifyNoInteractions(restTemplate, mtlsRestTemplate);
            verify(idoJdbcTemplate).update(contains("next_retry_at"), any(), any(), eq(ROW_ID));
        }

        @Test
        @DisplayName("B-13: API_KEY 자격증명 미등록(환경변수/DB 없음) → RETRY 예약")
        void b13_apiKeyCredentialMissing_retry() throws Exception {
            String agencyCode = "AGENCY_NO_CRED";
            String ref        = "secrets/agency/AGENCY_NO_CRED/api-key";
            given(mockResultSet.getString("agency_code")).willReturn(agencyCode);
            stubFetchPending();
            stubEndpoint(agencyCode, ENDPOINT_URL, "API_KEY", ref);
            // DB fallback도 빈 결과
            given(idoJdbcTemplate.queryForObject(
                    contains("agency_credential_config"), eq(String.class), eq(ref)))
                .willThrow(new org.springframework.dao.EmptyResultDataAccessException(1));

            sut.relay();

            verifyNoInteractions(restTemplate, mtlsRestTemplate);
            verify(idoJdbcTemplate).update(contains("next_retry_at"), any(), any(), eq(ROW_ID));
        }
    }

    // =========================================================================
    // 헬퍼 메서드
    // =========================================================================

    /**
     * fetchPending() 스텁 — RowCallbackHandler 콜백을 직접 실행해 rows에 1건 추가.
     *
     * <p>ProvisioningRelayJob.fetchPending()는 내부에서:
     * <pre>
     *   idoJdbcTemplate.query(sql, (ResultSet rs) -> { rows.add(new ProvisioningRow(...)); }, batchSize)
     * </pre>
     * 형태의 RowCallbackHandler를 사용. doAnswer로 콜백을 캡처 후 Mock ResultSet으로 직접 실행.
     */
    private void stubFetchPending() throws Exception {
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            handler.processRow(mockResultSet);
            return null;
        }).when(idoJdbcTemplate).query(contains("provisioning_outbox"),
                any(RowCallbackHandler.class), eq(50));
    }

    /**
     * agency_endpoint_registry 조회 스텁 — EndpointInfo private record 리플렉션 생성.
     */
    @SuppressWarnings("unchecked")
    private void stubEndpoint(String agencyCode, String url, String authType, String credRef) {
        given(idoJdbcTemplate.queryForObject(
                contains("agency_endpoint_registry"),
                any(RowMapper.class),
                eq(agencyCode), eq("PROVISIONING")))
            .willAnswer(inv -> createEndpointInfo(url, authType, credRef, agencyCode));
    }

    private Object createEndpointInfo(String url, String authType, String credRef, String agencyCode) {
        try {
            for (Class<?> inner : ProvisioningRelayJob.class.getDeclaredClasses()) {
                if (inner.getSimpleName().equals("EndpointInfo")) {
                    var ctor = inner.getDeclaredConstructors()[0];
                    ctor.setAccessible(true);
                    return ctor.newInstance(url, authType, credRef, agencyCode);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("EndpointInfo 생성 실패: " + e.getMessage(), e);
        }
        throw new IllegalStateException("EndpointInfo record를 찾을 수 없음");
    }
}

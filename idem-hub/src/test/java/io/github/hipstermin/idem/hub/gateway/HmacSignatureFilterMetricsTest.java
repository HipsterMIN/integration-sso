package io.github.hipstermin.idem.hub.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.hipstermin.idem.hub.config.FeatureFlags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Sprint β-1 (F4.7) — HmacSignatureFilter 메트릭 회귀 테스트
 *
 * <h3>검증 시나리오</h3>
 * <ol>
 *   <li>soft mode (F-26=false) + sig 헤더 부재 → {@code result="missing"} 카운터 +1, chain 진행</li>
 *   <li>soft mode + 유효 sig                → {@code result="valid"} 카운터 +1, chain 진행</li>
 *   <li>strict mode (F-26=true) + sig 부재  → {@code result="missing"} 카운터 +1, 401, chain 중단</li>
 *   <li>유효하지 않은 sig                    → {@code result="invalid_signature"} 카운터 +1, 401</li>
 *   <li>X-Agency-Code 부재                  → {@code result="missing_agency"} 카운터 +1, 401</li>
 *   <li>기관 키 미등록                       → {@code result="key_not_found"} 카운터 +1, 401</li>
 *   <li>대상 외 경로                         → 어떤 카운터도 증가시키지 않음 (pass-through)</li>
 * </ol>
 */
@Tag("unit")
@DisplayName("Sprint β-1: HmacSignatureFilter 메트릭 회귀 테스트 (F4.7)")
class HmacSignatureFilterMetricsTest {

    private static final String INBOUND_PATH      = "/api/v1/agency/gateway/inbound/event";
    private static final String AGENCY_CODE       = "AGENCY_TEST_001";
    private static final String IDEMPOTENCY_KEY   = "idem-key-001";
    private static final String SECRET            = "test-secret-256bit-key-do-not-use-in-prod";

    private SimpleMeterRegistry registry;
    private InboundHmacMetrics  metrics;
    private FeatureFlags        featureFlags;
    private AgencyHmacKeyStore  hmacKeyStore;
    private HmacSignatureFilter filter;

    @BeforeEach
    void setUp() {
        this.registry      = new SimpleMeterRegistry();
        this.metrics       = new InboundHmacMetrics(registry);
        this.featureFlags  = mock(FeatureFlags.class);
        this.hmacKeyStore  = mock(AgencyHmacKeyStore.class);
        this.filter        = new HmacSignatureFilter(featureFlags, hmacKeyStore, metrics);
    }

    // ────────────────────────────────────────────────────────────────────
    // soft mode (F-26=false)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("soft mode (F-26=false) 카운터")
    class SoftMode {

        @Test
        @DisplayName("sig 헤더 부재 → result=missing 카운터 +1, chain 진행 (통과)")
        void soft_missing_sig_passes_with_counter() throws Exception {
            when(featureFlags.isHmacSigRequired()).thenReturn(false);

            MockHttpServletRequest req = inboundReq();
            req.addHeader("X-Agency-Code", AGENCY_CODE);
            req.addHeader("X-Idempotency-Key", IDEMPOTENCY_KEY);
            // X-Internal-Sig 헤더 없음

            MockHttpServletResponse resp = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            ReflectionTestUtils.invokeMethod(filter, "doFilterInternal", req, resp, chain);

            verify(chain, times(1)).doFilter(any(), any());
            assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
            assertThat(counterCount(InboundHmacMetrics.RESULT_MISSING)).isEqualTo(1.0);
            assertThat(counterCount(InboundHmacMetrics.RESULT_VALID)).isZero();
        }

        @Test
        @DisplayName("유효 sig → result=valid 카운터 +1, chain 진행")
        void soft_valid_sig_increments_valid() throws Exception {
            when(featureFlags.isHmacSigRequired()).thenReturn(false);
            when(hmacKeyStore.findSecret(AGENCY_CODE)).thenReturn(SECRET);

            long epoch = Instant.now().getEpochSecond();
            String sig = hmacHex(AGENCY_CODE + ":" + IDEMPOTENCY_KEY + ":" + epoch, SECRET);

            MockHttpServletRequest req = inboundReq();
            req.addHeader("X-Agency-Code", AGENCY_CODE);
            req.addHeader("X-Idempotency-Key", IDEMPOTENCY_KEY);
            req.addHeader("X-Internal-Sig", sig);

            MockHttpServletResponse resp = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            ReflectionTestUtils.invokeMethod(filter, "doFilterInternal", req, resp, chain);

            verify(chain, times(1)).doFilter(any(), any());
            assertThat(counterCount(InboundHmacMetrics.RESULT_VALID)).isEqualTo(1.0);
            assertThat(counterCount(InboundHmacMetrics.RESULT_MISSING)).isZero();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // strict mode (F-26=true)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("strict mode (F-26=true) 카운터")
    class StrictMode {

        @Test
        @DisplayName("sig 헤더 부재 → result=missing 카운터 +1, 401, chain 중단")
        void strict_missing_sig_rejected_with_counter() throws Exception {
            when(featureFlags.isHmacSigRequired()).thenReturn(true);

            MockHttpServletRequest req = inboundReq();
            req.addHeader("X-Agency-Code", AGENCY_CODE);
            req.addHeader("X-Idempotency-Key", IDEMPOTENCY_KEY);
            // X-Internal-Sig 헤더 없음

            MockHttpServletResponse resp = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            ReflectionTestUtils.invokeMethod(filter, "doFilterInternal", req, resp, chain);

            verify(chain, never()).doFilter(any(), any());
            assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            assertThat(counterCount(InboundHmacMetrics.RESULT_MISSING)).isEqualTo(1.0);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 거부 사유별 카운터 분기
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("invalid sig → result=invalid_signature 카운터 +1, 401")
    void invalid_sig_increments_invalid_counter() throws Exception {
        when(featureFlags.isHmacSigRequired()).thenReturn(false);
        when(hmacKeyStore.findSecret(AGENCY_CODE)).thenReturn(SECRET);

        MockHttpServletRequest req = inboundReq();
        req.addHeader("X-Agency-Code", AGENCY_CODE);
        req.addHeader("X-Idempotency-Key", IDEMPOTENCY_KEY);
        req.addHeader("X-Internal-Sig", "deadbeef" + "0".repeat(56)); // 64 hex but wrong

        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        ReflectionTestUtils.invokeMethod(filter, "doFilterInternal", req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(counterCount(InboundHmacMetrics.RESULT_INVALID_SIGNATURE)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("X-Agency-Code 부재 → result=missing_agency 카운터 +1, 401")
    void missing_agency_code_increments_counter() throws Exception {
        when(featureFlags.isHmacSigRequired()).thenReturn(false);

        MockHttpServletRequest req = inboundReq();
        // X-Agency-Code 헤더 없음
        req.addHeader("X-Idempotency-Key", IDEMPOTENCY_KEY);
        req.addHeader("X-Internal-Sig", "anything");

        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        ReflectionTestUtils.invokeMethod(filter, "doFilterInternal", req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(counterCount(InboundHmacMetrics.RESULT_MISSING_AGENCY)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("기관 키 미등록 → result=key_not_found 카운터 +1, 401")
    void key_not_found_increments_counter() throws Exception {
        when(featureFlags.isHmacSigRequired()).thenReturn(false);
        when(hmacKeyStore.findSecret(AGENCY_CODE)).thenReturn(null);

        MockHttpServletRequest req = inboundReq();
        req.addHeader("X-Agency-Code", AGENCY_CODE);
        req.addHeader("X-Idempotency-Key", IDEMPOTENCY_KEY);
        req.addHeader("X-Internal-Sig", "anything");

        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        ReflectionTestUtils.invokeMethod(filter, "doFilterInternal", req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(counterCount(InboundHmacMetrics.RESULT_KEY_NOT_FOUND)).isEqualTo(1.0);
    }

    // ────────────────────────────────────────────────────────────────────
    // pass-through 경로
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("대상 외 경로 (GET /health 등) → 어떤 카운터도 증가 안 함")
    void non_inbound_path_records_no_counter() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod("GET");
        req.setRequestURI("/actuator/health");

        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        ReflectionTestUtils.invokeMethod(filter, "doFilterInternal", req, resp, chain);

        verify(chain, times(1)).doFilter(any(), any());
        // 모든 result 카운터가 0 이어야 함
        for (String r : new String[] {
                InboundHmacMetrics.RESULT_VALID,
                InboundHmacMetrics.RESULT_MISSING,
                InboundHmacMetrics.RESULT_INVALID_SIGNATURE,
                InboundHmacMetrics.RESULT_MISSING_AGENCY,
                InboundHmacMetrics.RESULT_KEY_NOT_FOUND,
                InboundHmacMetrics.RESULT_COMPUTE_ERROR }) {
            assertThat(counterCount(r))
                    .as("pass-through 경로는 result=" + r + " 카운터를 증가시키지 않아야 함")
                    .isZero();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────

    private MockHttpServletRequest inboundReq() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod("POST");
        req.setRequestURI(INBOUND_PATH);
        return req;
    }

    private double counterCount(String result) {
        return registry.counter(InboundHmacMetrics.METRIC_NAME,
                                InboundHmacMetrics.TAG_RESULT, result).count();
    }

    private static String hmacHex(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}

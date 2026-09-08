package io.github.hipstermin.idem.hub.fe.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Sprint β-2 (F4.8) — InternalCallerAuthInterceptor 회귀 테스트
 *
 * <h3>검증 시나리오</h3>
 * <ol>
 *   <li>두 헤더 모두 존재 + caller 등록 + key 일치 → preHandle=true,
 *       result=valid 카운터, Request Attribute 저장</li>
 *   <li>헤더 1개 누락 → 401, result=missing 카운터, preHandle=false</li>
 *   <li>등록되지 않은 caller → 401, result=caller_unknown 카운터</li>
 *   <li>등록된 caller 지만 key 불일치 → 401, result=invalid 카운터</li>
 *   <li>부팅 검증: callers 비어있음 + allow-empty-callers=false → IllegalStateException</li>
 *   <li>부팅 검증: callers 비어있음 + allow-empty-callers=true → 통과 (테스트/로컬)</li>
 *   <li>부팅 검증: callers 중 빈 값 + allow-empty-callers=false → IllegalStateException</li>
 * </ol>
 */
@Tag("unit")
@DisplayName("Sprint β-2: InternalCallerAuthInterceptor 회귀 테스트 (F4.8)")
class InternalCallerAuthInterceptorTest {

    private SimpleMeterRegistry registry;
    private InternalCallerAuthInterceptor.InternalCallersProperties props;
    private InternalCallerAuthInterceptor interceptor;

    private static final String CALLER_QSIGN = "q-sign";
    private static final String KEY_QSIGN    = "test-key-32bytes-aaaaaaaaaaaaaaaaaaaaa";

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        props    = new InternalCallerAuthInterceptor.InternalCallersProperties();
        Map<String, String> callers = new HashMap<>();
        callers.put(CALLER_QSIGN, KEY_QSIGN);
        props.setCallers(callers);
        props.setAllowEmptyCallers(false);
        interceptor = new InternalCallerAuthInterceptor(props, registry);
        // validateCallers() 는 @PostConstruct — 단위 테스트에서는 명시 호출
        interceptor.validateCallers();
    }

    // ────────────────────────────────────────────────────────────────────
    // preHandle — 성공/실패 분기
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("preHandle 카운터/응답 분기")
    class PreHandle {

        @Test
        @DisplayName("정상 — caller + 유효 key → preHandle=true, attribute 저장, result=valid")
        void valid_credentials_pass() throws Exception {
            MockHttpServletRequest req = postReq();
            req.addHeader(InternalCallerAuthInterceptor.HEADER_CALLER,  CALLER_QSIGN);
            req.addHeader(InternalCallerAuthInterceptor.HEADER_API_KEY, KEY_QSIGN);
            MockHttpServletResponse resp = new MockHttpServletResponse();

            boolean result = interceptor.preHandle(req, resp, new Object());

            assertThat(result).isTrue();
            assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
            assertThat(req.getAttribute(InternalCallerAuthInterceptor.ATTR_VALIDATED_CALLER))
                    .isEqualTo(CALLER_QSIGN);
            assertThat(counter(InternalCallerAuthInterceptor.RESULT_VALID)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("X-Internal-Api-Key 누락 → 401, result=missing")
        void missing_api_key_returns_401() throws Exception {
            MockHttpServletRequest req = postReq();
            req.addHeader(InternalCallerAuthInterceptor.HEADER_CALLER, CALLER_QSIGN);
            MockHttpServletResponse resp = new MockHttpServletResponse();

            boolean result = interceptor.preHandle(req, resp, new Object());

            assertThat(result).isFalse();
            assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            assertThat(resp.getContentAsString()).contains("MISSING_INTERNAL_CREDENTIALS");
            assertThat(counter(InternalCallerAuthInterceptor.RESULT_MISSING)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("X-Internal-Caller 누락 → 401, result=missing")
        void missing_caller_returns_401() throws Exception {
            MockHttpServletRequest req = postReq();
            req.addHeader(InternalCallerAuthInterceptor.HEADER_API_KEY, KEY_QSIGN);
            MockHttpServletResponse resp = new MockHttpServletResponse();

            boolean result = interceptor.preHandle(req, resp, new Object());

            assertThat(result).isFalse();
            assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            assertThat(counter(InternalCallerAuthInterceptor.RESULT_MISSING)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("등록되지 않은 caller → 401, result=caller_unknown")
        void unknown_caller_returns_401() throws Exception {
            MockHttpServletRequest req = postReq();
            req.addHeader(InternalCallerAuthInterceptor.HEADER_CALLER,  "evil-service");
            req.addHeader(InternalCallerAuthInterceptor.HEADER_API_KEY, "any-key");
            MockHttpServletResponse resp = new MockHttpServletResponse();

            boolean result = interceptor.preHandle(req, resp, new Object());

            assertThat(result).isFalse();
            assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            // 정보 노출 방지를 위해 에러 코드는 INVALID 와 동일
            assertThat(resp.getContentAsString()).contains("INVALID_INTERNAL_CREDENTIALS");
            assertThat(counter(InternalCallerAuthInterceptor.RESULT_CALLER_UNKNOWN)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("등록 caller + 잘못된 key → 401, result=invalid")
        void invalid_key_returns_401() throws Exception {
            MockHttpServletRequest req = postReq();
            req.addHeader(InternalCallerAuthInterceptor.HEADER_CALLER,  CALLER_QSIGN);
            req.addHeader(InternalCallerAuthInterceptor.HEADER_API_KEY, "wrong-key");
            MockHttpServletResponse resp = new MockHttpServletResponse();

            boolean result = interceptor.preHandle(req, resp, new Object());

            assertThat(result).isFalse();
            assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            assertThat(counter(InternalCallerAuthInterceptor.RESULT_INVALID)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("빈 문자열 헤더 → MISSING 으로 처리 (blank trim)")
        void blank_headers_treated_as_missing() throws Exception {
            MockHttpServletRequest req = postReq();
            req.addHeader(InternalCallerAuthInterceptor.HEADER_CALLER,  "   ");
            req.addHeader(InternalCallerAuthInterceptor.HEADER_API_KEY, "");
            MockHttpServletResponse resp = new MockHttpServletResponse();

            boolean result = interceptor.preHandle(req, resp, new Object());

            assertThat(result).isFalse();
            assertThat(counter(InternalCallerAuthInterceptor.RESULT_MISSING)).isEqualTo(1.0);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 부팅 검증 — @PostConstruct validateCallers()
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("부팅 검증 (validateCallers)")
    class StartupGuard {

        @Test
        @DisplayName("callers 비어있음 + allow-empty=false → IllegalStateException")
        void empty_callers_fail_fast() {
            InternalCallerAuthInterceptor.InternalCallersProperties p =
                    new InternalCallerAuthInterceptor.InternalCallersProperties();
            p.setCallers(new HashMap<>());
            p.setAllowEmptyCallers(false);

            InternalCallerAuthInterceptor i = new InternalCallerAuthInterceptor(
                    p, new SimpleMeterRegistry());

            assertThatThrownBy(i::validateCallers)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ido.internal.callers")
                    .hasMessageContaining("운영");
        }

        @Test
        @DisplayName("callers 비어있음 + allow-empty=true → 통과 (테스트/로컬)")
        void empty_callers_with_escape_hatch_pass() {
            InternalCallerAuthInterceptor.InternalCallersProperties p =
                    new InternalCallerAuthInterceptor.InternalCallersProperties();
            p.setCallers(new HashMap<>());
            p.setAllowEmptyCallers(true);

            InternalCallerAuthInterceptor i = new InternalCallerAuthInterceptor(
                    p, new SimpleMeterRegistry());

            assertThatCode(i::validateCallers).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("callers 중 빈 값 존재 + allow-empty=false → IllegalStateException")
        void blank_value_fail_fast() {
            InternalCallerAuthInterceptor.InternalCallersProperties p =
                    new InternalCallerAuthInterceptor.InternalCallersProperties();
            Map<String, String> m = new HashMap<>();
            m.put("q-sign", "");
            p.setCallers(m);
            p.setAllowEmptyCallers(false);

            InternalCallerAuthInterceptor i = new InternalCallerAuthInterceptor(
                    p, new SimpleMeterRegistry());

            assertThatThrownBy(i::validateCallers)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("q-sign")
                    .hasMessageContaining("빈 문자열");
        }

        @Test
        @DisplayName("정상 등록 → 통과")
        void valid_callers_pass() {
            InternalCallerAuthInterceptor.InternalCallersProperties p =
                    new InternalCallerAuthInterceptor.InternalCallersProperties();
            Map<String, String> m = new HashMap<>();
            m.put("q-sign", "valid-secret-32-bytes-padding-aaaaa");
            p.setCallers(m);
            p.setAllowEmptyCallers(false);

            InternalCallerAuthInterceptor i = new InternalCallerAuthInterceptor(
                    p, new SimpleMeterRegistry());

            assertThatCode(i::validateCallers).doesNotThrowAnyException();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────

    private MockHttpServletRequest postReq() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod("POST");
        req.setRequestURI("/api/v1/fe-session");
        return req;
    }

    private double counter(String result) {
        return registry.counter(InternalCallerAuthInterceptor.METRIC_NAME,
                                InternalCallerAuthInterceptor.TAG_RESULT, result).count();
    }
}

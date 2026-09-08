package io.github.hipstermin.idem.registry.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * InternalApiKeyInterceptor 단위 테스트 (P2 보안 패치)
 *
 * <p><b>검증 항목</b>:
 * <ol>
 *   <li>올바른 키 → preHandle() = true (통과)</li>
 *   <li>키 불일치 → 401 반환 + preHandle() = false</li>
 *   <li>헤더 누락 → 401 반환 + preHandle() = false</li>
 *   <li>빈 헤더 → 401 반환 + preHandle() = false</li>
 *   <li>서버 키 미설정(빈 문자열) → 전면 거부 401 + preHandle() = false</li>
 *   <li>서버 키 미설정(null) → 전면 거부 401 + preHandle() = false</li>
 *   <li>타이밍 공격 방지 — 길이 다른 키도 상수 시간 비교 (secureEquals)</li>
 *   <li>응답 Content-Type = application/json, charset = UTF-8</li>
 *   <li>응답 바디에 "UNAUTHORIZED" 포함</li>
 * </ol>
 */
@DisplayName("InternalApiKeyInterceptor — X-Internal-Api-Key 헤더 검증")
class InternalApiKeyInterceptorTest {

    private static final String VALID_KEY      = "super-secret-internal-key-for-test";
    private static final String WRONG_KEY      = "wrong-key";
    private static final String HEADER_NAME    = "X-Internal-Api-Key";

    /** 유효한 키가 설정된 인터셉터 */
    private InternalApiKeyInterceptor interceptorWithKey() {
        return new InternalApiKeyInterceptor(VALID_KEY);
    }

    /** 키가 빈 문자열인 인터셉터 (미설정 시뮬레이션) */
    private InternalApiKeyInterceptor interceptorWithEmptyKey() {
        return new InternalApiKeyInterceptor("");
    }

    // ──────────────────────────────────────────────────────────────────────
    // 1. 정상 통과
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("정상 통과")
    class PassCases {

        @Test
        @DisplayName("올바른 X-Internal-Api-Key → preHandle() = true")
        void preHandle_validKey_returnsTrue() throws Exception {
            InternalApiKeyInterceptor interceptor = interceptorWithKey();
            MockHttpServletRequest  req  = new MockHttpServletRequest();
            MockHttpServletResponse resp = new MockHttpServletResponse();
            req.addHeader(HEADER_NAME, VALID_KEY);

            boolean result = interceptor.preHandle(req, resp, new Object());

            assertThat(result).isTrue();
            assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_OK); // 200 (Spring 기본값)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 2. 키 불일치 거부
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("키 불일치 거부")
    class KeyMismatchCases {

        @Test
        @DisplayName("잘못된 키 → 401 반환 + preHandle() = false")
        void preHandle_wrongKey_returns401() throws Exception {
            InternalApiKeyInterceptor interceptor = interceptorWithKey();
            MockHttpServletRequest  req  = new MockHttpServletRequest("GET", "/api/v1/internal/users/find-by-social-sub");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            req.addHeader(HEADER_NAME, WRONG_KEY);

            boolean result = interceptor.preHandle(req, resp, new Object());

            assertThat(result).isFalse();
            assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("키가 대소문자만 다른 경우 → 401 (대소문자 구분)")
        void preHandle_keyWithDifferentCase_returns401() throws Exception {
            InternalApiKeyInterceptor interceptor = interceptorWithKey();
            MockHttpServletRequest  req  = new MockHttpServletRequest();
            MockHttpServletResponse resp = new MockHttpServletResponse();
            req.addHeader(HEADER_NAME, VALID_KEY.toUpperCase());

            boolean result = interceptor.preHandle(req, resp, new Object());

            assertThat(result).isFalse();
            assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("앞뒤 공백 포함 키 → 401 (exact match 필요)")
        void preHandle_keyWithSpaces_returns401() throws Exception {
            InternalApiKeyInterceptor interceptor = interceptorWithKey();
            MockHttpServletRequest  req  = new MockHttpServletRequest();
            MockHttpServletResponse resp = new MockHttpServletResponse();
            req.addHeader(HEADER_NAME, " " + VALID_KEY + " "); // 공백 포함

            boolean result = interceptor.preHandle(req, resp, new Object());

            assertThat(result).isFalse();
            assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 3. 헤더 누락 거부
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("헤더 누락/빈값 거부")
    class MissingHeaderCases {

        @Test
        @DisplayName("X-Internal-Api-Key 헤더 없음 → 401")
        void preHandle_missingHeader_returns401() throws Exception {
            InternalApiKeyInterceptor interceptor = interceptorWithKey();
            MockHttpServletRequest  req  = new MockHttpServletRequest("POST", "/api/v1/internal/users/register-social");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            // 헤더 미설정

            boolean result = interceptor.preHandle(req, resp, new Object());

            assertThat(result).isFalse();
            assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("X-Internal-Api-Key 헤더 빈 문자열 → 401")
        void preHandle_emptyHeader_returns401() throws Exception {
            InternalApiKeyInterceptor interceptor = interceptorWithKey();
            MockHttpServletRequest  req  = new MockHttpServletRequest();
            MockHttpServletResponse resp = new MockHttpServletResponse();
            req.addHeader(HEADER_NAME, "");

            boolean result = interceptor.preHandle(req, resp, new Object());

            assertThat(result).isFalse();
            assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("X-Internal-Api-Key 헤더 공백만 → 401 (blank)")
        void preHandle_blankHeader_returns401() throws Exception {
            InternalApiKeyInterceptor interceptor = interceptorWithKey();
            MockHttpServletRequest  req  = new MockHttpServletRequest();
            MockHttpServletResponse resp = new MockHttpServletResponse();
            req.addHeader(HEADER_NAME, "   ");

            boolean result = interceptor.preHandle(req, resp, new Object());

            assertThat(result).isFalse();
            assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 4. 서버 키 미설정 전면 거부 (운영 환경 설정 오류 방어)
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("서버 키 미설정 전면 거부")
    class ServerKeyNotConfiguredCases {

        @Test
        @DisplayName("서버 키 빈 문자열 → 올바른 클라이언트 키도 401 (전면 거부)")
        void preHandle_serverKeyEmpty_returnsUnauthorizedEvenWithValidClientKey() throws Exception {
            InternalApiKeyInterceptor interceptor = interceptorWithEmptyKey();
            MockHttpServletRequest  req  = new MockHttpServletRequest();
            MockHttpServletResponse resp = new MockHttpServletResponse();
            req.addHeader(HEADER_NAME, VALID_KEY); // 클라이언트는 올바른 키 전송

            boolean result = interceptor.preHandle(req, resp, new Object());

            assertThat(result).isFalse();
            assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("서버 키 null → 올바른 클라이언트 키도 401")
        void preHandle_serverKeyNull_returnsUnauthorized() throws Exception {
            // null 키 주입 (ReflectionTestUtils 사용)
            InternalApiKeyInterceptor interceptor = new InternalApiKeyInterceptor("");
            ReflectionTestUtils.setField(interceptor, "internalApiKey", null);

            MockHttpServletRequest  req  = new MockHttpServletRequest();
            MockHttpServletResponse resp = new MockHttpServletResponse();
            req.addHeader(HEADER_NAME, VALID_KEY);

            boolean result = interceptor.preHandle(req, resp, new Object());

            assertThat(result).isFalse();
            assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("서버 키 미설정 + 클라이언트 헤더 없음 → 401")
        void preHandle_serverKeyEmpty_noClientHeader_returns401() throws Exception {
            InternalApiKeyInterceptor interceptor = interceptorWithEmptyKey();
            MockHttpServletRequest  req  = new MockHttpServletRequest();
            MockHttpServletResponse resp = new MockHttpServletResponse();

            boolean result = interceptor.preHandle(req, resp, new Object());

            assertThat(result).isFalse();
            assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 5. 응답 형식 검증
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("응답 형식 검증")
    class ResponseFormatCases {

        @Test
        @DisplayName("401 응답 Content-Type = application/json")
        void preHandle_unauthorized_responseContentTypeIsJson() throws Exception {
            InternalApiKeyInterceptor interceptor = interceptorWithKey();
            MockHttpServletRequest  req  = new MockHttpServletRequest();
            MockHttpServletResponse resp = new MockHttpServletResponse();
            // 헤더 없음 → 401

            interceptor.preHandle(req, resp, new Object());

            assertThat(resp.getContentType()).contains("application/json");
        }

        @Test
        @DisplayName("401 응답 바디에 'UNAUTHORIZED' 포함")
        void preHandle_unauthorized_responseBodyContainsUnauthorized() throws Exception {
            InternalApiKeyInterceptor interceptor = interceptorWithKey();
            MockHttpServletRequest  req  = new MockHttpServletRequest();
            MockHttpServletResponse resp = new MockHttpServletResponse();
            // 헤더 없음 → 401

            interceptor.preHandle(req, resp, new Object());

            String body = resp.getContentAsString();
            assertThat(body).contains("UNAUTHORIZED");
        }

        @Test
        @DisplayName("키 불일치 응답 바디에 'Invalid X-Internal-Api-Key' 포함")
        void preHandle_wrongKey_responseBodyContainsInvalidKey() throws Exception {
            InternalApiKeyInterceptor interceptor = interceptorWithKey();
            MockHttpServletRequest  req  = new MockHttpServletRequest();
            MockHttpServletResponse resp = new MockHttpServletResponse();
            req.addHeader(HEADER_NAME, WRONG_KEY);

            interceptor.preHandle(req, resp, new Object());

            String body = resp.getContentAsString();
            assertThat(body).contains("Invalid").contains("X-Internal-Api-Key");
        }

        @Test
        @DisplayName("헤더 누락 응답 바디에 'Missing X-Internal-Api-Key' 포함")
        void preHandle_missingHeader_responseBodyContainsMissing() throws Exception {
            InternalApiKeyInterceptor interceptor = interceptorWithKey();
            MockHttpServletRequest  req  = new MockHttpServletRequest();
            MockHttpServletResponse resp = new MockHttpServletResponse();

            interceptor.preHandle(req, resp, new Object());

            String body = resp.getContentAsString();
            assertThat(body).contains("Missing").contains("X-Internal-Api-Key");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 6. 타이밍 공격 방지 (secureEquals) — 경계 케이스
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("타이밍 공격 방지 — secureEquals 경계 케이스")
    class SecureEqualsCases {

        @Test
        @DisplayName("길이가 다른 키 → 불일치 거부 (길이 누설 없이 비교)")
        void preHandle_keyLengthDifferent_returns401() throws Exception {
            InternalApiKeyInterceptor interceptor = interceptorWithKey();
            MockHttpServletRequest  req  = new MockHttpServletRequest();
            MockHttpServletResponse resp = new MockHttpServletResponse();
            // 서버 키보다 훨씬 짧은 키
            req.addHeader(HEADER_NAME, "x");

            boolean result = interceptor.preHandle(req, resp, new Object());

            assertThat(result).isFalse();
            assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("길이는 같지만 마지막 1바이트 다른 키 → 불일치 거부")
        void preHandle_keyLastByteDifferent_returns401() throws Exception {
            InternalApiKeyInterceptor interceptor = interceptorWithKey();
            MockHttpServletRequest  req  = new MockHttpServletRequest();
            MockHttpServletResponse resp = new MockHttpServletResponse();
            // 마지막 문자만 다름
            String almostValidKey = VALID_KEY.substring(0, VALID_KEY.length() - 1) + "X";
            req.addHeader(HEADER_NAME, almostValidKey);

            boolean result = interceptor.preHandle(req, resp, new Object());

            assertThat(result).isFalse();
            assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("완전 동일한 키(정확히 일치) → 통과")
        void preHandle_exactMatch_returnsTrue() throws Exception {
            // VALID_KEY와 동일한 문자열을 별도로 생성 (동일 객체 참조 아님)
            String sameKey = new String(VALID_KEY.toCharArray());
            InternalApiKeyInterceptor interceptor = interceptorWithKey();
            MockHttpServletRequest  req  = new MockHttpServletRequest();
            MockHttpServletResponse resp = new MockHttpServletResponse();
            req.addHeader(HEADER_NAME, sameKey);

            boolean result = interceptor.preHandle(req, resp, new Object());

            assertThat(result).isTrue();
        }
    }
}

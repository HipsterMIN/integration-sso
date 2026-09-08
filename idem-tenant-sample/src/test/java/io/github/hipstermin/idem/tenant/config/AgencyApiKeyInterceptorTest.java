package io.github.hipstermin.idem.tenant.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AgencyApiKeyInterceptor — X-Agency-Code / X-Agency-Key 헤더 검증 단위 테스트
 *
 * <p>설계서 §15.1 — 기관 API Key 인증 보안 요구사항:
 * <ul>
 *   <li>헤더 누락 시 401 반환</li>
 *   <li>유효하지 않은 API Key 시 401 반환</li>
 *   <li>rawApiKey 원문은 절대 로그에 기록하지 않음</li>
 *   <li>SHA-256 해시로 DB 조회</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgencyApiKeyInterceptor — API Key 인증 단위 테스트")
class AgencyApiKeyInterceptorTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private Object handler;

    private AgencyApiKeyInterceptor interceptor;
    private StringWriter responseWriter;

    @BeforeEach
    void setUp() throws Exception {
        interceptor = new AgencyApiKeyInterceptor(jdbcTemplate);
        ReflectionTestUtils.setField(interceptor, "myAgencyCode", "AGENCY_TEST_001");

        responseWriter = new StringWriter();
        // lenient: 일부 테스트(401 응답 없음)에서는 getWriter() 미사용
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 헤더 누락 검증
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("X-Agency-Code 헤더 누락 — 401 반환, 요청 차단")
    void testMissingAgencyCode_rejected() throws Exception {
        when(request.getHeader("X-Agency-Code")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/test");

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isFalse();
        verify(response).setStatus(401);
        assertThat(responseWriter.toString()).contains("MISSING_AGENCY_CODE");
    }

    @Test
    @DisplayName("X-Agency-Code 빈 문자열 — 401 반환")
    void testBlankAgencyCode_rejected() throws Exception {
        when(request.getHeader("X-Agency-Code")).thenReturn("   ");
        when(request.getRequestURI()).thenReturn("/api/v1/test");

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isFalse();
        verify(response).setStatus(401);
    }

    @Test
    @DisplayName("X-Agency-Key 헤더 누락 — 401 반환")
    void testMissingAgencyKey_rejected() throws Exception {
        when(request.getHeader("X-Agency-Code")).thenReturn("AGENCY_TEST_001");
        when(request.getHeader("X-Agency-Key")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/test");

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isFalse();
        verify(response).setStatus(401);
        assertThat(responseWriter.toString()).contains("MISSING_API_KEY");
    }

    // ─────────────────────────────────────────────────────────────────────
    // SHA-256 해시 검증
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("유효한 API Key — SHA-256 해시로 DB 조회, 통과")
    void testValidApiKey_allowed() throws Exception {
        when(request.getHeader("X-Agency-Code")).thenReturn("AGENCY_TEST_001");
        when(request.getHeader("X-Agency-Key")).thenReturn("stub-api-key-dev-001");

        // DB 조회 결과: 1건 (유효한 키)
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql.contains("agency_api_key")),
                eq(Integer.class),
                eq("AGENCY_TEST_001"),
                anyString()   // SHA-256 해시값
        )).thenReturn(1);

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isTrue();
        verify(request).setAttribute(eq("validatedAgencyCode"), eq("AGENCY_TEST_001"));
    }

    @Test
    @DisplayName("유효하지 않은 API Key — 401 INVALID_API_KEY 반환")
    void testInvalidApiKey_rejected() throws Exception {
        when(request.getHeader("X-Agency-Code")).thenReturn("AGENCY_TEST_001");
        when(request.getHeader("X-Agency-Key")).thenReturn("wrong-api-key");

        // DB 조회 결과: 0건 (잘못된 키)
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql.contains("agency_api_key")),
                eq(Integer.class),
                eq("AGENCY_TEST_001"),
                anyString()
        )).thenReturn(0);

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isFalse();
        verify(response).setStatus(401);
        assertThat(responseWriter.toString()).contains("INVALID_API_KEY");
    }

    @Test
    @DisplayName("API Key DB 조회 결과 null — 401 반환")
    void testNullCountFromDb_rejected() throws Exception {
        when(request.getHeader("X-Agency-Code")).thenReturn("AGENCY_TEST_001");
        when(request.getHeader("X-Agency-Key")).thenReturn("some-key");

        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql.contains("agency_api_key")),
                eq(Integer.class),
                eq("AGENCY_TEST_001"),
                anyString()
        )).thenReturn(null);

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isFalse();
        verify(response).setStatus(401);
    }

    @Test
    @DisplayName("DB 조회 예외 발생 — 500 반환")
    void testDbException_internalServerError() throws Exception {
        when(request.getHeader("X-Agency-Code")).thenReturn("AGENCY_TEST_001");
        when(request.getHeader("X-Agency-Key")).thenReturn("any-key");

        when(jdbcTemplate.queryForObject(
                anyString(), eq(Integer.class), eq("AGENCY_TEST_001"), anyString()
        )).thenThrow(new RuntimeException("DB connection failed"));

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isFalse();
        verify(response).setStatus(500);
        assertThat(responseWriter.toString()).contains("AUTH_CHECK_FAILED");
    }

    // ─────────────────────────────────────────────────────────────────────
    // SHA-256 해시 결정론 검증
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 API Key는 항상 같은 SHA-256 해시로 DB 조회")
    void testSameKeyAlwaysSameHash() throws Exception {
        when(request.getHeader("X-Agency-Code")).thenReturn("AGENCY_TEST_001");
        when(request.getHeader("X-Agency-Key")).thenReturn("fixed-api-key-value");
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString(), anyString()))
                .thenReturn(1);

        // 두 번 호출
        interceptor.preHandle(request, response, handler);
        interceptor.preHandle(request, response, handler);

        // 캡처된 해시값 비교
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).queryForObject(
                anyString(), eq(Integer.class), eq("AGENCY_TEST_001"), hashCaptor.capture()
        );

        String hash1 = hashCaptor.getAllValues().get(0);
        String hash2 = hashCaptor.getAllValues().get(1);

        assertThat(hash1).isEqualTo(hash2);                   // 결정론적
        assertThat(hash1).hasSize(64);                         // SHA-256 = 64자 HEX
        assertThat(hash1).matches("[0-9a-f]{64}");
        // rawApiKey가 해시에 노출되지 않음 확인
        assertThat(hash1).doesNotContain("fixed-api-key-value");
    }

    @Test
    @DisplayName("다른 API Key는 다른 SHA-256 해시 생성")
    void testDifferentKeysGenerateDifferentHashes() throws Exception {
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);

        // 첫 번째 키
        when(request.getHeader("X-Agency-Code")).thenReturn("AGENCY_TEST_001");
        when(request.getHeader("X-Agency-Key")).thenReturn("api-key-alpha");
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString(), anyString()))
                .thenReturn(1);

        interceptor.preHandle(request, response, handler);

        // 두 번째 키
        when(request.getHeader("X-Agency-Key")).thenReturn("api-key-beta");
        interceptor.preHandle(request, response, handler);

        verify(jdbcTemplate, times(2)).queryForObject(
                anyString(), eq(Integer.class), eq("AGENCY_TEST_001"), hashCaptor.capture()
        );

        String hash1 = hashCaptor.getAllValues().get(0);
        String hash2 = hashCaptor.getAllValues().get(1);

        assertThat(hash1).isNotEqualTo(hash2); // 서로 다른 해시
    }
}

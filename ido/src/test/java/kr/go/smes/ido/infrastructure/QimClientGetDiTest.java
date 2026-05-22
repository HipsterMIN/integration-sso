package kr.go.smes.ido.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * Sprint α-3 / F4.6 — QimClientImpl.getDi() 예외 구분 회귀 테스트
 *
 * <p><b>위협 모델</b>: 이전 구현은 모든 예외를 swallow하여 null을 반환 → Q-IM 장애가 영구 미매핑(GUEST)으로 둔갑.
 *
 * <p><b>검증 시나리오</b>:
 * <ul>
 *   <li>2xx + di 존재 → DI 반환 (정상)</li>
 *   <li>2xx + di null 또는 blank → null 반환 (정당 미매핑)</li>
 *   <li>404 NotFound → null 반환 (정당 미매핑)</li>
 *   <li>5xx → PlatformException(IDO_QIM_UNREACHABLE)</li>
 *   <li>네트워크 오류 (ResourceAccessException) → PlatformException(IDO_QIM_UNREACHABLE)</li>
 *   <li>예상 외 RuntimeException → PlatformException(IDO_QIM_UNREACHABLE)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("[F4.6] QimClientImpl.getDi() — 예외 구분 (미매핑 vs 일시 장애)")
class QimClientGetDiTest {

    @Mock RestTemplate restTemplate;

    QimClientImpl sut;

    private static final String BASE_URL       = "http://q-im-test:8082";
    private static final String API_KEY        = "test-key";
    private static final String QIM_USER_ID    = "qim-user-001";
    private static final String AGENCY_CODE    = "AGENCY_B";
    private static final String CORRELATION_ID = "corr-getDi-001";

    @BeforeEach
    void setUp() {
        sut = new QimClientImpl(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(sut, "qimBaseUrl",        BASE_URL);
        ReflectionTestUtils.setField(sut, "qimInternalApiKey", API_KEY);
    }

    // ════════════════════════════════════════════════════════════════════════
    // [F4.6] 정상 케이스
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("[F4.6] 정상 응답 — DI 반환 또는 null")
    class HappyPath {

        @Test
        @DisplayName("[F4.6] 200 OK + di 존재 → DI 문자열 반환")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void getDi_returnsDi_on200WithBody() {
            given(restTemplate.exchange(
                    contains("/di?agencyCode="),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class)
            )).willReturn(ResponseEntity.ok((Map) Map.of("di", "DI-AGENCY-B-12345")));

            String di = sut.getDi(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID);

            assertThat(di).isEqualTo("DI-AGENCY-B-12345");
        }

        @Test
        @DisplayName("[F4.6] 200 OK + di 필드 없음 → null 반환 (정당 미매핑)")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void getDi_returnsNull_on200WithoutDiField() {
            given(restTemplate.exchange(
                    contains("/di?agencyCode="),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class)
            )).willReturn(ResponseEntity.ok((Map) Map.of("other", "value")));

            String di = sut.getDi(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID);

            assertThat(di).isNull();
        }

        @Test
        @DisplayName("[F4.6] 200 OK + di blank → null 반환")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void getDi_returnsNull_on200WithBlankDi() {
            given(restTemplate.exchange(
                    contains("/di?agencyCode="),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class)
            )).willReturn(ResponseEntity.ok((Map) Map.of("di", "   ")));

            String di = sut.getDi(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID);

            assertThat(di).isNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // [F4.6] 404 NotFound — 정당한 미매핑
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("[F4.6] 404 NotFound → null (정당 미매핑, 예외 안 던짐)")
    class NotFoundIsNullNotException {

        @Test
        @DisplayName("[F4.6] HttpClientErrorException.NotFound → null 반환 (GUEST 정당)")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void getDi_returnsNull_on404() {
            given(restTemplate.exchange(
                    contains("/di?agencyCode="),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class)
            )).willThrow(HttpClientErrorException.create(
                    HttpStatus.NOT_FOUND, "Not Found", null, null, null));

            String di = sut.getDi(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID);

            assertThat(di)
                    .as("404는 영구 미매핑 — null 반환으로 GUEST 정당화. 예외 던지면 안 됨.")
                    .isNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // [F4.6] 핵심 가드 — 일시 장애는 PlatformException 으로 전파
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("[F4.6] 5xx / 네트워크 / 타임아웃 → PlatformException(IDO_QIM_UNREACHABLE)")
    class TransientFailureThrows {

        @Test
        @DisplayName("[F4.6] 5xx (Internal Server Error) → IDO_QIM_UNREACHABLE")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void getDi_throws_on5xx() {
            given(restTemplate.exchange(
                    contains("/di?agencyCode="),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class)
            )).willThrow(HttpServerErrorException.create(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error", null, null, null));

            assertThatThrownBy(() -> sut.getDi(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .satisfies(ex -> assertThat(((PlatformException) ex).getErrorCode())
                            .isEqualTo(PlatformErrorCode.IDO_QIM_UNREACHABLE));
        }

        @Test
        @DisplayName("[F4.6] ResourceAccessException (네트워크/타임아웃) → IDO_QIM_UNREACHABLE")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void getDi_throws_onResourceAccessException() {
            given(restTemplate.exchange(
                    contains("/di?agencyCode="),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class)
            )).willThrow(new ResourceAccessException("connection refused"));

            assertThatThrownBy(() -> sut.getDi(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .satisfies(ex -> assertThat(((PlatformException) ex).getErrorCode())
                            .isEqualTo(PlatformErrorCode.IDO_QIM_UNREACHABLE));
        }

        @Test
        @DisplayName("[F4.6] 503 Service Unavailable → IDO_QIM_UNREACHABLE")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void getDi_throws_on503() {
            given(restTemplate.exchange(
                    contains("/di?agencyCode="),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class)
            )).willThrow(HttpServerErrorException.create(
                    HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", null, null, null));

            assertThatThrownBy(() -> sut.getDi(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .satisfies(ex -> assertThat(((PlatformException) ex).getErrorCode())
                            .isEqualTo(PlatformErrorCode.IDO_QIM_UNREACHABLE));
        }

        @Test
        @DisplayName("[F4.6] 예상 외 RuntimeException → IDO_QIM_UNREACHABLE (안전 우선 거부)")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void getDi_throws_onUnexpectedException() {
            given(restTemplate.exchange(
                    contains("/di?agencyCode="),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class)
            )).willThrow(new IllegalStateException("NPE-like unexpected failure"));

            assertThatThrownBy(() -> sut.getDi(QIM_USER_ID, AGENCY_CODE, CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .satisfies(ex -> assertThat(((PlatformException) ex).getErrorCode())
                            .as("예상 외 예외도 안전 우선 거부 — GUEST swallow 금지")
                            .isEqualTo(PlatformErrorCode.IDO_QIM_UNREACHABLE));
        }
    }
}

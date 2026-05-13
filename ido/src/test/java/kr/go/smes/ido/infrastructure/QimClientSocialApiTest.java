package kr.go.smes.ido.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.ido.auth.dto.im.QimMemberInfo;
import kr.go.smes.ido.auth.dto.im.QimRegisterResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * QimClientImpl 소셜 API 단위 테스트 (SSO — v4.0 추가 메서드)
 *
 * <p>검증 대상:
 * <ul>
 *   <li>{@code findBySocialSub()} — Keycloak sub 기반 소셜 계정 조회</li>
 *   <li>{@code registerSocialUser()} — 신규 소셜 사용자 Q-IM 등록</li>
 * </ul>
 *
 * <p><b>검증 항목</b>:
 * <ol>
 *   <li>findBySocialSub 성공 (200 OK) → Optional.of(QimMemberInfo) 반환</li>
 *   <li>findBySocialSub 404 → Optional.empty() 반환 (정상 케이스)</li>
 *   <li>findBySocialSub 네트워크 오류 → PlatformException 발생</li>
 *   <li>findBySocialSub 요청에 X-Internal-Api-Key 헤더 포함</li>
 *   <li>findBySocialSub 요청에 X-Correlation-Id 헤더 포함</li>
 *   <li>findBySocialSub API 키 미설정 → 헤더 없이 요청 (경고 로그만)</li>
 *   <li>registerSocialUser 성공 (200 OK) → QimRegisterResponse 반환</li>
 *   <li>registerSocialUser 서버 오류(5xx) → PlatformException</li>
 *   <li>registerSocialUser 네트워크 오류 → PlatformException</li>
 *   <li>registerSocialUser 요청 바디에 sub/providerCode/identifierHash 포함</li>
 *   <li>registerSocialUser 응답 null → PlatformException</li>
 * </ol>
 */
@DisplayName("QimClientImpl — 소셜 API (findBySocialSub / registerSocialUser)")
@ExtendWith(MockitoExtension.class)
class QimClientSocialApiTest {

    @Mock private RestTemplate restTemplate;

    private QimClientImpl sut;

    // ── 픽스처 ──────────────────────────────────────────────────────────
    private static final String BASE_URL         = "http://q-im-test:8082";
    private static final String INTERNAL_API_KEY = "test-internal-api-key";
    private static final String CORRELATION_ID   = "test-corr-social-001";
    private static final String SUB              = "kakao-sub-user-12345";
    private static final String PROVIDER_CODE    = "KAKAO_OIDC";
    private static final String IDENTIFIER_HASH  = "deadbeef1234567890abcdef";
    private static final String QIM_USER_ID      = "qim-user-social-abc";

    @BeforeEach
    void setUp() {
        sut = new QimClientImpl(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(sut, "qimBaseUrl",         BASE_URL);
        ReflectionTestUtils.setField(sut, "qimInternalApiKey",  INTERNAL_API_KEY);
    }

    // ══════════════════════════════════════════════════════════════════════
    // findBySocialSub()
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("findBySocialSub()")
    class FindBySocialSubTests {

        @Test
        @DisplayName("200 OK + 바디 있음 → Optional.of(QimMemberInfo) 반환")
        void findBySocialSub_200WithBody_returnsOptionalOf() {
            // Given
            QimMemberInfo info = QimMemberInfo.builder()
                    .qimUserId(QIM_USER_ID).status("ACTIVE").build();
            given(restTemplate.exchange(
                    contains("/find-by-social-sub"),
                    eq(HttpMethod.POST),
                    any(HttpEntity.class),
                    eq(QimMemberInfo.class)
            )).willReturn(ResponseEntity.ok(info));

            // When
            Optional<QimMemberInfo> result = sut.findBySocialSub(SUB, PROVIDER_CODE, CORRELATION_ID);

            // Then
            assertThat(result).isPresent();
            assertThat(result.get().getQimUserId()).isEqualTo(QIM_USER_ID);
        }

        @Test
        @DisplayName("404 NotFound → Optional.empty() 반환 (정상 케이스)")
        void findBySocialSub_404_returnsOptionalEmpty() {
            // Given
            given(restTemplate.exchange(
                    contains("/find-by-social-sub"),
                    eq(HttpMethod.POST),
                    any(HttpEntity.class),
                    eq(QimMemberInfo.class)
            )).willThrow(HttpClientErrorException.create(
                    HttpStatus.NOT_FOUND, "Not Found", null, null, null));

            // When
            Optional<QimMemberInfo> result = sut.findBySocialSub(SUB, PROVIDER_CODE, CORRELATION_ID);

            // Then: 예외 전파 없이 empty 반환
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("네트워크 오류 → PlatformException 발생")
        void findBySocialSub_networkError_throwsPlatformException() {
            // Given
            given(restTemplate.exchange(
                    contains("/find-by-social-sub"),
                    eq(HttpMethod.POST),
                    any(HttpEntity.class),
                    eq(QimMemberInfo.class)
            )).willThrow(new RestClientException("Connection refused"));

            // When / Then
            assertThatThrownBy(() -> sut.findBySocialSub(SUB, PROVIDER_CODE, CORRELATION_ID))
                    .isInstanceOf(PlatformException.class);
        }

        @Test
        @DisplayName("요청 헤더에 X-Internal-Api-Key 포함됨")
        @SuppressWarnings("unchecked")
        void findBySocialSub_requestIncludesInternalApiKeyHeader() {
            // Given
            ArgumentCaptor<HttpEntity<Map<String, Object>>> captor =
                    ArgumentCaptor.forClass(HttpEntity.class);
            given(restTemplate.exchange(
                    contains("/find-by-social-sub"),
                    eq(HttpMethod.POST),
                    captor.capture(),
                    eq(QimMemberInfo.class)
            )).willReturn(ResponseEntity.ok(
                    QimMemberInfo.builder().qimUserId(QIM_USER_ID).build()
            ));

            // When
            sut.findBySocialSub(SUB, PROVIDER_CODE, CORRELATION_ID);

            // Then
            HttpEntity<Map<String, Object>> captured = captor.getValue();
            assertThat(captured.getHeaders().getFirst("X-Internal-Api-Key"))
                    .isEqualTo(INTERNAL_API_KEY);
        }

        @Test
        @DisplayName("요청 헤더에 X-Correlation-Id 포함됨")
        @SuppressWarnings("unchecked")
        void findBySocialSub_requestIncludesCorrelationIdHeader() {
            // Given
            ArgumentCaptor<HttpEntity<Map<String, Object>>> captor =
                    ArgumentCaptor.forClass(HttpEntity.class);
            given(restTemplate.exchange(
                    contains("/find-by-social-sub"),
                    eq(HttpMethod.POST),
                    captor.capture(),
                    eq(QimMemberInfo.class)
            )).willReturn(ResponseEntity.ok(
                    QimMemberInfo.builder().qimUserId(QIM_USER_ID).build()
            ));

            // When
            sut.findBySocialSub(SUB, PROVIDER_CODE, CORRELATION_ID);

            // Then
            HttpEntity<Map<String, Object>> captured = captor.getValue();
            assertThat(captured.getHeaders().getFirst("X-Correlation-Id"))
                    .isEqualTo(CORRELATION_ID);
        }

        @Test
        @DisplayName("API 키 미설정 시 X-Internal-Api-Key 헤더 없이 요청 (경고 로그만)")
        @SuppressWarnings("unchecked")
        void findBySocialSub_noApiKey_requestWithoutApiKeyHeader() {
            // Given: API 키 미설정
            ReflectionTestUtils.setField(sut, "qimInternalApiKey", "");
            ArgumentCaptor<HttpEntity<Map<String, Object>>> captor =
                    ArgumentCaptor.forClass(HttpEntity.class);
            given(restTemplate.exchange(
                    contains("/find-by-social-sub"),
                    eq(HttpMethod.POST),
                    captor.capture(),
                    eq(QimMemberInfo.class)
            )).willReturn(ResponseEntity.ok(
                    QimMemberInfo.builder().qimUserId(QIM_USER_ID).build()
            ));

            // When
            sut.findBySocialSub(SUB, PROVIDER_CODE, CORRELATION_ID);

            // Then: X-Internal-Api-Key 헤더 없음
            HttpEntity<Map<String, Object>> captured = captor.getValue();
            assertThat(captured.getHeaders().containsKey("X-Internal-Api-Key")).isFalse();
        }

        @Test
        @DisplayName("요청 URL에 /find-by-social-sub 포함")
        void findBySocialSub_callsCorrectEndpoint() {
            // Given: 정확한 URL로 stub 등록 (eq() 사용)
            String expectedUrl = BASE_URL + "/api/v1/internal/users/find-by-social-sub";
            given(restTemplate.exchange(
                    eq(expectedUrl),
                    eq(HttpMethod.POST),
                    any(HttpEntity.class),
                    eq(QimMemberInfo.class)
            )).willReturn(ResponseEntity.ok(
                    QimMemberInfo.builder().qimUserId(QIM_USER_ID).build()
            ));

            // When
            Optional<QimMemberInfo> result = sut.findBySocialSub(SUB, PROVIDER_CODE, CORRELATION_ID);

            // Then: 정확한 URL 호출 확인
            assertThat(result).isPresent();
            verify(restTemplate).exchange(
                    eq(expectedUrl),
                    eq(HttpMethod.POST),
                    any(HttpEntity.class),
                    eq(QimMemberInfo.class)
            );
        }

        @Test
        @DisplayName("200 OK + 바디 null → Optional.empty() 반환")
        void findBySocialSub_200WithNullBody_returnsOptionalEmpty() {
            // Given
            given(restTemplate.exchange(
                    contains("/find-by-social-sub"),
                    eq(HttpMethod.POST),
                    any(HttpEntity.class),
                    eq(QimMemberInfo.class)
            )).willReturn(ResponseEntity.ok(null));

            // When
            Optional<QimMemberInfo> result = sut.findBySocialSub(SUB, PROVIDER_CODE, CORRELATION_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // registerSocialUser()
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("registerSocialUser()")
    class RegisterSocialUserTests {

        @Test
        @DisplayName("200 OK → QimRegisterResponse 반환 (isNew=true)")
        void registerSocialUser_200_returnsResponse() {
            // Given
            QimRegisterResponse resp = QimRegisterResponse.builder()
                    .qimUserId(QIM_USER_ID).status("ACTIVE").isNew(true).build();
            given(restTemplate.exchange(
                    contains("/register-social"),
                    eq(HttpMethod.POST),
                    any(HttpEntity.class),
                    eq(QimRegisterResponse.class)
            )).willReturn(ResponseEntity.ok(resp));

            // When
            QimRegisterResponse result = sut.registerSocialUser(
                    SUB, PROVIDER_CODE, IDENTIFIER_HASH, CORRELATION_ID);

            // Then
            assertThat(result.getQimUserId()).isEqualTo(QIM_USER_ID);
            assertThat(result.getIsNew()).isTrue();
        }

        @Test
        @DisplayName("서버 오류(5xx) — 비정상 상태코드 → PlatformException")
        void registerSocialUser_5xx_throwsPlatformException() {
            // Given: 500 응답 (응답 바디 없음)
            given(restTemplate.exchange(
                    contains("/register-social"),
                    eq(HttpMethod.POST),
                    any(HttpEntity.class),
                    eq(QimRegisterResponse.class)
            )).willReturn(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());

            // When / Then
            assertThatThrownBy(() -> sut.registerSocialUser(
                    SUB, PROVIDER_CODE, IDENTIFIER_HASH, CORRELATION_ID))
                    .isInstanceOf(PlatformException.class);
        }

        @Test
        @DisplayName("응답 바디 null → PlatformException")
        void registerSocialUser_nullBody_throwsPlatformException() {
            // Given
            given(restTemplate.exchange(
                    contains("/register-social"),
                    eq(HttpMethod.POST),
                    any(HttpEntity.class),
                    eq(QimRegisterResponse.class)
            )).willReturn(ResponseEntity.ok(null));

            // When / Then
            assertThatThrownBy(() -> sut.registerSocialUser(
                    SUB, PROVIDER_CODE, IDENTIFIER_HASH, CORRELATION_ID))
                    .isInstanceOf(PlatformException.class);
        }

        @Test
        @DisplayName("네트워크 오류 → PlatformException")
        void registerSocialUser_networkError_throwsPlatformException() {
            // Given
            given(restTemplate.exchange(
                    contains("/register-social"),
                    eq(HttpMethod.POST),
                    any(HttpEntity.class),
                    eq(QimRegisterResponse.class)
            )).willThrow(new RestClientException("Timeout"));

            // When / Then
            assertThatThrownBy(() -> sut.registerSocialUser(
                    SUB, PROVIDER_CODE, IDENTIFIER_HASH, CORRELATION_ID))
                    .isInstanceOf(PlatformException.class);
        }

        @Test
        @DisplayName("요청 바디에 sub / providerCode / identifierHash 포함")
        @SuppressWarnings("unchecked")
        void registerSocialUser_requestBodyContainsRequiredFields() {
            // Given
            ArgumentCaptor<HttpEntity<Map<String, Object>>> captor =
                    ArgumentCaptor.forClass(HttpEntity.class);
            given(restTemplate.exchange(
                    contains("/register-social"),
                    eq(HttpMethod.POST),
                    captor.capture(),
                    eq(QimRegisterResponse.class)
            )).willReturn(ResponseEntity.ok(
                    QimRegisterResponse.builder()
                            .qimUserId(QIM_USER_ID).isNew(true).build()
            ));

            // When
            sut.registerSocialUser(SUB, PROVIDER_CODE, IDENTIFIER_HASH, CORRELATION_ID);

            // Then
            Map<String, Object> body = captor.getValue().getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("sub")).isEqualTo(SUB);
            assertThat(body.get("providerCode")).isEqualTo(PROVIDER_CODE);
            assertThat(body.get("identifierHash")).isEqualTo(IDENTIFIER_HASH);
        }

        @Test
        @DisplayName("요청 헤더에 X-Internal-Api-Key + X-Correlation-Id 포함")
        @SuppressWarnings("unchecked")
        void registerSocialUser_requestIncludesRequiredHeaders() {
            // Given
            ArgumentCaptor<HttpEntity<Map<String, Object>>> captor =
                    ArgumentCaptor.forClass(HttpEntity.class);
            given(restTemplate.exchange(
                    contains("/register-social"),
                    eq(HttpMethod.POST),
                    captor.capture(),
                    eq(QimRegisterResponse.class)
            )).willReturn(ResponseEntity.ok(
                    QimRegisterResponse.builder().qimUserId(QIM_USER_ID).isNew(true).build()
            ));

            // When
            sut.registerSocialUser(SUB, PROVIDER_CODE, IDENTIFIER_HASH, CORRELATION_ID);

            // Then
            var headers = captor.getValue().getHeaders();
            assertThat(headers.getFirst("X-Internal-Api-Key")).isEqualTo(INTERNAL_API_KEY);
            assertThat(headers.getFirst("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
        }

        @Test
        @DisplayName("요청 URL에 /register-social 포함")
        void registerSocialUser_callsCorrectEndpoint() {
            // Given
            given(restTemplate.exchange(
                    eq(BASE_URL + "/api/v1/internal/users/register-social"),
                    eq(HttpMethod.POST),
                    any(HttpEntity.class),
                    eq(QimRegisterResponse.class)
            )).willReturn(ResponseEntity.ok(
                    QimRegisterResponse.builder().qimUserId(QIM_USER_ID).isNew(true).build()
            ));

            // When
            QimRegisterResponse result =
                    sut.registerSocialUser(SUB, PROVIDER_CODE, IDENTIFIER_HASH, CORRELATION_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("isNew=false (기존 사용자 재확인) 응답도 정상 반환")
        void registerSocialUser_isNewFalse_returnsResponse() {
            // Given: 동시 등록 등으로 isNew=false 응답
            QimRegisterResponse resp = QimRegisterResponse.builder()
                    .qimUserId(QIM_USER_ID).isNew(false).status("ACTIVE").build();
            given(restTemplate.exchange(
                    contains("/register-social"),
                    eq(HttpMethod.POST),
                    any(HttpEntity.class),
                    eq(QimRegisterResponse.class)
            )).willReturn(ResponseEntity.ok(resp));

            // When
            QimRegisterResponse result = sut.registerSocialUser(
                    SUB, PROVIDER_CODE, IDENTIFIER_HASH, CORRELATION_ID);

            // Then
            assertThat(result.getIsNew()).isFalse();
            assertThat(result.getQimUserId()).isEqualTo(QIM_USER_ID);
        }
    }
}

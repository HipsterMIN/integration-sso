package io.github.hipstermin.idem.hub.broker.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.broker.BrokerAuditLogService;
import io.github.hipstermin.idem.hub.broker.state.IdoOidcStateStore;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import io.github.hipstermin.idem.hub.infrastructure.QimMemberInfo;
import io.github.hipstermin.idem.hub.infrastructure.QimRegisterResponse;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/**
 * KeycloakOidcService.resolveQimUserIdFromSub() 단위 테스트 (SSO 핵심 메서드)
 *
 * <p>{@code resolveQimUserIdFromSub()}는 private 메서드이므로,
 * {@link ReflectionTestUtils#invokeMethod}를 통해 직접 호출한다.
 *
 * <p><b>검증 항목</b>:
 * <ol>
 *   <li>기존 소셜 사용자 — Q-IM findBySocialSub() 성공 → 실제 qimUserId 반환</li>
 *   <li>신규 소셜 사용자 — findBySocialSub() empty → registerSocialUser() 호출 → 신규 qimUserId 반환</li>
 *   <li>Q-IM 통신 장애 — 예외 발생 → identifierHash 폴백 반환 (인증 플로우 중단 없음)</li>
 *   <li>등록 API 실패 — registerSocialUser() 예외 → identifierHash 폴백</li>
 *   <li>기존 사용자 확인 시 registerSocialUser() 미호출 (불필요한 API 호출 없음)</li>
 *   <li>신규 등록 성공 시 isNew=true 확인 (신규 등록 케이스)</li>
 *   <li>identifierHash 폴백 시 findBySocialSub() 1회만 호출</li>
 * </ol>
 */
@DisplayName("KeycloakOidcService.resolveQimUserIdFromSub() — SSO 사용자 식별 단위 테스트")
@ExtendWith(MockitoExtension.class)
class KeycloakOidcServiceResolveQimUserIdTest {

    // ── 목(Mock) 의존성 ───────────────────────────────────────────────────
    @Mock private IdoOidcStateStore          stateStore;
    @Mock private KeycloakJwksVerifier       jwksVerifier;
    @Mock private KeycloakProperties         keycloakProperties;
    @Mock private FeSessionService           feSessionService;
    @Mock private QimClient                  qimClient;
    @Mock private RestTemplate               restTemplate;
    @Mock @SuppressWarnings("rawtypes")
          private KafkaTemplate              kafkaTemplate;
    @Mock private JdbcTemplate               jdbcTemplate;
    @Mock private BrokerAuditLogService      brokerAuditLogService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 테스트 대상 */
    private KeycloakOidcService service;

    // ── 픽스처 ──────────────────────────────────────────────────────────
    private static final String SUB              = "keycloak-sub-abc123";
    private static final String IDENTIFIER_HASH  =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"; // SHA-256("test") 예시
    private static final String PROVIDER_CODE    = "KAKAO_OIDC";
    private static final String CORRELATION_ID   = "test-corr-id-001";
    private static final String EXISTING_QIM_ID  = "qim-user-existing-001";
    private static final String NEW_QIM_ID       = "qim-user-new-002";

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        service = new KeycloakOidcService(
                stateStore, jwksVerifier, keycloakProperties, feSessionService,
                qimClient, restTemplate, kafkaTemplate, jdbcTemplate,
                objectMapper, brokerAuditLogService
        );
    }

    // ──────────────────────────────────────────────────────────────────────
    // 1. 기존 소셜 사용자 (Happy Path A)
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("기존 소셜 사용자 — findBySocialSub() 성공")
    class ExistingUserCases {

        @Test
        @DisplayName("Q-IM 기존 사용자 → EXISTING_QIM_ID 반환")
        void resolve_existingUser_returnsExistingQimUserId() {
            // Given
            QimMemberInfo existingUser = QimMemberInfo.builder()
                    .qimUserId(EXISTING_QIM_ID)
                    .status("ACTIVE")
                    .build();
            given(qimClient.findBySocialSub(SUB, PROVIDER_CODE, CORRELATION_ID))
                    .willReturn(Optional.of(existingUser));

            // When
            String result = invokeResolve(SUB, IDENTIFIER_HASH, PROVIDER_CODE, CORRELATION_ID);

            // Then
            assertThat(result).isEqualTo(EXISTING_QIM_ID);
        }

        @Test
        @DisplayName("기존 사용자 → registerSocialUser() 미호출")
        void resolve_existingUser_doesNotCallRegister() {
            // Given
            given(qimClient.findBySocialSub(SUB, PROVIDER_CODE, CORRELATION_ID))
                    .willReturn(Optional.of(
                            QimMemberInfo.builder().qimUserId(EXISTING_QIM_ID).build()
                    ));

            // When
            invokeResolve(SUB, IDENTIFIER_HASH, PROVIDER_CODE, CORRELATION_ID);

            // Then: register 절대 호출 안 됨
            verify(qimClient, never())
                    .registerSocialUser(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("기존 사용자 — SUSPENDED 상태도 qimUserId 그대로 반환 (상태 검사는 PolicyEngine 몫)")
        void resolve_existingSuspendedUser_returnsQimUserId() {
            // Given
            given(qimClient.findBySocialSub(SUB, PROVIDER_CODE, CORRELATION_ID))
                    .willReturn(Optional.of(
                            QimMemberInfo.builder()
                                    .qimUserId(EXISTING_QIM_ID)
                                    .status("SUSPENDED")
                                    .build()
                    ));

            // When
            String result = invokeResolve(SUB, IDENTIFIER_HASH, PROVIDER_CODE, CORRELATION_ID);

            // Then: resolveQimUserIdFromSub는 상태를 검사하지 않고 ID만 반환
            assertThat(result).isEqualTo(EXISTING_QIM_ID);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 2. 신규 소셜 사용자 (Happy Path B)
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("신규 소셜 사용자 — findBySocialSub() empty → registerSocialUser() 호출")
    class NewUserCases {

        @Test
        @DisplayName("Q-IM 신규 사용자 → registerSocialUser() 호출 → NEW_QIM_ID 반환")
        void resolve_newUser_callsRegisterAndReturnsNewQimUserId() {
            // Given: 기존 사용자 없음
            given(qimClient.findBySocialSub(SUB, PROVIDER_CODE, CORRELATION_ID))
                    .willReturn(Optional.empty());

            QimRegisterResponse registered = QimRegisterResponse.builder()
                    .qimUserId(NEW_QIM_ID)
                    .status("ACTIVE")
                    .isNew(true)
                    .build();
            given(qimClient.registerSocialUser(SUB, PROVIDER_CODE, IDENTIFIER_HASH, CORRELATION_ID))
                    .willReturn(registered);

            // When
            String result = invokeResolve(SUB, IDENTIFIER_HASH, PROVIDER_CODE, CORRELATION_ID);

            // Then
            assertThat(result).isEqualTo(NEW_QIM_ID);
        }

        @Test
        @DisplayName("신규 등록 시 registerSocialUser()에 올바른 인자 전달 (sub, providerCode, identifierHash)")
        void resolve_newUser_passesCorrectArgsToRegister() {
            // Given
            given(qimClient.findBySocialSub(SUB, PROVIDER_CODE, CORRELATION_ID))
                    .willReturn(Optional.empty());
            given(qimClient.registerSocialUser(SUB, PROVIDER_CODE, IDENTIFIER_HASH, CORRELATION_ID))
                    .willReturn(QimRegisterResponse.builder()
                            .qimUserId(NEW_QIM_ID).isNew(true).build());

            // When
            invokeResolve(SUB, IDENTIFIER_HASH, PROVIDER_CODE, CORRELATION_ID);

            // Then: 정확한 인자로 호출됐는지 검증
            verify(qimClient).registerSocialUser(
                    eq(SUB),
                    eq(PROVIDER_CODE),
                    eq(IDENTIFIER_HASH),
                    eq(CORRELATION_ID)
            );
        }

        @Test
        @DisplayName("isNew=false (기존 사용자 재확인)도 registerSocialUser() 응답 qimUserId 반환")
        void resolve_registerReturnsIsNewFalse_stillReturnsQimUserId() {
            // Given: find에서 empty, register에서 isNew=false (race condition 등)
            given(qimClient.findBySocialSub(SUB, PROVIDER_CODE, CORRELATION_ID))
                    .willReturn(Optional.empty());
            given(qimClient.registerSocialUser(anyString(), anyString(), anyString(), anyString()))
                    .willReturn(QimRegisterResponse.builder()
                            .qimUserId(EXISTING_QIM_ID).isNew(false).build());

            // When
            String result = invokeResolve(SUB, IDENTIFIER_HASH, PROVIDER_CODE, CORRELATION_ID);

            // Then
            assertThat(result).isEqualTo(EXISTING_QIM_ID);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 3. Q-IM 통신 장애 → identifierHash 폴백 (SSO 기능 저하, 플로우 중단 없음)
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Q-IM 통신 장애 — identifierHash 폴백")
    class FallbackCases {

        @Test
        @DisplayName("findBySocialSub() 예외 → PlatformException 아닌 identifierHash 폴백 반환")
        void resolve_findSocialSubThrows_returnsIdentifierHashFallback() {
            // Given: Q-IM 통신 장애
            given(qimClient.findBySocialSub(anyString(), anyString(), anyString()))
                    .willThrow(new RuntimeException("Q-IM 연결 시간 초과"));

            // When: 예외가 외부로 전파되지 않아야 함
            String result = invokeResolve(SUB, IDENTIFIER_HASH, PROVIDER_CODE, CORRELATION_ID);

            // Then: identifierHash 폴백
            assertThat(result).isEqualTo(IDENTIFIER_HASH);
        }

        @Test
        @DisplayName("registerSocialUser() 예외 → identifierHash 폴백")
        void resolve_registerSocialUserThrows_returnsIdentifierHashFallback() {
            // Given: 조회는 empty, 등록 시 장애
            given(qimClient.findBySocialSub(anyString(), anyString(), anyString()))
                    .willReturn(Optional.empty());
            given(qimClient.registerSocialUser(anyString(), anyString(), anyString(), anyString()))
                    .willThrow(new PlatformException(
                            io.github.hipstermin.idem.common.error.PlatformErrorCode.IDO_QIM_UNREACHABLE,
                            CORRELATION_ID
                    ));

            // When
            String result = invokeResolve(SUB, IDENTIFIER_HASH, PROVIDER_CODE, CORRELATION_ID);

            // Then: identifierHash 폴백 (인증 플로우 계속)
            assertThat(result).isEqualTo(IDENTIFIER_HASH);
        }

        @Test
        @DisplayName("장애 시 findBySocialSub() 정확히 1회만 호출")
        void resolve_onFailure_findCalledOnce() {
            // Given
            given(qimClient.findBySocialSub(anyString(), anyString(), anyString()))
                    .willThrow(new RuntimeException("네트워크 오류"));

            // When
            invokeResolve(SUB, IDENTIFIER_HASH, PROVIDER_CODE, CORRELATION_ID);

            // Then
            verify(qimClient, times(1))
                    .findBySocialSub(SUB, PROVIDER_CODE, CORRELATION_ID);
        }

        @Test
        @DisplayName("장애 시 폴백 값은 identifierHash 그대로 (변조 없음)")
        void resolve_fallback_valueIsExactIdentifierHash() {
            // Given
            String customHash = "custom-identifier-hash-value-for-fallback-test";
            given(qimClient.findBySocialSub(anyString(), anyString(), anyString()))
                    .willThrow(new RuntimeException("오류"));

            // When
            String result = invokeResolve(SUB, customHash, PROVIDER_CODE, CORRELATION_ID);

            // Then: 정확히 전달된 identifierHash 반환
            assertThat(result).isEqualTo(customHash);
        }

        @Test
        @DisplayName("장애 시 registerSocialUser() 미호출 (불필요한 추가 HTTP 요청 없음)")
        void resolve_findThrows_registerNeverCalled() {
            // Given
            given(qimClient.findBySocialSub(anyString(), anyString(), anyString()))
                    .willThrow(new RuntimeException("오류"));

            // When
            invokeResolve(SUB, IDENTIFIER_HASH, PROVIDER_CODE, CORRELATION_ID);

            // Then
            verify(qimClient, never())
                    .registerSocialUser(anyString(), anyString(), anyString(), anyString());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 헬퍼 — private 메서드 ReflectionTestUtils 호출
    // ──────────────────────────────────────────────────────────────────────

    /**
     * private resolveQimUserIdFromSub() 호출 헬퍼
     */
    private String invokeResolve(String sub, String identifierHash,
                                  String providerCode, String correlationId) {
        return ReflectionTestUtils.invokeMethod(
                service, "resolveQimUserIdFromSub",
                sub, identifierHash, providerCode, correlationId
        );
    }
}

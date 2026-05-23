package kr.go.smes.qsign.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.common.domain.AuthResult;
import kr.go.smes.qsign.api.dto.IdOAuthInputRequest;
import kr.go.smes.qsign.api.dto.OidcAuthRequest;
import kr.go.smes.qsign.application.AuthService;
import kr.go.smes.qsign.config.QSignWebConfig;
import kr.go.smes.qsign.config.SecurityHeadersFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthController 슬라이스 테스트 — @WebMvcTest
 *
 * <p>의존: {@link AuthService}, {@link InternalSigVerifier} 는 @MockitoBean 으로 대체.
 *
 * <p>커버 케이스:
 * <ul>
 *   <li>POST /api/v1/auth/oidc — 정상 200, providerCode 위임 검증</li>
 *   <li>POST /api/v1/auth/oidc — body @NotBlank 위반 → 400</li>
 *   <li>POST /api/v1/auth/oidc — X-Correlation-Id 헤더 누락 시 자동 생성</li>
 *   <li>POST /api/v1/auth/broker-input — X-Internal-Sig 누락 → 400 (헤더 required)</li>
 *   <li>POST /api/v1/auth/broker-input — X-Internal-Sig 검증 실패 → 서명 검증 실패 + AuthService 호출 없음</li>
 *   <li>POST /api/v1/auth/broker-input — 정상 흐름 200, IdOAuthInput 빌딩 확인</li>
 *   <li>GET /api/v1/auth/{authResultId} — 정상 200</li>
 * </ul>
 *
 * <p>설계서 17.1 / 9.4 / 11.7 — 신뢰 루트 (Root of Trust) 회귀 방어
 */
@WebMvcTest(
        controllers = AuthController.class,
        excludeFilters = {
                // SecurityHeadersFilter 는 응답 헤더만 부여하므로 슬라이스 테스트에서는 불필요.
                // 격리도를 높이기 위해 제외.
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = SecurityHeadersFilter.class
                ),
                // QSignWebConfig 는 RedisConnectionFactory / Cache 빈을 요구하므로
                // 슬라이스 테스트의 컨텍스트 로딩 실패를 방지하기 위해 제외.
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = QSignWebConfig.class
                )
        }
)
class AuthControllerTest {

    private static final String FAKE_SIG = "deadbeefcafef00d";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private InternalSigVerifier internalSigVerifier;

    private AuthResult stubAuthResult;

    @BeforeEach
    void setUp() {
        stubAuthResult = AuthResult.builder()
                .authResultId("ar-test-0001")
                .correlationId("corr-test-0001")
                .authLevel(AuthResult.AuthLevel.L2)
                .providerCode("NAVER_OIDC")
                .providerTxId("tx-001")
                .identifierHash("hash-abc123")
                .authenticatedAt(Instant.now())
                .verificationResult(AuthResult.VerificationResult.SUCCESS)
                .authMethod("STANDARD_OIDC_NAVER")
                .build();
    }

    // ------------------------------------------------------------------
    // POST /api/v1/auth/oidc
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("POST /api/v1/auth/oidc")
    class OidcEndpoint {

        @Test
        @DisplayName("정상 요청 → 200 + AuthResult 반환 + AuthService 위임 인자 정확")
        void validRequest_returns200AndDelegates() throws Exception {
            given(authService.issueFromOidc(anyString(), eq("NAVER_OIDC"), eq("dummy.id.token"), eq("L2")))
                    .willReturn(stubAuthResult);

            OidcAuthRequest req = new OidcAuthRequest();
            setField(req, "providerCode", "NAVER_OIDC");
            setField(req, "idToken", "dummy.id.token");
            setField(req, "requestedLevel", "L2");

            mockMvc.perform(post("/api/v1/auth/oidc")
                            .header("X-Correlation-Id", "corr-test-0001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authResultId").value("ar-test-0001"))
                    .andExpect(jsonPath("$.providerCode").value("NAVER_OIDC"))
                    .andExpect(jsonPath("$.authLevel").value("L2"));

            then(authService).should()
                    .issueFromOidc(eq("corr-test-0001"), eq("NAVER_OIDC"),
                            eq("dummy.id.token"), eq("L2"));
        }

        @Test
        @DisplayName("X-Correlation-Id 헤더 누락 시 컨트롤러가 새 ID 생성")
        void missingCorrelationIdHeader_generatesNewOne() throws Exception {
            given(authService.issueFromOidc(anyString(), anyString(), anyString(), anyString()))
                    .willReturn(stubAuthResult);

            OidcAuthRequest req = new OidcAuthRequest();
            setField(req, "providerCode", "NAVER_OIDC");
            setField(req, "idToken", "dummy.id.token");
            setField(req, "requestedLevel", "L2");

            mockMvc.perform(post("/api/v1/auth/oidc")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> cidCaptor = ArgumentCaptor.forClass(String.class);
            then(authService).should()
                    .issueFromOidc(cidCaptor.capture(), anyString(), anyString(), anyString());
            assertThat(cidCaptor.getValue())
                    .as("자동 생성된 correlationId 는 비어있지 않아야 한다")
                    .isNotBlank();
        }

        @Test
        @DisplayName("providerCode 가 blank 면 @NotBlank 위반 → 400")
        void blankProviderCode_returns400() throws Exception {
            OidcAuthRequest req = new OidcAuthRequest();
            setField(req, "providerCode", "");
            setField(req, "idToken", "dummy.id.token");
            setField(req, "requestedLevel", "L2");

            mockMvc.perform(post("/api/v1/auth/oidc")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());

            then(authService).should(never())
                    .issueFromOidc(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("idToken 누락 시 @NotBlank 위반 → 400")
        void missingIdToken_returns400() throws Exception {
            OidcAuthRequest req = new OidcAuthRequest();
            setField(req, "providerCode", "NAVER_OIDC");
            // idToken 없음
            setField(req, "requestedLevel", "L2");

            mockMvc.perform(post("/api/v1/auth/oidc")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());

            then(authService).should(never())
                    .issueFromOidc(anyString(), anyString(), anyString(), anyString());
        }
    }

    // ------------------------------------------------------------------
    // POST /api/v1/auth/broker-input  ← 신뢰 루트
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("POST /api/v1/auth/broker-input (X-Internal-Sig 검증)")
    class BrokerInputEndpoint {

        @Test
        @DisplayName("X-Internal-Sig 헤더 누락 → 400 (required header)")
        void missingInternalSigHeader_returns400() throws Exception {
            IdOAuthInputRequest req = buildValidBrokerRequest();

            mockMvc.perform(post("/api/v1/auth/broker-input")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());

            then(internalSigVerifier).should(never()).verify(anyString(), anyString());
            then(authService).should(never()).issueFromIdOAuthInput(any());
        }

        @Test
        @DisplayName("X-Internal-Sig 검증 실패 → AuthService 호출되지 않음 (신뢰 루트 차단)")
        void invalidInternalSig_doesNotInvokeAuthService() throws Exception {
            given(internalSigVerifier.verify(eq(FAKE_SIG), anyString())).willReturn(false);

            IdOAuthInputRequest req = buildValidBrokerRequest();

            // ControllerAdvice 가 없으므로 PlatformException 이 그대로 전파됨 → status 검증 대신
            // ServletException 형태의 실패를 받아내고 핵심은 "AuthService 가 호출되지 않았음" 확인
            try {
                mockMvc.perform(post("/api/v1/auth/broker-input")
                                .header("X-Internal-Sig", FAKE_SIG)
                                .header("X-Correlation-Id", "corr-broker-bad")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)));
            } catch (Exception ignored) {
                // PlatformException 은 ServletException 으로 래핑되어 던져질 수 있음 — 의도된 동작
            }

            // 핵심 보안 회귀 방어: 서명 검증 실패 시 AuthService 는 절대 호출되면 안 된다
            then(internalSigVerifier).should().verify(eq(FAKE_SIG), eq("corr-broker-bad"));
            then(authService).should(never()).issueFromIdOAuthInput(any());
        }

        @Test
        @DisplayName("X-Internal-Sig 검증 성공 → 200 + IdOAuthInput 빌딩 확인")
        void validInternalSig_returns200AndDelegates() throws Exception {
            given(internalSigVerifier.verify(eq(FAKE_SIG), anyString())).willReturn(true);
            given(authService.issueFromIdOAuthInput(any())).willReturn(stubAuthResult);

            IdOAuthInputRequest req = buildValidBrokerRequest();

            mockMvc.perform(post("/api/v1/auth/broker-input")
                            .header("X-Internal-Sig", FAKE_SIG)
                            .header("X-Correlation-Id", "corr-broker-good")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authResultId").value("ar-test-0001"));

            then(internalSigVerifier).should().verify(eq(FAKE_SIG), eq("corr-broker-good"));
            then(authService).should().issueFromIdOAuthInput(any());
        }

        @Test
        @DisplayName("providerCode 가 blank 인 broker-input → @NotBlank → 400 (검증 전 차단)")
        void blankProviderCodeInBrokerInput_returns400() throws Exception {
            // 본 케이스에서는 verifier 자체가 호출되지 않을 수 있음을 검증
            IdOAuthInputRequest req = buildValidBrokerRequest();
            setField(req, "providerCode", ""); // @NotBlank 위반

            mockMvc.perform(post("/api/v1/auth/broker-input")
                            .header("X-Internal-Sig", FAKE_SIG)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());

            then(authService).should(never()).issueFromIdOAuthInput(any());
        }

        private IdOAuthInputRequest buildValidBrokerRequest() {
            IdOAuthInputRequest req = new IdOAuthInputRequest();
            setField(req, "providerCode", "NAVER_LOGIN");
            setField(req, "providerTxId", "tx-broker-001");
            setField(req, "requestedAuthLevel", "L2");
            setField(req, "identifierHash", "hash-broker-abc");
            setField(req, "providerVerified", true);
            Map<String, Object> claims = new HashMap<>();
            claims.put("name_verified", true);
            setField(req, "claims", claims);
            return req;
        }
    }

    // ------------------------------------------------------------------
    // GET /api/v1/auth/{authResultId}
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("GET /api/v1/auth/{authResultId}")
    class GetAuthResultEndpoint {

        @Test
        @DisplayName("존재하는 authResultId → 200 + AuthResult JSON")
        void existingId_returns200() throws Exception {
            given(authService.findById(eq("ar-test-0001"), anyString()))
                    .willReturn(stubAuthResult);

            mockMvc.perform(get("/api/v1/auth/{id}", "ar-test-0001")
                            .header("X-Correlation-Id", "corr-get-001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authResultId").value("ar-test-0001"))
                    .andExpect(jsonPath("$.verificationResult").value("SUCCESS"));

            then(authService).should().findById(eq("ar-test-0001"), eq("corr-get-001"));
        }

        @Test
        @DisplayName("X-Correlation-Id 헤더 누락 시 자동 생성된 ID 로 AuthService 호출")
        void missingCorrelationId_generatesNewOne() throws Exception {
            given(authService.findById(anyString(), anyString())).willReturn(stubAuthResult);

            mockMvc.perform(get("/api/v1/auth/{id}", "ar-test-0001"))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> cidCaptor = ArgumentCaptor.forClass(String.class);
            then(authService).should()
                    .findById(eq("ar-test-0001"), cidCaptor.capture());
            assertThat(cidCaptor.getValue()).isNotBlank();
        }
    }

    // ------------------------------------------------------------------
    // 테스트 헬퍼
    // ------------------------------------------------------------------

    /**
     * @NoArgsConstructor + @Getter 만 있는 DTO 에 reflection 으로 필드 주입.
     * (DTO 가 setter 를 노출하지 않으므로 ObjectMapper 직렬화를 통해 우회하기보다
     *  reflection 으로 직접 값을 세팅하는 편이 테스트 의도를 명확히 한다)
     */
    private static void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "테스트 setField 실패: " + target.getClass().getSimpleName() + "#" + fieldName, e);
        }
    }

}

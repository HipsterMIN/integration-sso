package io.github.hipstermin.idem.hub.kr.memberlookup;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.config.HandoffAgencyKeyInterceptor;
import io.github.hipstermin.idem.hub.fe.config.IdoWebMvcConfig;
import io.github.hipstermin.idem.hub.fe.config.InternalCallerAuthInterceptor;
import io.github.hipstermin.idem.hub.gateway.HmacSignatureFilter;
import io.github.hipstermin.idem.hub.kr.fe.KrHubWebMvcConfig;
import io.github.hipstermin.idem.hub.ratelimit.AuthRateLimitInterceptor;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MemberLookupController — @WebMvcTest 슬라이스 테스트
 *
 * <p>검증 범위:
 * <ul>
 *   <li>POST /api/v1/member/lookup — CI 기반 회원 조회</li>
 *   <li>GET  /api/v1/member/lookup/hash — identifierHash 기반 회원 조회</li>
 * </ul>
 *
 * <p>검증 시나리오:
 * <ol>
 *   <li>encryptedCi 누락 → 400 Bad Request + MISSING_ENCRYPTED_CI 에러 코드</li>
 *   <li>encryptedCi 제공, 사용자 조회 성공 → 200 OK + 결과 JSON 반환</li>
 *   <li>encryptedCi 제공, 사용자 없음 → PlatformException(IM_USER_NOT_FOUND) → 500 (예외 전파)</li>
 *   <li>identifierHash 기반 조회 성공 → 200 OK + 결과 JSON 반환</li>
 *   <li>identifierHash 기반 조회 실패 → PlatformException(IM_USER_NOT_FOUND)</li>
 *   <li>X-Agency-Code 헤더 누락 → 400</li>
 *   <li>X-Correlation-Id 헤더 생략 가능 (optional)</li>
 *   <li>감사 로그 발행 여부 검증</li>
 * </ol>
 *
 * <p>@WebMvcTest 슬라이스로 MemberLookupService, AuditLogPublisher를 MockitoBean으로 격리.
 * HandoffAgencyKeyInterceptor는 /api/v1/member/** 경로에 등록되지 않으므로 별도 Mock 불필요.
 */
@WebMvcTest(
        controllers = MemberLookupController.class,
        excludeFilters = {
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = HandoffAgencyKeyInterceptor.class
                ),
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = IdoWebMvcConfig.class
                ),
                // S8-a: KR 에디션 MVC 설정도 코어 인터셉터 빈을 요구하므로 슬라이스에서 제외
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = KrHubWebMvcConfig.class
                ),
                // [P2 수정 후 추가] AuthRateLimitInterceptor는 RedisTemplate 의존성을 가지므로
                // @WebMvcTest 슬라이스에서 Redis 빈 없이 컨텍스트 로딩 실패를 방지하기 위해 제외
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = AuthRateLimitInterceptor.class
                ),
                // [Sprint 17 추가] HmacSignatureFilter는 AgencyHmacKeyStore 의존성을 가지므로
                // @WebMvcTest 슬라이스에서 제외 처리
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = HmacSignatureFilter.class
                ),
                // [γ-게이트 #2b] InternalCallerAuthInterceptor 는 @Component 로 등록되어
                // 컴포넌트 스캔에 포함되며 생성자에서 MeterRegistry/InternalCallersProperties 를
                // 요구한다. @WebMvcTest 슬라이스는 actuator/micrometer 빈을 로딩하지 않으므로
                // 빈 생성 실패가 발생 → MemberLookup 16 건 테스트 일괄 ApplicationContext
                // 로딩 실패의 직접 원인. 슬라이스 컨텍스트에서 제외 처리.
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = InternalCallerAuthInterceptor.class
                )
        }
)
@DisplayName("MemberLookupController — @WebMvcTest 슬라이스 테스트")
class MemberLookupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MemberLookupService memberLookupService;

    @MockitoBean
    private AuditLogPublisher auditLogPublisher;

    // HandoffAgencyKeyInterceptor의 JdbcTemplate 의존성 충족 (컴포넌트 제외 후 불필요하나 안전용)
    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    private static final String AGENCY_CODE     = "AGENCY_001";
    private static final String CORRELATION_ID  = "cid-test-9876";
    private static final String ENCRYPTED_CI    = "v1.ABC123.ENC456CIPHERTEXT";
    private static final String IDENTIFIER_HASH = "sha256hashvalue1234567890abcdef";

    // ════════════════════════════════════════════════════════════════════════
    // Nested 1: POST /api/v1/member/lookup — CI 기반 회원 조회
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("POST /api/v1/member/lookup — CI 기반 회원 조회")
    class LookupByCi {

        @Test
        @DisplayName("encryptedCi가 null이면 400 Bad Request + MISSING_ENCRYPTED_CI 반환")
        void missingEncryptedCi_returnsBadRequest() throws Exception {
            mockMvc.perform(post("/api/v1/member/lookup")
                            .header("X-Agency-Code", AGENCY_CODE)
                            .header("X-Correlation-Id", CORRELATION_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andDo(print())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("MISSING_ENCRYPTED_CI"))
                    .andExpect(jsonPath("$.message").isString());

            // MemberLookupService는 호출되지 않아야 함
            then(memberLookupService).shouldHaveNoInteractions();
        }

        @ParameterizedTest(name = "encryptedCi=\"{0}\" (blank) → 400")
        @ValueSource(strings = {"", "  "})
        @DisplayName("encryptedCi가 blank이면 400 Bad Request 반환")
        void blankEncryptedCi_returnsBadRequest(String blank) throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("encryptedCi", blank));

            mockMvc.perform(post("/api/v1/member/lookup")
                            .header("X-Agency-Code", AGENCY_CODE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("MISSING_ENCRYPTED_CI"));

            then(memberLookupService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("encryptedCi 정상 + 서비스 응답 → 200 OK + 결과 JSON 반환")
        void validEncryptedCi_returnsOk() throws Exception {
            Map<String, Object> serviceResult = Map.of(
                    "qimUserId",  "usr-uuid-001",
                    "status",     "ACTIVE",
                    "maskedName", "홍*동"
            );
            given(memberLookupService.lookupByCi(anyString(), anyString(), anyString()))
                    .willReturn(serviceResult);

            String body = objectMapper.writeValueAsString(Map.of("encryptedCi", ENCRYPTED_CI));

            mockMvc.perform(post("/api/v1/member/lookup")
                            .header("X-Agency-Code", AGENCY_CODE)
                            .header("X-Correlation-Id", CORRELATION_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.qimUserId").value("usr-uuid-001"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.maskedName").value("홍*동"));

            // MemberLookupService 정확한 인수로 호출 확인
            then(memberLookupService).should(times(1))
                    .lookupByCi(eq(ENCRYPTED_CI), eq(AGENCY_CODE), anyString());
        }

        @Test
        @DisplayName("X-Correlation-Id 헤더 생략 가능 — 자동 생성되어 서비스 정상 호출")
        void withoutCorrelationId_autoGenerated_success() throws Exception {
            Map<String, Object> serviceResult = Map.of("qimUserId", "usr-uuid-002");
            given(memberLookupService.lookupByCi(anyString(), anyString(), anyString()))
                    .willReturn(serviceResult);

            String body = objectMapper.writeValueAsString(Map.of("encryptedCi", ENCRYPTED_CI));

            // X-Correlation-Id 헤더 없이 요청 — optional이므로 정상 처리되어야 함
            mockMvc.perform(post("/api/v1/member/lookup")
                            .header("X-Agency-Code", AGENCY_CODE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.qimUserId").value("usr-uuid-002"));
        }

        @Test
        @DisplayName("서비스에서 PlatformException(IM_USER_NOT_FOUND) 발생 시 404 반환")
        void serviceThrowsNotFound_returns404() throws Exception {
            given(memberLookupService.lookupByCi(anyString(), anyString(), anyString()))
                    .willThrow(new PlatformException(
                            PlatformErrorCode.IM_USER_NOT_FOUND, CORRELATION_ID));

            String body = objectMapper.writeValueAsString(Map.of("encryptedCi", ENCRYPTED_CI));

            // GlobalExceptionHandler가 PlatformException을 errorCode.httpStatus로 매핑
            // IM_USER_NOT_FOUND → HttpStatus.NOT_FOUND → 404
            mockMvc.perform(post("/api/v1/member/lookup")
                            .header("X-Agency-Code", AGENCY_CODE)
                            .header("X-Correlation-Id", CORRELATION_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("서비스 성공 후 감사 로그 발행 시도")
        void success_publishesAuditLog() throws Exception {
            Map<String, Object> serviceResult = Map.of("qimUserId", "usr-audit-001");
            given(memberLookupService.lookupByCi(anyString(), anyString(), anyString()))
                    .willReturn(serviceResult);

            String body = objectMapper.writeValueAsString(Map.of("encryptedCi", ENCRYPTED_CI));

            mockMvc.perform(post("/api/v1/member/lookup")
                            .header("X-Agency-Code", AGENCY_CODE)
                            .header("X-Correlation-Id", CORRELATION_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());

            // AuditLogPublisher.publish() 1회 호출 확인
            then(auditLogPublisher).should(times(1))
                    .publish(ArgumentMatchers.<AuditLogPublisher.AuditEntry>any());
        }

        @Test
        @DisplayName("X-Agency-Code 헤더 누락 — 400")
        void missingAgencyCode_returnsError() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("encryptedCi", ENCRYPTED_CI));

            // S8-a: GlobalExceptionHandler 가 MissingRequestHeaderException 을 400 으로 매핑한다 (종전 500)
            mockMvc.perform(post("/api/v1/member/lookup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            then(memberLookupService).shouldHaveNoInteractions();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Nested 2: GET /api/v1/member/lookup/hash — identifierHash 기반 회원 조회
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("GET /api/v1/member/lookup/hash — identifierHash 기반 회원 조회")
    class LookupByHash {

        @Test
        @DisplayName("identifierHash 제공, 사용자 조회 성공 → 200 OK + 결과 JSON 반환")
        void validHash_success_returns200() throws Exception {
            Map<String, Object> serviceResult = Map.of(
                    "qimUserId", "usr-hash-001",
                    "status", "ACTIVE",
                    "maskedMobile", "010-****-5678"
            );

            given(memberLookupService.lookupByHash(
                    eq(IDENTIFIER_HASH), eq(AGENCY_CODE), anyString()
            )).willReturn(serviceResult);

            mockMvc.perform(get("/api/v1/member/lookup/hash")
                            .header("X-Agency-Code", AGENCY_CODE)
                            .header("X-Correlation-Id", CORRELATION_ID)
                            .param("identifierHash", IDENTIFIER_HASH))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.qimUserId").value("usr-hash-001"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.maskedMobile").value("010-****-5678"));

            then(memberLookupService).should(times(1))
                    .lookupByHash(eq(IDENTIFIER_HASH), eq(AGENCY_CODE), anyString());
        }

        @Test
        @DisplayName("identifierHash 파라미터 누락 → 에러 반환 (누락 매개변수)")
        void missingIdentifierHash_returnsError() throws Exception {
            // @RequestParam identifierHash 는 required=true(기본값)
            // GlobalExceptionHandler 맵핑에 따라 4xx 또는 5xx 반환
            mockMvc.perform(get("/api/v1/member/lookup/hash")
                            .header("X-Agency-Code", AGENCY_CODE))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        assert status >= 400 : "Expected 4xx or 5xx but was " + status;
                    });

            then(memberLookupService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("서비스에서 PlatformException(IM_USER_NOT_FOUND) 발생 시 404 반환")
        void serviceThrowsNotFound_returns404() throws Exception {
            given(memberLookupService.lookupByHash(anyString(), anyString(), anyString()))
                    .willThrow(new PlatformException(
                            PlatformErrorCode.IM_USER_NOT_FOUND, CORRELATION_ID));

            // GlobalExceptionHandler: IM_USER_NOT_FOUND → HttpStatus.NOT_FOUND → 404
            mockMvc.perform(get("/api/v1/member/lookup/hash")
                            .header("X-Agency-Code", AGENCY_CODE)
                            .header("X-Correlation-Id", CORRELATION_ID)
                            .param("identifierHash", IDENTIFIER_HASH))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("X-Agency-Code 헤더 누락 → 400")
        void missingAgencyCode_returnsError() throws Exception {
            // S8-a: 필수 헤더 누락은 400 (종전 500)
            mockMvc.perform(get("/api/v1/member/lookup/hash")
                            .param("identifierHash", IDENTIFIER_HASH))
                    .andExpect(status().isBadRequest());

            then(memberLookupService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("X-Correlation-Id 헤더 생략 가능 — 자동 생성 후 서비스 정상 호출")
        void withoutCorrelationId_autoGenerated_success() throws Exception {
            Map<String, Object> serviceResult = Map.of("qimUserId", "usr-hash-002");
            given(memberLookupService.lookupByHash(anyString(), anyString(), anyString()))
                    .willReturn(serviceResult);

            mockMvc.perform(get("/api/v1/member/lookup/hash")
                            .header("X-Agency-Code", AGENCY_CODE)
                            .param("identifierHash", IDENTIFIER_HASH))
                    .andExpect(status().isOk());

            then(memberLookupService).should(times(1))
                    .lookupByHash(eq(IDENTIFIER_HASH), eq(AGENCY_CODE), anyString());
        }

        @Test
        @DisplayName("서비스 성공 후 감사 로그 발행 시도")
        void success_publishesAuditLog() throws Exception {
            Map<String, Object> serviceResult = Map.of("qimUserId", "usr-audit-hash");
            given(memberLookupService.lookupByHash(anyString(), anyString(), anyString()))
                    .willReturn(serviceResult);

            mockMvc.perform(get("/api/v1/member/lookup/hash")
                            .header("X-Agency-Code", AGENCY_CODE)
                            .header("X-Correlation-Id", CORRELATION_ID)
                            .param("identifierHash", IDENTIFIER_HASH))
                    .andExpect(status().isOk());

            then(auditLogPublisher).should(times(1))
                    .publish(ArgumentMatchers.<AuditLogPublisher.AuditEntry>any());
        }

        @Test
        @DisplayName("IDO_QIM_UNREACHABLE 예외 발생 시 서비스 장애 응답 확인")
        void serviceThrowsQimUnreachable_propagatesException() throws Exception {
            given(memberLookupService.lookupByHash(anyString(), anyString(), anyString()))
                    .willThrow(new PlatformException(
                            PlatformErrorCode.IDO_QIM_UNREACHABLE, CORRELATION_ID));

            mockMvc.perform(get("/api/v1/member/lookup/hash")
                            .header("X-Agency-Code", AGENCY_CODE)
                            .header("X-Correlation-Id", CORRELATION_ID)
                            .param("identifierHash", IDENTIFIER_HASH))
                    .andExpect(status().is5xxServerError());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Nested 3: 감사 로그 비치명적 동작 검증
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("감사 로그 비치명적 동작 검증")
    class AuditLogBehavior {

        @Test
        @DisplayName("감사 로그 발행이 실패해도 응답은 200 OK — 감사 로그 실패는 비치명적")
        void auditPublishFails_responseStillOk() throws Exception {
            Map<String, Object> serviceResult = Map.of("qimUserId", "usr-audit-fail");
            given(memberLookupService.lookupByCi(anyString(), anyString(), anyString()))
                    .willReturn(serviceResult);

            // 감사 로그 발행에서 RuntimeException 발생 — 컨트롤러 내부에서 try-catch로 처리됨
            willThrow(new RuntimeException("Kafka unavailable"))
                    .given(auditLogPublisher)
                    .publish(ArgumentMatchers.<AuditLogPublisher.AuditEntry>any());

            String body = objectMapper.writeValueAsString(Map.of("encryptedCi", ENCRYPTED_CI));

            // 컨트롤러의 publishAudit()이 try-catch로 예외 억제 → 200 반환되어야 함
            mockMvc.perform(post("/api/v1/member/lookup")
                            .header("X-Agency-Code", AGENCY_CODE)
                            .header("X-Correlation-Id", CORRELATION_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.qimUserId").value("usr-audit-fail"));
        }
    }
}

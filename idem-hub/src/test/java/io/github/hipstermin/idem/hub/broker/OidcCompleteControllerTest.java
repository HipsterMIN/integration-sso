package io.github.hipstermin.idem.hub.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import io.github.hipstermin.idem.hub.auth.dto.im.QimMemberInfo;
import io.github.hipstermin.idem.hub.auth.dto.im.QimRegisterResponse;
import io.github.hipstermin.idem.hub.broker.dto.OidcCompleteRequest;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * OidcCompleteController 단위 테스트
 *
 * <p><b>P0 검증 항목</b>:
 * <ol>
 *   <li>CI가 있는 기존 사용자 → QimClient.findByCi() 호출 → 실제 qimUserId 사용</li>
 *   <li>CI가 있는 신규 사용자 → QimClient.registerUser() 호출 → 신규 qimUserId 사용</li>
 *   <li>CI가 없는 경우 → identifierHash 폴백 + 경고 (PoC 경로)</li>
 *   <li>X-Internal-Sig 없으면 → 서명 검증 실패 → PlatformException</li>
 * </ol>
 */
@DisplayName("OidcCompleteController — P0 qimUserId 해석 단위 테스트")
@ExtendWith(MockitoExtension.class)
class OidcCompleteControllerTest {

    @Mock
    private FeSessionService feSessionService;

    @Mock
    private InternalSigVerifier internalSigVerifier;

    @Mock
    private QimClient qimClient;

    @InjectMocks
    private OidcCompleteController controller;

    @BeforeEach
    void setUp() {
        // broker.mode=qsign (기본값)
        ReflectionTestUtils.setField(controller, "brokerMode", "qsign");
    }

    // ── qimUserId 해석 테스트 ─────────────────────────────────────────────

    @Nested
    @DisplayName("resolveQimUserId — CI → qimUserId 해석")
    class ResolveQimUserIdTest {

        @Test
        @DisplayName("[P0] CI 있음 + 기존 Q-IM 사용자 → findByCi() 결과의 qimUserId 사용")
        void resolve_existingUser_shouldUseQimUserIdFromFindByCi() {
            // given
            String ci = "test-ci-value-0123456789abcdef";
            String expectedQimUserId = "qim-user-existing-001";

            QimMemberInfo memberInfo = QimMemberInfo.builder()
                    .qimUserId(expectedQimUserId)
                    .status("ACTIVE")
                    .build();

            given(feSessionService.isValidReturnUrl(any())).willReturn(true);
            given(internalSigVerifier.verify(any(), any())).willReturn(true);
            given(qimClient.findByCi(eq(ci), eq("INDIVIDUAL"), anyString()))
                    .willReturn(Optional.of(memberInfo));
            given(feSessionService.create(eq(expectedQimUserId), any(), any(), any()))
                    .willReturn(buildMockSession(expectedQimUserId));

            OidcCompleteRequest req = buildRequest(ci, "INDIVIDUAL");
            MockHttpServletResponse response = new MockHttpServletResponse();

            // when
            controller.complete("valid-sig", "q-sign", "cid-001", req, response);

            // then
            // findByCi는 1번 호출됨
            verify(qimClient, times(1)).findByCi(eq(ci), eq("INDIVIDUAL"), anyString());
            // registerUser는 호출되지 않음 (기존 사용자)
            verify(qimClient, never()).registerUser(any(), anyString());
            // FeSession은 실제 qimUserId로 생성됨
            verify(feSessionService, times(1))
                    .create(eq(expectedQimUserId), any(), any(), any());
        }

        @Test
        @DisplayName("[P0] CI 있음 + Q-IM 미등록 사용자 → registerUser() 호출 후 신규 qimUserId 사용")
        void resolve_newUser_shouldRegisterAndUseNewQimUserId() {
            // given
            String ci = "new-user-ci-value-0123456789abc";
            String newQimUserId = "qim-user-new-00234";

            QimRegisterResponse registerResponse = QimRegisterResponse.builder()
                    .qimUserId(newQimUserId)
                    .isNew(true)
                    .status("ACTIVE")
                    .build();

            given(feSessionService.isValidReturnUrl(any())).willReturn(true);
            given(internalSigVerifier.verify(any(), any())).willReturn(true);
            given(qimClient.findByCi(eq(ci), eq("INDIVIDUAL"), anyString()))
                    .willReturn(Optional.empty());  // 미등록
            given(qimClient.registerUser(any(), anyString()))
                    .willReturn(registerResponse);
            given(feSessionService.create(eq(newQimUserId), any(), any(), any()))
                    .willReturn(buildMockSession(newQimUserId));

            OidcCompleteRequest req = buildRequest(ci, "INDIVIDUAL");
            MockHttpServletResponse response = new MockHttpServletResponse();

            // when
            controller.complete("valid-sig", "q-sign", "cid-002", req, response);

            // then
            verify(qimClient, times(1)).findByCi(eq(ci), eq("INDIVIDUAL"), anyString());
            verify(qimClient, times(1)).registerUser(any(), anyString());
            verify(feSessionService, times(1))
                    .create(eq(newQimUserId), any(), any(), any());
        }

        @Test
        @DisplayName("[P0-FALLBACK] CI 없음 → identifierHash를 qimUserId로 폴백 (PoC 경로)")
        void resolve_noCi_shouldFallbackToIdentifierHash() {
            // given
            String identifierHash = "sha256-hash-of-sub-value";

            given(feSessionService.isValidReturnUrl(any())).willReturn(true);
            given(internalSigVerifier.verify(any(), any())).willReturn(true);
            given(feSessionService.create(eq(identifierHash), any(), any(), any()))
                    .willReturn(buildMockSession(identifierHash));

            OidcCompleteRequest req = buildRequestNoCi(identifierHash);
            MockHttpServletResponse response = new MockHttpServletResponse();

            // when
            controller.complete("valid-sig", "q-sign", "cid-003", req, response);

            // then — QimClient 호출 없이 identifierHash로 세션 생성
            verify(qimClient, never()).findByCi(any(), any(), any());
            verify(qimClient, never()).registerUser(any(), any());
            verify(feSessionService, times(1))
                    .create(eq(identifierHash), any(), any(), any());
        }

        @Test
        @DisplayName("[P0] memberType 미전달 시 'INDIVIDUAL' 기본값 사용")
        void resolve_noMemberType_shouldDefaultToIndividual() {
            // given
            String ci = "ci-no-membertype-value-0123456789";
            String qimUserId = "qim-user-default-type";

            QimMemberInfo memberInfo = QimMemberInfo.builder()
                    .qimUserId(qimUserId)
                    .status("ACTIVE")
                    .build();

            given(feSessionService.isValidReturnUrl(any())).willReturn(true);
            given(internalSigVerifier.verify(any(), any())).willReturn(true);
            // memberType이 null이면 "INDIVIDUAL"로 조회해야 함
            given(qimClient.findByCi(eq(ci), eq("INDIVIDUAL"), anyString()))
                    .willReturn(Optional.of(memberInfo));
            given(feSessionService.create(eq(qimUserId), any(), any(), any()))
                    .willReturn(buildMockSession(qimUserId));

            OidcCompleteRequest req = buildRequest(ci, null);  // memberType=null
            MockHttpServletResponse response = new MockHttpServletResponse();

            // when
            controller.complete("valid-sig", "q-sign", "cid-004", req, response);

            // then
            verify(qimClient).findByCi(eq(ci), eq("INDIVIDUAL"), anyString());
        }
    }

    // ── X-Internal-Sig 검증 테스트 ────────────────────────────────────────

    @Nested
    @DisplayName("X-Internal-Sig 서명 검증")
    class SigVerificationTest {

        @Test
        @DisplayName("[P1] X-Internal-Sig 검증 실패 시 PlatformException 발생")
        void complete_invalidSig_shouldThrowException() {
            // given
            given(internalSigVerifier.verify(any(), any())).willReturn(false);

            OidcCompleteRequest req = buildRequest("any-ci", "INDIVIDUAL");
            MockHttpServletResponse response = new MockHttpServletResponse();

            // when & then
            org.junit.jupiter.api.Assertions.assertThrows(
                    io.github.hipstermin.idem.common.error.PlatformException.class,
                    () -> controller.complete("invalid-sig", "q-sign", "cid-sig-fail", req, response)
            );

            // QimClient, FeSessionService는 호출되지 않아야 함
            verify(qimClient, never()).findByCi(any(), any(), any());
            verify(feSessionService, never()).create(any(), any(), any(), any());
        }

        @Test
        @DisplayName("[P1] X-Internal-Sig null이면 서명 검증 실패")
        void complete_nullSig_shouldThrowException() {
            // given
            given(internalSigVerifier.verify(isNull(), any())).willReturn(false);

            OidcCompleteRequest req = buildRequest("any-ci", "INDIVIDUAL");
            MockHttpServletResponse response = new MockHttpServletResponse();

            // when & then
            org.junit.jupiter.api.Assertions.assertThrows(
                    io.github.hipstermin.idem.common.error.PlatformException.class,
                    () -> controller.complete(null, "q-sign", "cid-null-sig", req, response)
            );
        }

        @Test
        @DisplayName("keycloak 모드에서는 내부 콜백 수신 시 409 반환")
        void complete_keycloakMode_shouldReturn409() {
            // given
            ReflectionTestUtils.setField(controller, "brokerMode", "keycloak");

            OidcCompleteRequest req = buildRequest("any-ci", "INDIVIDUAL");
            MockHttpServletResponse response = new MockHttpServletResponse();

            // when
            var result = controller.complete("any-sig", "q-sign", "cid-kc", req, response);

            // then
            assertThat(result.getStatusCode().value()).isEqualTo(409);
            verify(internalSigVerifier, never()).verify(any(), any());
        }
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    /**
     * CI + memberType 포함 요청 DTO 생성 (Reflection 사용)
     */
    private OidcCompleteRequest buildRequest(String ci, String memberType) {
        OidcCompleteRequest req = new OidcCompleteRequest();
        ReflectionTestUtils.setField(req, "authResultId",    "auth-result-001");
        ReflectionTestUtils.setField(req, "identifierHash",  "sha256-hash-001");
        ReflectionTestUtils.setField(req, "authLevel",       "L1");
        ReflectionTestUtils.setField(req, "providerCode",    "KAKAO_OIDC");
        ReflectionTestUtils.setField(req, "correlationId",   "cid-test");
        ReflectionTestUtils.setField(req, "returnUrl",       "http://localhost:8084/callback");
        ReflectionTestUtils.setField(req, "ci",              ci);
        ReflectionTestUtils.setField(req, "memberType",      memberType);
        return req;
    }

    /**
     * CI 없는 요청 DTO 생성 (PoC 폴백 경로)
     */
    private OidcCompleteRequest buildRequestNoCi(String identifierHash) {
        OidcCompleteRequest req = new OidcCompleteRequest();
        ReflectionTestUtils.setField(req, "authResultId",    "auth-result-002");
        ReflectionTestUtils.setField(req, "identifierHash",  identifierHash);
        ReflectionTestUtils.setField(req, "authLevel",       "L1");
        ReflectionTestUtils.setField(req, "providerCode",    "KAKAO_OIDC");
        ReflectionTestUtils.setField(req, "correlationId",   "cid-no-ci");
        ReflectionTestUtils.setField(req, "returnUrl",       "http://localhost:8084/callback");
        ReflectionTestUtils.setField(req, "ci",              null);
        ReflectionTestUtils.setField(req, "memberType",      null);
        return req;
    }

    /**
     * Mock FeSession 생성
     */
    private FeSession buildMockSession(String qimUserId) {
        return FeSession.builder()
                .feSessionId("mock-session-id-0123456789abcdef")
                .qimUserId(qimUserId)
                .authResultId("auth-result-001")
                .authLevel("L1")
                .createdAt(Instant.now())
                .lastActivityAt(Instant.now())
                .absoluteExpiresAt(Instant.now().plusSeconds(3600))
                .returnUrl("http://localhost:8084/callback")
                .advisoryFlag(false)
                .build();
    }
}

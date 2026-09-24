package io.github.hipstermin.idem.hub.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
import io.github.hipstermin.idem.hub.broker.dto.OidcCompleteRequest;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import io.github.hipstermin.idem.hub.identity.SubjectRegistration;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import io.github.hipstermin.idem.hub.infrastructure.QimMemberInfo;
import io.github.hipstermin.idem.hub.infrastructure.QimRegisterResponse;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
 *   <li>주체 키가 있는 기존 사용자 → QimClient.findByIdentifierHash() 결과의 qimUserId 사용</li>
 *   <li>주체 키가 있는 신규 사용자 → QimClient.registerSubject() 호출 → 신규 qimUserId 사용</li>
 *   <li>S8-a: 구 {@code ci} 필드는 scheme=CI 의 별칭, 정식 계약은 subjectScheme/subjectKey</li>
 *   <li>주체 키가 없는 경우 → (D2) 거부. allow-ciless-identity=true 일 때만 identifierHash 폴백</li>
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
    @DisplayName("resolveQimUserId — 주체 스킴·키 → qimUserId 해석")
    class ResolveQimUserIdTest {

        @Test
        @DisplayName("[P0] CI(별칭) 있음 + 기존 registry 사용자 → findByIdentifierHash(CI 해시) 결과의 qimUserId 사용")
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
            given(qimClient.findByIdentifierHash(eq(SubjectScheme.CI.identifierHash(ci)), anyString()))
                    .willReturn(Optional.of(memberInfo));
            given(feSessionService.create(eq(expectedQimUserId), any(), any(), any()))
                    .willReturn(buildMockSession(expectedQimUserId));

            OidcCompleteRequest req = buildRequest(ci);
            MockHttpServletResponse response = new MockHttpServletResponse();

            // when
            controller.complete("valid-sig", "q-sign", "cid-001", req, response);

            // then
            verify(qimClient, times(1)).findByIdentifierHash(eq(SubjectScheme.CI.identifierHash(ci)), anyString());
            verify(qimClient, never()).registerSubject(any());
            // FeSession은 실제 qimUserId로 생성됨
            verify(feSessionService, times(1))
                    .create(eq(expectedQimUserId), any(), any(), any());
        }

        @Test
        @DisplayName("[P0] CI(별칭) 있음 + registry 미등록 사용자 → registerSubject(scheme=CI) 후 신규 qimUserId 사용")
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
            given(qimClient.findByIdentifierHash(eq(SubjectScheme.CI.identifierHash(ci)), anyString()))
                    .willReturn(Optional.empty());  // 미등록
            given(qimClient.registerSubject(any()))
                    .willReturn(registerResponse);
            given(feSessionService.create(eq(newQimUserId), any(), any(), any()))
                    .willReturn(buildMockSession(newQimUserId));

            OidcCompleteRequest req = buildRequest(ci);
            MockHttpServletResponse response = new MockHttpServletResponse();

            // when
            controller.complete("valid-sig", "q-sign", "cid-002", req, response);

            // then
            ArgumentCaptor<SubjectRegistration> captor = ArgumentCaptor.forClass(SubjectRegistration.class);
            verify(qimClient, times(1)).registerSubject(captor.capture());
            assertThat(captor.getValue().scheme()).isEqualTo(SubjectScheme.CI);
            assertThat(captor.getValue().subjectKey()).isEqualTo(ci);
            assertThat(captor.getValue().providerCode()).isEqualTo("KAKAO_OIDC");
            verify(feSessionService, times(1))
                    .create(eq(newQimUserId), any(), any(), any());
        }

        @Test
        @DisplayName("(D2) CI 없음 → 기본은 거부(IDO_IDENTITY_UNRESOLVED) — 세션 미발급, Q-IM 미호출")
        void resolve_noCi_shouldBeRejectedByDefault() {
            String identifierHash = "sha256-hash-of-sub-value";
            given(feSessionService.isValidReturnUrl(any())).willReturn(true);
            given(internalSigVerifier.verify(any(), any())).willReturn(true);

            OidcCompleteRequest req = buildRequestNoCi(identifierHash);
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertThatThrownBy(() -> controller.complete("valid-sig", "q-sign", "cid-003", req, response))
                    .isInstanceOf(PlatformException.class)
                    .satisfies(e -> assertThat(((PlatformException) e).getErrorCode())
                            .isEqualTo(PlatformErrorCode.IDO_IDENTITY_UNRESOLVED));
            verify(qimClient, never()).findByIdentifierHash(any(), any());
            verify(feSessionService, never()).create(any(), any(), any(), any());
        }

        @Test
        @DisplayName("(D2) ido.broker.allow-ciless-identity=true (로컬 전용) 일 때만 identifierHash 폴백")
        void resolve_noCi_fallbackOnlyWhenExplicitlyAllowed() {
            ReflectionTestUtils.setField(controller, "allowCilessIdentity", true);
            String identifierHash = "sha256-hash-of-sub-value";

            given(feSessionService.isValidReturnUrl(any())).willReturn(true);
            given(internalSigVerifier.verify(any(), any())).willReturn(true);
            given(feSessionService.create(eq(identifierHash), any(), any(), any()))
                    .willReturn(buildMockSession(identifierHash));

            OidcCompleteRequest req = buildRequestNoCi(identifierHash);
            controller.complete("valid-sig", "q-sign", "cid-003", req, new MockHttpServletResponse());

            verify(qimClient, never()).findByIdentifierHash(any(), any());
            verify(feSessionService, times(1)).create(eq(identifierHash), any(), any(), any());
        }

        @Test
        @DisplayName("(S8-a) 정식 계약 subjectScheme/subjectKey 로 조회 — ci 없이 EMAIL 스킴")
        void resolve_subjectSchemeContract() {
            String qimUserId = "qim-user-email-001";
            given(feSessionService.isValidReturnUrl(any())).willReturn(true);
            given(internalSigVerifier.verify(any(), any())).willReturn(true);
            given(qimClient.findByIdentifierHash(eq(SubjectScheme.EMAIL.identifierHash("alice@example.org")), anyString()))
                    .willReturn(Optional.of(QimMemberInfo.builder().qimUserId(qimUserId).status("ACTIVE").build()));
            given(feSessionService.create(eq(qimUserId), any(), any(), any()))
                    .willReturn(buildMockSession(qimUserId));
            OidcCompleteRequest req = buildRequestNoCi("sha256-hash-x");
            ReflectionTestUtils.setField(req, "subjectScheme", "email");
            ReflectionTestUtils.setField(req, "subjectKey", "alice@example.org");

            controller.complete("valid-sig", "q-sign", "cid-005", req, new MockHttpServletResponse());

            verify(qimClient).findByIdentifierHash(eq(SubjectScheme.EMAIL.identifierHash("alice@example.org")), anyString());
            verify(feSessionService).create(eq(qimUserId), any(), any(), any());
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

            OidcCompleteRequest req = buildRequest("any-ci");
            MockHttpServletResponse response = new MockHttpServletResponse();

            // when & then
            org.junit.jupiter.api.Assertions.assertThrows(
                    io.github.hipstermin.idem.common.error.PlatformException.class,
                    () -> controller.complete("invalid-sig", "q-sign", "cid-sig-fail", req, response)
            );

            // QimClient, FeSessionService는 호출되지 않아야 함
            verify(qimClient, never()).findByIdentifierHash(any(), any());
            verify(feSessionService, never()).create(any(), any(), any(), any());
        }

        @Test
        @DisplayName("[P1] X-Internal-Sig null이면 서명 검증 실패")
        void complete_nullSig_shouldThrowException() {
            // given
            given(internalSigVerifier.verify(isNull(), any())).willReturn(false);

            OidcCompleteRequest req = buildRequest("any-ci");
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

            OidcCompleteRequest req = buildRequest("any-ci");
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
     * CI(별칭) 포함 요청 DTO 생성 (Reflection 사용)
     */
    private OidcCompleteRequest buildRequest(String ci) {
        OidcCompleteRequest req = new OidcCompleteRequest();
        ReflectionTestUtils.setField(req, "authResultId",    "auth-result-001");
        ReflectionTestUtils.setField(req, "identifierHash",  "sha256-hash-001");
        ReflectionTestUtils.setField(req, "authLevel",       "L1");
        ReflectionTestUtils.setField(req, "providerCode",    "KAKAO_OIDC");
        ReflectionTestUtils.setField(req, "correlationId",   "cid-test");
        ReflectionTestUtils.setField(req, "returnUrl",       "http://localhost:8084/callback");
        ReflectionTestUtils.setField(req, "ci",              ci);
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

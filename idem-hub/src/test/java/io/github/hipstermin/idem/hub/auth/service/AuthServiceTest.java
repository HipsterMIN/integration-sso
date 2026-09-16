package io.github.hipstermin.idem.hub.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.hub.auth.audit.AuthAuditService;
import io.github.hipstermin.idem.hub.auth.client.IntegrationAuthClient;
import io.github.hipstermin.idem.hub.auth.dto.*;
import io.github.hipstermin.idem.hub.auth.dto.im.QimMemberInfo;
import io.github.hipstermin.idem.hub.auth.port.ImApiOutPort;
import io.github.hipstermin.idem.hub.qim.MemberDivisionPolicy;
import io.github.hipstermin.idem.hub.qim.crypto.AesSharedKeyDecryptor;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AuthService 단위 테스트
 *
 * <p>기업인증 콜백, OACX 간편서명, CI 확인 비즈니스 로직을 검증.
 * 외부 의존성(IntegrationAuthClient, OacxClient)은 Mockito Mock으로 대체.
 */
@DisplayName("AuthService — 본인인증 서비스 단위 테스트")
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private IntegrationAuthClient integrationAuthClient;

    @Mock
    private ImApiOutPort imApiOutPort;

    @Mock
    private AuthAuditService authAuditService;

    @Mock
    private AesSharedKeyDecryptor aesSharedKeyDecryptor;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(integrationAuthClient, new ObjectMapper(),
                imApiOutPort, authAuditService, aesSharedKeyDecryptor, MemberDivisionPolicy.defaults());
    }

    // ── callback 테스트 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("callback — 기업인증 콜백")
    class CallbackTest {

        @Test
        @DisplayName("통합인증 서버 응답이 null이면 5001 반환")
        void callback_shouldReturn5001WhenResponseIsNull() {
            // given
            AuthCallbackRequest request = AuthCallbackRequest.builder()
                    .txId("test-tx-id")
                    .build();
            given(integrationAuthClient.sendAuthCheck(any())).willReturn(null);

            // when
            AuthCallbackResponse response = authService.callback(request);

            // then
            assertThat(response.getResultCode()).isEqualTo("5001");
            assertThat(response.getResultMsg()).contains("응답 없음");
        }

        @Test
        @DisplayName("통합인증 서버 오류 시 5000 반환")
        void callback_shouldReturn5000OnException() {
            // given
            AuthCallbackRequest request = AuthCallbackRequest.builder().txId("tx-123").build();
            given(integrationAuthClient.sendAuthCheck(any()))
                    .willThrow(new RuntimeException("통합인증 서버 연결 오류"));

            // when
            AuthCallbackResponse response = authService.callback(request);

            // then
            assertThat(response.getResultCode()).isEqualTo("5000");
            assertThat(response.getResultMsg()).contains("오류");
        }

        @Test
        @DisplayName("성공 응답 시 resultData 없이도 resultCode를 그대로 전달")
        void callback_shouldReturnResultCodeFromServer() {
            // given
            AuthCallbackRequest request = AuthCallbackRequest.builder()
                    .txId("tx-success")
                    .siteInfo(AuthCallbackRequest.SiteInfo.builder().siteId("site-001").build())
                    .build();

            AuthCheckResponse serverResponse = AuthCheckResponse.builder()
                    .resultCode("2000")
                    .resultMsg("성공")
                    .resultData(null)  // resultData 없는 경우
                    .build();
            given(integrationAuthClient.sendAuthCheck(any())).willReturn(serverResponse);

            // when
            AuthCallbackResponse response = authService.callback(request);

            // then
            assertThat(response.getResultCode()).isEqualTo("2000");
            assertThat(response.getResultMsg()).isEqualTo("성공");
            assertThat(response.getResultData()).isNull();
        }
    }

    // ── handleOacxEasysign 테스트 ──────────────────────────────────────────────

    @Nested
    @DisplayName("checkNiceCi — NICE CI 회원 확인")
    class CheckNiceCiTest {

        @Test
        @DisplayName("CI 누락 시 4000 반환")
        void checkNiceCi_shouldReturn4000WhenCiIsNull() {
            // given
            CiCheckRequest request = CiCheckRequest.builder()
                    .ci(null)
                    .mbrDvsnCd("A101")
                    .build();

            // when
            CiCheckResponse response = authService.checkNiceCi(request);

            // then
            assertThat(response.getResultCode()).isEqualTo("4000");
            assertThat(response.getResultMsg()).contains("ci");
        }

        @Test
        @DisplayName("CI가 빈 문자열이면 4000 반환")
        void checkNiceCi_shouldReturn4000WhenCiIsBlank() {
            // given
            CiCheckRequest request = CiCheckRequest.builder()
                    .ci("   ")
                    .mbrDvsnCd("A101")
                    .build();

            // when
            CiCheckResponse response = authService.checkNiceCi(request);

            // then
            assertThat(response.getResultCode()).isEqualTo("4000");
        }

        @Test
        @DisplayName("잘못된 mbrDvsnCd (A101/A102 외) 시 4000 반환")
        void checkNiceCi_shouldReturn4000ForInvalidMbrDvsnCd() {
            // given
            CiCheckRequest request = CiCheckRequest.builder()
                    .ci("VALID_CI_VALUE_88_CHARS")
                    .mbrDvsnCd("A999")  // 잘못된 값
                    .build();

            // when
            CiCheckResponse response = authService.checkNiceCi(request);

            // then
            assertThat(response.getResultCode()).isEqualTo("4000");
            assertThat(response.getResultMsg()).contains("A101").contains("A102");
        }

        @Test
        @DisplayName("기업회원(A102) 요청에서 bizno 누락 시 4000 반환")
        void checkNiceCi_shouldReturn4000ForA102WithoutBizno() {
            // given
            CiCheckRequest request = CiCheckRequest.builder()
                    .ci("VALID_CI_VALUE_88_CHARS")
                    .mbrDvsnCd("A102")
                    .bizno(null)  // 기업회원 필수 누락
                    .build();

            // when
            CiCheckResponse response = authService.checkNiceCi(request);

            // then
            assertThat(response.getResultCode()).isEqualTo("4000");
            assertThat(response.getResultMsg()).contains("bizno");
        }

        @Test
        @DisplayName("개인회원(A101) 정상 요청 — Q-IM 미등록 사용자: 선행 인증 미완료로 4040 반환 (신규 등록 없음)")
        void checkNiceCi_shouldReturn4040ForUnregisteredA101() {
            // given
            CiCheckRequest request = CiCheckRequest.builder()
                    .ci("VALID_CI_VALUE_88_CHARS_LONG_STRING_FOR_TEST_PURPOSE")
                    .mbrDvsnCd("A101")
                    .indvlMbrNm("홍길동")
                    .indvlMbrId("hong123")
                    .build();
            // Q-IM에서 CI 미발견 → 선행 인증 미완료로 에러 반환 (신규 등록 없음)
            given(imApiOutPort.findByCi(anyString(), eq("A101"), anyString()))
                    .willReturn(Optional.empty());

            // when
            CiCheckResponse response = authService.checkNiceCi(request);

            // then: ci-check는 조회 전용 — 미등록 CI는 4040 반환 (신규 등록 시도 없음)
            assertThat(response.getResultCode()).isEqualTo("4040");
            assertThat(response.getResult()).isFalse();
            assertThat(response.getResultMsg()).contains("본인인증 이력이 없습니다");
            // register() 호출 없음 확인
            verify(imApiOutPort, never()).register(any(), anyString());
        }

        @Test
        @DisplayName("개인회원(A101) 정상 요청 — Q-IM 기존 회원 조회 후 indvlMbrId 반환")
        void checkNiceCi_shouldReturnIndvlMbrIdForExistingA101Member() {
            // given
            CiCheckRequest request = CiCheckRequest.builder()
                    .ci("VALID_CI_VALUE_88_CHARS_LONG_STRING_FOR_TEST_PURPOSE")
                    .mbrDvsnCd("A101")
                    .indvlMbrNm("홍길동")
                    .build();
            // Q-IM에서 기존 회원 발견
            given(imApiOutPort.findByCi(anyString(), eq("A101"), anyString()))
                    .willReturn(Optional.of(QimMemberInfo.builder()
                            .qimUserId("qim-existing-001")
                            .status("ACTIVE")
                            .memberType("A101")
                            .indvlMbrId("honggildong")
                            .build()));

            // when
            CiCheckResponse response = authService.checkNiceCi(request);

            // then
            assertThat(response.getResultCode()).isEqualTo("2000");
            assertThat(response.getResult()).isTrue();
            assertThat(response.getIndvlMbrId()).isEqualTo("honggildong");
            assertThat(response.getResultMsg()).contains("기존");
        }

        @Test
        @DisplayName("기업회원(A102) Q-IM 미등록 시 4040 반환 (신규 등록 없음)")
        void checkNiceCi_shouldReturn4040ForUnregisteredA102() {
            // given
            CiCheckRequest request = CiCheckRequest.builder()
                    .ci("VALID_CI_VALUE_88_CHARS_LONG_STRING_FOR_TEST_PURPOSE")
                    .mbrDvsnCd("A102")
                    .cmpMbrId("company-001")
                    .bizno("1234567890")
                    .build();
            // Q-IM에서 CI 미발견 → 선행 인증 미완료로 에러 반환
            given(imApiOutPort.findByCi(anyString(), eq("A102"), anyString()))
                    .willReturn(Optional.empty());

            // when
            CiCheckResponse response = authService.checkNiceCi(request);

            // then: ci-check는 조회 전용 — 미등록 CI는 4040 반환 (신규 등록 시도 없음)
            assertThat(response.getResultCode()).isEqualTo("4040");
            assertThat(response.getResult()).isFalse();
            // register() 호출 없음 확인
            verify(imApiOutPort, never()).register(any(), anyString());
        }

        @Test
        @DisplayName("기업회원(A102) Q-IM 기존 회원 조회 후 cmpMbrId 반환")
        void checkNiceCi_shouldReturnCmpMbrIdForExistingA102Member() {
            // given
            CiCheckRequest request = CiCheckRequest.builder()
                    .ci("VALID_CI_VALUE_88_CHARS_LONG_STRING_FOR_TEST_PURPOSE")
                    .mbrDvsnCd("A102")
                    .cmpMbrId("company-001")
                    .bizno("1234567890")
                    .build();
            // Q-IM에서 기존 기업 회원 발견
            given(imApiOutPort.findByCi(anyString(), eq("A102"), anyString()))
                    .willReturn(Optional.of(QimMemberInfo.builder()
                            .qimUserId("qim-existing-002")
                            .status("ACTIVE")
                            .memberType("A102")
                            .cmpMbrId("company-001")
                            .build()));

            // when
            CiCheckResponse response = authService.checkNiceCi(request);

            // then
            assertThat(response.getResultCode()).isEqualTo("2000");
            assertThat(response.getResult()).isTrue();
            assertThat(response.getCmpMbrId()).isEqualTo("company-001");
            assertThat(response.getResultMsg()).contains("기존");
        }
    }

    // ── getOacxAccessInfo 테스트 ──────────────────────────────────────────────

}

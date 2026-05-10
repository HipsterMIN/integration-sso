package kr.go.smes.ido.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.ido.auth.client.IntegrationAuthClient;
import kr.go.smes.ido.auth.client.OacxClient;
import kr.go.smes.ido.auth.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
    private OacxClient oacxClient;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(integrationAuthClient, oacxClient, new ObjectMapper());
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
    @DisplayName("handleOacxEasysign — OACX 간편서명 결과 처리")
    class OacxEasysignTest {

        @Test
        @DisplayName("fn이 authComplete가 아니면 4000 반환")
        void handleOacxEasysign_shouldReturn4000ForInvalidFn() {
            // given
            OacxEasysignRequest request = OacxEasysignRequest.builder()
                    .fn("unknownFunction")
                    .status("success")
                    .build();

            // when
            OacxEasysignResponse response = authService.handleOacxEasysign(request);

            // then
            assertThat(response.getResultCode()).isEqualTo("4000");
            assertThat(response.getResultMsg()).contains("authComplete");
            verify(oacxClient, never()).decryptEasysignResult(any());
        }

        @Test
        @DisplayName("OACX resultCode가 200이 아니면 4001 반환")
        void handleOacxEasysign_shouldReturn4001ForNon200OacxCode() {
            // given
            OacxEasysignRequest request = OacxEasysignRequest.builder()
                    .fn("authComplete")
                    .status("success")
                    .res(Map.of("resultCode", "400"))
                    .build();

            // when
            OacxEasysignResponse response = authService.handleOacxEasysign(request);

            // then
            assertThat(response.getResultCode()).isEqualTo("4001");
            assertThat(response.getResultMsg()).contains("인증 실패");
            verify(oacxClient, never()).decryptEasysignResult(any());
        }

        @Test
        @DisplayName("복호화 실패 시 5002 반환")
        void handleOacxEasysign_shouldReturn5002WhenDecryptFails() {
            // given
            OacxEasysignRequest request = OacxEasysignRequest.builder()
                    .fn("authComplete")
                    .status("success")
                    .res(Map.of("resultCode", "200", "encData", "some-jwt"))
                    .build();
            given(oacxClient.decryptEasysignResult(any()))
                    .willReturn(Map.of("status", "error", "message", "JWT 복호화 실패"));

            // when
            OacxEasysignResponse response = authService.handleOacxEasysign(request);

            // then
            assertThat(response.getResultCode()).isEqualTo("5002");
            assertThat(response.getResultMsg()).contains("복호화 실패");
        }

        @Test
        @DisplayName("성공 시 name, birthday, phone 반환 — CI 미포함 (Q3=B)")
        void handleOacxEasysign_shouldReturnUserInfoWithoutCi() {
            // given
            OacxEasysignRequest request = OacxEasysignRequest.builder()
                    .fn("authComplete")
                    .status("success")
                    .res(Map.of("resultCode", "200", "encData", "some-jwt"))
                    .build();
            // OACX SDK 복호화 결과에 CI 포함
            given(oacxClient.decryptEasysignResult(any())).willReturn(Map.of(
                    "status", "success",
                    "name", "홍길동",
                    "phone", "01012345678",
                    "birthday", "19900101",
                    "ci", "ABCDEF0123456789ABCDEF0123456789..."  // 88자 CI
            ));

            // when
            OacxEasysignResponse response = authService.handleOacxEasysign(request);

            // then: 성공 + 사용자 정보 포함
            assertThat(response.getResultCode()).isEqualTo("2000");
            assertThat(response.getName()).isEqualTo("홍길동");
            assertThat(response.getPhone()).isEqualTo("01012345678");
            assertThat(response.getBirthday()).isEqualTo("19900101");

            // ★ Q3=B: CI는 FE에 미반환 — null이어야 함
            assertThat(response.getCi()).isNull();
        }

        @Test
        @DisplayName("PASS 통신사 provider (userNm/phoneNo 키) 응답 정상 처리")
        void handleOacxEasysign_shouldHandlePassProviderKeys() {
            // given: PASS(통신3사) provider는 userNm, phoneNo 키 사용
            OacxEasysignRequest request = OacxEasysignRequest.builder()
                    .fn("authComplete")
                    .status("success")
                    .res(Map.of("resultCode", "200"))
                    .build();
            given(oacxClient.decryptEasysignResult(any())).willReturn(Map.of(
                    "status", "success",
                    "userNm", "김철수",   // PASS provider: userNm (name 아님)
                    "phoneNo", "01098765432",  // PASS provider: phoneNo (phone 아님)
                    "birthday", "19851215"
            ));

            // when
            OacxEasysignResponse response = authService.handleOacxEasysign(request);

            // then: userNm → name, phoneNo → phone 으로 정상 매핑
            assertThat(response.getResultCode()).isEqualTo("2000");
            assertThat(response.getName()).isEqualTo("김철수");
            assertThat(response.getPhone()).isEqualTo("01098765432");
        }
    }

    // ── checkNiceCi 테스트 ────────────────────────────────────────────────────

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
        @DisplayName("개인회원(A101) 정상 요청 시 2000 반환")
        void checkNiceCi_shouldReturn2000ForValidA101Request() {
            // given
            CiCheckRequest request = CiCheckRequest.builder()
                    .ci("VALID_CI_VALUE_88_CHARS_LONG_STRING_FOR_TEST_PURPOSE")
                    .mbrDvsnCd("A101")
                    .indvlMbrNm("홍길동")
                    .indvlMbrId("hong123")
                    .build();

            // when
            CiCheckResponse response = authService.checkNiceCi(request);

            // then
            assertThat(response.getResultCode()).isEqualTo("2000");
            assertThat(response.getResult()).isTrue();
        }

        @Test
        @DisplayName("기업회원(A102) 정상 요청 시 2000 반환 (bizno 포함)")
        void checkNiceCi_shouldReturn2000ForValidA102Request() {
            // given
            CiCheckRequest request = CiCheckRequest.builder()
                    .ci("VALID_CI_VALUE_88_CHARS_LONG_STRING_FOR_TEST_PURPOSE")
                    .mbrDvsnCd("A102")
                    .cmpMbrId("company-001")
                    .bizno("1234567890")
                    .build();

            // when
            CiCheckResponse response = authService.checkNiceCi(request);

            // then
            assertThat(response.getResultCode()).isEqualTo("2000");
            assertThat(response.getResult()).isTrue();
        }
    }

    // ── getOacxAccessInfo 테스트 ──────────────────────────────────────────────

    @Nested
    @DisplayName("getOacxAccessInfo — OACX 접근정보 발급")
    class GetOacxAccessInfoTest {

        @Test
        @DisplayName("OacxClient 결과를 그대로 반환해야 한다")
        void getOacxAccessInfo_shouldDelegateToOacxClient() {
            // given
            String fn = "simpleAuth";
            OacxAccessInfoResponse expected = OacxAccessInfoResponse.builder()
                    .resultCode("2000")
                    .resultMsg("성공")
                    .fn(fn)
                    .accKey("test-acc-key")
                    .accToken("test-acc-token")
                    .build();
            given(oacxClient.getAccessInfo(eq(fn))).willReturn(expected);

            // when
            OacxAccessInfoResponse response = authService.getOacxAccessInfo(fn);

            // then
            assertThat(response).isEqualTo(expected);
            verify(oacxClient).getAccessInfo(fn);
        }
    }
}

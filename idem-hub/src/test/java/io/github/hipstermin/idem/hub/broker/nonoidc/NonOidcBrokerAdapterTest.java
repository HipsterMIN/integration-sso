package io.github.hipstermin.idem.hub.broker.nonoidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** D2: 비OIDC 사업자 응답 검증 "항상 통과(PoC)" 제거 — 검증기 없으면 503, 검증 실패면 서명 불일치. */
@ExtendWith(MockitoExtension.class)
class NonOidcBrokerAdapterTest {

    @Mock NonOidcAuthService nonOidcAuthService;

    private static NonOidcProviderVerifier verifier(String code, boolean result) {
        return new NonOidcProviderVerifier() {
            @Override public boolean supports(String providerCode) { return code.equalsIgnoreCase(providerCode); }
            @Override public boolean verify(Map<String, Object> response, String providerCode, String correlationId) { return result; }
        };
    }

    @Test
    @DisplayName("검증기 미등록 사업자 — initiate 부터 IDEM_HUB_PROVIDER_NOT_CONFIGURED (막다른 인증 화면 방지)")
    void noVerifier_initiateRejected() {
        NonOidcBrokerAdapter sut = new NonOidcBrokerAdapter(nonOidcAuthService, List.of(), props());

        assertThatThrownBy(() -> sut.initiateAuth("PASS", "cid", "https://cb"))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode())
                        .isEqualTo(PlatformErrorCode.IDO_PROVIDER_NOT_CONFIGURED));
        verifyNoInteractions(nonOidcAuthService);
    }

    @Test
    @DisplayName("검증 실패 → IDP_SIGNATURE_MISMATCH, AuthResult 저장 없음")
    void verifierFalse_callbackRejected() {
        NonOidcBrokerAdapter sut = new NonOidcBrokerAdapter(nonOidcAuthService, List.of(verifier("PASS", false)), props());

        assertThatThrownBy(() -> sut.normalizeResponse("PASS", "cid", "tx", Map.of("identifier", "user-1")))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode())
                        .isEqualTo(PlatformErrorCode.IDP_SIGNATURE_MISMATCH));
        verifyNoInteractions(nonOidcAuthService);
    }

    @Test
    @DisplayName("검증 통과 → 정규화 진행 (AuthResult 저장)")
    void verifierTrue_proceeds() {
        NonOidcBrokerAdapter sut = new NonOidcBrokerAdapter(nonOidcAuthService, List.of(verifier("PASS", true)), props());
        given(nonOidcAuthService.processAuth(any())).willReturn("ar-1");

        var input = sut.normalizeResponse("PASS", "cid", "tx", Map.of("identifier", "user-1"));

        assertThat(input.getInternalSignature()).isEqualTo("ar-1");
        assertThat(input.isProviderVerified()).isTrue();
    }

    /** D3: 사업자는 설정이 정한다 — 테스트는 PASS 하나를 넣는다 */
    static NonOidcProviderProperties props() {
        NonOidcProviderProperties p = new NonOidcProviderProperties();
        NonOidcProviderProperties.Provider pass = new NonOidcProviderProperties.Provider();
        pass.setInitiateUrl("https://idp.example.org/auth?callback={callbackUrl}&cid={correlationId}");
        pass.setAuthLevel(io.github.hipstermin.idem.common.domain.AuthResult.AuthLevel.L2);
        p.getProviders().put("PASS", pass);
        return p;
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("[D3] 설정에 없는 사업자는 검증기가 있어도 시작을 거부한다 — 코어에 사업자 이름이 박혀 있지 않다")
    void unconfiguredProvider_rejected() {
        NonOidcBrokerAdapter sut = new NonOidcBrokerAdapter(nonOidcAuthService, List.of(verifier("GPKI", true)), props());
        assertThatThrownBy(() -> sut.initiateAuth("GPKI", "cid", "https://cb"))
                .isInstanceOf(io.github.hipstermin.idem.common.error.PlatformException.class)
                .extracting("errorCode").isEqualTo(io.github.hipstermin.idem.common.error.PlatformErrorCode.IDP_PROVIDER_UNAVAILABLE);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("[D3] 시작 URL 은 설정 템플릿에서 — {callbackUrl} 인코딩·{correlationId} 치환, 인증수준은 설정값")
    void initiateUrlFromTemplate() {
        NonOidcBrokerAdapter sut = new NonOidcBrokerAdapter(nonOidcAuthService, List.of(verifier("pass", true)), props());
        var r = sut.initiateAuth("pass", "cid-1234-5678", "https://cb/x?y=1");
        org.assertj.core.api.Assertions.assertThat(r.getRedirectUrl())
                .isEqualTo("https://idp.example.org/auth?callback=https%3A%2F%2Fcb%2Fx%3Fy%3D1&cid=cid-1234-5678");
        org.assertj.core.api.Assertions.assertThat(r.getProviderCode()).isEqualTo("PASS");
        org.assertj.core.api.Assertions.assertThat(r.getProviderTxId()).startsWith("PASS-TX-");
        var input = sut.normalizeResponse("PASS", "cid", "tx", Map.of("identifier", "user-1"));
        org.assertj.core.api.Assertions.assertThat(input.getRequestedAuthLevel()).isEqualTo(io.github.hipstermin.idem.common.domain.AuthResult.AuthLevel.L2);
    }
}

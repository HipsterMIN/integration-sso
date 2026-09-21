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
    @DisplayName("검증기 미등록 사업자 — initiate 부터 IDO_PROVIDER_NOT_CONFIGURED (막다른 인증 화면 방지)")
    void noVerifier_initiateRejected() {
        NonOidcBrokerAdapter sut = new NonOidcBrokerAdapter(nonOidcAuthService, List.of());

        assertThatThrownBy(() -> sut.initiateAuth("PASS", "cid", "https://cb"))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode())
                        .isEqualTo(PlatformErrorCode.IDO_PROVIDER_NOT_CONFIGURED));
        verifyNoInteractions(nonOidcAuthService);
    }

    @Test
    @DisplayName("검증 실패 → IDP_SIGNATURE_MISMATCH, AuthResult 저장 없음")
    void verifierFalse_callbackRejected() {
        NonOidcBrokerAdapter sut = new NonOidcBrokerAdapter(nonOidcAuthService, List.of(verifier("PASS", false)));

        assertThatThrownBy(() -> sut.normalizeResponse("PASS", "cid", "tx", Map.of("identifier", "user-1")))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode())
                        .isEqualTo(PlatformErrorCode.IDP_SIGNATURE_MISMATCH));
        verifyNoInteractions(nonOidcAuthService);
    }

    @Test
    @DisplayName("검증 통과 → 정규화 진행 (AuthResult 저장)")
    void verifierTrue_proceeds() {
        NonOidcBrokerAdapter sut = new NonOidcBrokerAdapter(nonOidcAuthService, List.of(verifier("PASS", true)));
        given(nonOidcAuthService.processAuth(any())).willReturn("ar-1");

        var input = sut.normalizeResponse("PASS", "cid", "tx", Map.of("identifier", "user-1"));

        assertThat(input.getInternalSignature()).isEqualTo("ar-1");
        assertThat(input.isProviderVerified()).isTrue();
    }
}

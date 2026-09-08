package io.github.hipstermin.idem.hub.auth.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationException;
import io.github.hipstermin.idem.common.spi.identity.VerificationCallback;
import io.github.hipstermin.idem.common.spi.identity.VerificationRequest;
import io.github.hipstermin.idem.common.spi.identity.VerificationStart;
import io.github.hipstermin.idem.common.spi.identity.VerifiedIdentity;
import io.github.hipstermin.idem.hub.auth.dto.NicePhoneAuthResultRequest;
import io.github.hipstermin.idem.hub.auth.dto.NicePhoneAuthResultResponse;
import io.github.hipstermin.idem.hub.auth.dto.NicePhoneAuthUrlResponse;
import io.github.hipstermin.idem.hub.auth.service.NiceAuthService;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NiceIdentityVerificationProviderTest {

    @Mock NiceAuthService niceAuthService;
    @InjectMocks NiceIdentityVerificationProvider provider;

    @Test
    @DisplayName("코드·등급: NICE_PHONE / L2, 위젯 없음")
    void descriptor() {
        assertThat(provider.code()).isEqualTo("NICE_PHONE");
        assertThat(provider.level()).isEqualTo(AuthResult.AuthLevel.L2);
        assertThat(provider.widget()).isEmpty();
    }

    @Test
    @DisplayName("initiate: NICE URL 발급 결과를 txId=requestNo, redirectUrl=authUrl 로 매핑")
    void initiate() {
        when(niceAuthService.getNicePhoneAuthUrl("https://fe/return")).thenReturn(
                NicePhoneAuthUrlResponse.builder().resultCode("0000").authUrl("https://nice/auth?x=1").requestNo("REQ-1").build());
        VerificationStart s = provider.initiate(new VerificationRequest("c", "https://fe/return", Map.of()));
        assertThat(s.txId()).isEqualTo("REQ-1");
        assertThat(s.redirectUrl()).isEqualTo("https://nice/auth?x=1");
        assertThat(s.params()).containsEntry("requestNo", "REQ-1");
    }

    @Test
    @DisplayName("initiate: authUrl 이 없으면 IdentityVerificationException(resultCode 전달)")
    void initiateFailure() {
        when(niceAuthService.getNicePhoneAuthUrl(any())).thenReturn(
                NicePhoneAuthUrlResponse.builder().resultCode("5000").resultMsg("NICE down").build());
        assertThatThrownBy(() -> provider.initiate(new VerificationRequest(null, null, Map.of())))
                .isInstanceOf(IdentityVerificationException.class)
                .extracting("reasonCode").isEqualTo("5000");
    }

    @Test
    @DisplayName("complete: web_transaction_id + txId 로 결과 조회 → DI 가 subjectKey")
    void complete() {
        when(niceAuthService.getNicePhoneAuthResult(any())).thenReturn(NicePhoneAuthResultResponse.builder()
                .resultCode("0000")
                .resultData(NicePhoneAuthResultResponse.ResultData.builder()
                        .name("홍길동").birthdate("19900101").gender("1").nationalInfo("0")
                        .di("DI-abc").mobileCo("1").mobileNo("01012345678").build())
                .build());
        VerifiedIdentity id = provider.complete(new VerificationCallback("NICE_PHONE", "REQ-1", "c",
                Map.of("web_transaction_id", "WTX-9")));

        ArgumentCaptor<NicePhoneAuthResultRequest> cap = ArgumentCaptor.forClass(NicePhoneAuthResultRequest.class);
        org.mockito.Mockito.verify(niceAuthService).getNicePhoneAuthResult(cap.capture());
        assertThat(cap.getValue().getWebTransactionId()).isEqualTo("WTX-9");
        assertThat(cap.getValue().getRequestNo()).isEqualTo("REQ-1");

        assertThat(id.subjectKey()).isEqualTo("DI-abc");
        assertThat(id.name()).isEqualTo("홍길동");
        assertThat(id.phone()).isEqualTo("01012345678");
        assertThat(id.phoneCarrier()).isEqualTo("1");
        assertThat(id.level()).isEqualTo(AuthResult.AuthLevel.L2);
        assertThat(id.attributes()).containsEntry("nationalInfo", "0");
    }

    @Test
    @DisplayName("complete: web_transaction_id 누락 → MISSING_PARAM, 결과 없음 → resultCode 전달")
    void completeFailures() {
        assertThatThrownBy(() -> provider.complete(new VerificationCallback("NICE_PHONE", "REQ-1", null, Map.of())))
                .isInstanceOf(IdentityVerificationException.class)
                .extracting("reasonCode").isEqualTo("MISSING_PARAM");

        when(niceAuthService.getNicePhoneAuthResult(any())).thenReturn(
                NicePhoneAuthResultResponse.builder().resultCode("4001").resultMsg("expired").build());
        assertThatThrownBy(() -> provider.complete(new VerificationCallback("NICE_PHONE", "REQ-1", null,
                Map.of("web_transaction_id", "WTX-9"))))
                .isInstanceOf(IdentityVerificationException.class)
                .extracting("reasonCode").isEqualTo("4001");
    }
}

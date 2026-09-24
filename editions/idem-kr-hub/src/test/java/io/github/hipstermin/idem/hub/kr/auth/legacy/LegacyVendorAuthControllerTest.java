package io.github.hipstermin.idem.hub.kr.auth.legacy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
import io.github.hipstermin.idem.common.spi.identity.IdentityProviderRegistry;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationException;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationProvider;
import io.github.hipstermin.idem.common.spi.identity.VerificationCallback;
import io.github.hipstermin.idem.common.spi.identity.VerificationRequest;
import io.github.hipstermin.idem.common.spi.identity.VerificationStart;
import io.github.hipstermin.idem.common.spi.identity.VerifiedIdentity;
import io.github.hipstermin.idem.hub.api.GlobalExceptionHandler;
import io.github.hipstermin.idem.hub.identity.SubjectRegistrationService;
import io.github.hipstermin.idem.hub.identity.audit.AuthAuditService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** S5a — 벤더 경로는 코어에 벤더 클래스 없이 SPI 제공자에 위임하고 종전 결과 코드를 지킨다. */
@DisplayName("LegacyVendorAuthController — 벤더 경로 → SPI 위임 (deprecated 프록시)")
class LegacyVendorAuthControllerTest {

    /** NICE_PHONE 흉내: initiate 는 성공, complete 는 web_transaction_id 가 "bad" 면 5003, 아니면 CI 스킴 결과 */
    static class FakeNice implements IdentityVerificationProvider {
        @Override public String code() { return "NICE_PHONE"; }
        @Override public AuthResult.AuthLevel level() { return AuthResult.AuthLevel.L2; }
        @Override public VerificationStart initiate(VerificationRequest r) {
            return new VerificationStart("NICE_PHONE", "REQ-1", "https://nice/auth", Map.of("requestNo", "REQ-1"));
        }
        @Override public VerifiedIdentity complete(VerificationCallback c) {
            if ("bad".equals(c.param("web_transaction_id"))) throw new IdentityVerificationException("NICE_PHONE", "5003", "무결성 실패");
            return new VerifiedIdentity("NICE_PHONE", c.txId(), "CI-1", "홍길동", "19900101", "1", "01012345678", "1",
                    AuthResult.AuthLevel.L2, Instant.EPOCH, Map.of("di", "DI-1", "nationalInfo", "0"), SubjectScheme.CI);
        }
    }

    private MockMvc mvc(List<IdentityVerificationProvider> providers, SubjectRegistrationService reg) {
        return MockMvcBuilders.standaloneSetup(new LegacyVendorAuthController(
                        new IdentityProviderRegistry(providers), reg, mock(AuthAuditService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("NICE URL: 제공자 initiate 결과를 종전 응답 형식(2000·authUrl·requestNo)으로")
    void niceUrl_delegates() throws Exception {
        mvc(List.of(new FakeNice()), mock(SubjectRegistrationService.class))
                .perform(get("/api/v1/auth/nice/phone/url").param("returnUrl", "https://fe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("2000"))
                .andExpect(jsonPath("$.authUrl").value("https://nice/auth"))
                .andExpect(jsonPath("$.requestNo").value("REQ-1"));
    }

    @Test
    @DisplayName("NICE 결과: complete + registry 등록 → 2000, resultData 에 di 는 있고 CI 는 없다; 제공자 오류는 그 코드로")
    void niceResult_registersAndHidesCi() throws Exception {
        SubjectRegistrationService reg = mock(SubjectRegistrationService.class);
        when(reg.register(any(), anyString())).thenReturn(new SubjectRegistrationService.Result("qim-1", true));
        MockMvc mvc = mvc(List.of(new FakeNice()), reg);

        mvc.perform(post("/api/v1/auth/nice/phone/result").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"web_transaction_id\":\"W\",\"request_no\":\"REQ-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("2000"))
                .andExpect(jsonPath("$.resultData.di").value("DI-1"))
                .andExpect(jsonPath("$.resultData.name").value("홍길동"))
                .andExpect(jsonPath("$.resultData.ci").doesNotExist());

        mvc.perform(post("/api/v1/auth/nice/phone/result").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"web_transaction_id\":\"bad\",\"request_no\":\"REQ-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value("5003"));
    }

    @Test
    @DisplayName("제공자가 없으면(플러그인 꺼짐) 5001; OACX easysign 의 잘못된 fn 은 제공자 유무와 무관하게 200/4000 (k6 계약)")
    void providerMissing_5001_andOacxFnValidation() throws Exception {
        MockMvc mvc = mvc(List.of(), mock(SubjectRegistrationService.class));
        mvc.perform(get("/api/v1/auth/nice/phone/url"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultCode").value("5001"));
        mvc.perform(post("/api/v1/auth/oacx/access-info").contentType(MediaType.TEXT_PLAIN).content("simpleAuth"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultCode").value("5001"));
        mvc.perform(post("/api/v1/auth/oacx/easysign").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fn\":\"INVALID\",\"status\":\"success\",\"res\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultCode").value("4000"));
        mvc.perform(post("/api/v1/auth/oacx/easysign").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fn\":\"authComplete\",\"status\":\"success\",\"res\":{\"resultCode\":\"200\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultCode").value("5001"));
        // Bean Validation 은 종전대로 400
        mvc.perform(post("/api/v1/auth/oacx/easysign").contentType(MediaType.APPLICATION_JSON).content("{\"fn\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}

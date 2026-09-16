package io.github.hipstermin.idem.plugin.anyid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.spi.broker.BrokerAuthCompletion;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("AnyIdController — /api/v1/anyid/* (플러그인)")
class AnyIdControllerTest {

    private AnyIdBrokerAdapter adapter;
    private BrokerAuthCompletion completion;
    private SsobDecryptor decryptor;
    private ObjectProvider<SsobDecryptor> decryptorProvider;
    private AnyIdProperties props;
    private MockMvc mvc;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        adapter = mock(AnyIdBrokerAdapter.class);
        completion = mock(BrokerAuthCompletion.class);
        decryptor = mock(SsobDecryptor.class);
        decryptorProvider = mock(ObjectProvider.class);
        when(decryptorProvider.getIfAvailable()).thenReturn(decryptor);
        props = new AnyIdProperties();
        props.setSrvcNo("SRVC-TEST");
        props.setAgencyCode("AG-TEST");
        props.setAgencyName("테스트기관");
        mvc = MockMvcBuilders.standaloneSetup(
                new AnyIdController(adapter, decryptorProvider, completion, props, new ObjectMapper())).build();
    }

    @Test
    @DisplayName("POST /txId → yyyyMMddHHmmss-{8자리}")
    void issueTxId() throws Exception {
        mvc.perform(post("/api/v1/anyid/txId"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txId").value(matchesPattern("\\d{14}-[0-9a-f]{8}")));
    }

    @Test
    @DisplayName("GET /{provider}/initiate → 어댑터가 준 URL 로 302")
    void initiate() throws Exception {
        when(adapter.initiateAuth(any(), any(), any(), any())).thenReturn("https://ui.example.test/mid");

        mvc.perform(get("/api/v1/anyid/mobile-id/initiate").param("returnUrl", "https://svc/done"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://ui.example.test/mid"));
    }

    @Test
    @DisplayName("GET /config·/health 는 비밀값을 내보내지 않는다")
    void configAndHealthExposeNoSecrets() throws Exception {
        props.getSso().setSecretCode("s3cr3t");
        props.getKms().setAppKey("appk3y");
        props.getPid().setClientSecret("pids3cr3t");

        String config = mvc.perform(get("/api/v1/anyid/config")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String health = mvc.perform(get("/api/v1/anyid/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sdk").value("present"))
                .andReturn().getResponse().getContentAsString();

        assertThat(config + health).doesNotContain("s3cr3t").doesNotContain("appk3y").doesNotContain("pids3cr3t");
    }

    @Nested
    @DisplayName("POST /ssob (FE 호환 통합 엔드포인트)")
    class UnifiedSsob {

        @Test
        @DisplayName("복호화 성공 → 코어 완료 포트 호출, 2000 + fe_session 쿠키, ci 는 authResultId")
        void success() throws Exception {
            when(decryptor.decrypt("SSOB", "TX-1")).thenReturn(Map.of("ci", "CI-RAW", "authLvl", "2", "name", "홍길동"));
            when(completion.complete(any())).thenReturn(new BrokerAuthCompletion.Result("AR-1", "FS-1"));

            mvc.perform(post("/api/v1/anyid/ssob").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Correlation-Id", "cid-9")
                            .content("{\"ssob\":\"SSOB\",\"tag\":\"TX-1\",\"userSeCd\":\"01\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resultCode").value("2000"))
                    .andExpect(jsonPath("$.ci").value("AR-1"))
                    .andExpect(jsonPath("$.authLevel").value("L2"))
                    .andExpect(jsonPath("$.name").value("홍길동"))
                    .andExpect(header().string("Set-Cookie", containsString("fe_session=FS-1")));

            ArgumentCaptor<BrokerAuthCompletion.Command> cmd = ArgumentCaptor.forClass(BrokerAuthCompletion.Command.class);
            verify(completion).complete(cmd.capture());
            assertThat(cmd.getValue().correlationId()).isEqualTo("cid-9");
            assertThat(cmd.getValue().providerCode()).isEqualTo("MOBILE_ID");   // userSeCd 01
            assertThat(cmd.getValue().providerTxId()).isEqualTo("TX-1");
            assertThat(cmd.getValue().rawIdentifier()).isEqualTo("CI-RAW");
            assertThat(cmd.getValue().authLevel()).isEqualTo("L2");
        }

        @Test
        @DisplayName("ssob 없음 → 400 / 5001")
        void missingSsob() throws Exception {
            mvc.perform(post("/api/v1/anyid/ssob").contentType(MediaType.APPLICATION_JSON).content("{\"tag\":\"TX-1\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.resultCode").value("5001"))
                    .andExpect(jsonPath("$.errorCode").value("MISSING_SSOB"));
        }

        @Test
        @DisplayName("SDK 미탑재(SsobDecryptor 빈 없음) → 503 / ANYID_SDK_UNAVAILABLE")
        void sdkUnavailable() throws Exception {
            when(decryptorProvider.getIfAvailable()).thenReturn(null);

            mvc.perform(post("/api/v1/anyid/ssob").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"ssob\":\"SSOB\",\"tag\":\"TX-1\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.errorCode").value("ANYID_SDK_UNAVAILABLE"));
        }

        @Test
        @DisplayName("복호화 실패(PlatformException) → 400 / 5000 + 오류 코드")
        void decryptFails() throws Exception {
            when(decryptor.decrypt(any(), any()))
                    .thenThrow(new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, "cid", "복호화 실패"));

            mvc.perform(post("/api/v1/anyid/ssob").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"ssob\":\"SSOB\",\"tag\":\"TX-1\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.resultCode").value("5000"))
                    .andExpect(jsonPath("$.errorCode").value("IDP_RESPONSE_INVALID"));
        }
    }

    @Test
    @DisplayName("POST /{provider}/ssob → status=success, provider 정규화, 쿠키")
    void providerSsob() throws Exception {
        when(decryptor.decrypt("SSOB", "TX-2")).thenReturn(Map.of("ci", "CI-RAW", "authLvl", "3"));
        when(completion.complete(any())).thenReturn(new BrokerAuthCompletion.Result("AR-2", "FS-2"));

        mvc.perform(post("/api/v1/anyid/financial-cert/ssob").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ssob\":\"SSOB\",\"tag\":\"TX-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.provider").value("FINANCIAL_CERT"))
                .andExpect(jsonPath("$.authLevel").value("L3"))
                .andExpect(jsonPath("$.authResultId").value("AR-2"))
                .andExpect(cookie().value("fe_session", "FS-2"));
    }

    @Test
    @DisplayName("GET /{provider}/callback: txId 없음 → /error 로 302, error 파라미터 → ANYID_AUTH_FAILED")
    void callbackErrors() throws Exception {
        mvc.perform(get("/api/v1/anyid/easy-sign/callback"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("code=MISSING_TX_ID")));
        mvc.perform(get("/api/v1/anyid/easy-sign/callback").param("error", "denied"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("code=ANYID_AUTH_FAILED")));
    }
}

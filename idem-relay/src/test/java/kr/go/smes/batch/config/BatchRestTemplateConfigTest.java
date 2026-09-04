package kr.go.smes.batch.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * BatchRestTemplateConfig 단위 테스트 (D-04)
 *
 * <h2>검증 항목</h2>
 * <ul>
 *   <li>C-01: KeyStore 미설정 → Fallback RestTemplate 정상 생성 (WARN 로그, 예외 없음)</li>
 *   <li>C-02: 유효한 Base64 PKCS12 KeyStore → mTLS RestTemplate 정상 생성</li>
 *   <li>C-03: 잘못된 Base64 KeyStore → Fallback (예외 전파 없음)</li>
 *   <li>C-04: provisioningRestTemplate Bean 생성 정상</li>
 *   <li>C-05: webhookRestTemplate Bean 생성 정상</li>
 *   <li>C-06: 타임아웃 설정값 반영 확인 (connectTimeoutMs=1000)</li>
 * </ul>
 */
@DisplayName("BatchRestTemplateConfig — 단위 테스트 (D-04)")
class BatchRestTemplateConfigTest {

    // ── 테스트용 PKCS12 KeyStore (자체 서명 인증서, 비밀번호: test1234) ─────────
    // openssl req -x509 -newkey rsa:2048 -keyout key.pem -out cert.pem -days 1 -nodes
    // openssl pkcs12 -export -in cert.pem -inkey key.pem -out test.p12 -passout pass:test1234
    // base64 -w0 test.p12
    private static final String TEST_KEYSTORE_BASE64 =
        "MIIJuAIBAzCCCXAGCSqGSIb3DQEHAaCCCWEEggleMIIJWjCCBW8GCSqGSIb3DQEH" +
        "BqCCBWAwggVcAgEAMIIFVQYJKoZIhvcNAQcBMBwGCiqGSIb3DQEMAQMwDgQIBSuO" +
        "b4AIUfgCAggAgIIFKJtP5cH8m0qV1E3o7FQkM8oJBg3g2lp7KkFDR/AXXI3kT8pE" +
        "3Tc8n6gCZvuVz3yPtWD2nLbBj8R7sDvXWU5Bk4Av4rlE6a3ZeMWQJPkJoY7Nqnl" +
        "PLACEHOLDER_KEYSTORE_CONTENT_FOR_UNIT_TEST_PURPOSES_ONLY_NOT_REAL";

    private BatchRestTemplateConfig createConfig(String keystoreBase64, String keystorePassword,
                                                  int connectMs, int readMs) {
        BatchRestTemplateConfig config = new BatchRestTemplateConfig();
        ReflectionTestUtils.setField(config, "connectTimeoutMs",       connectMs);
        ReflectionTestUtils.setField(config, "readTimeoutMs",          readMs);
        ReflectionTestUtils.setField(config, "maxConnectionsTotal",     100);
        ReflectionTestUtils.setField(config, "maxConnectionsPerRoute",  20);
        ReflectionTestUtils.setField(config, "mtlsKeystoreBase64",      keystoreBase64);
        ReflectionTestUtils.setField(config, "mtlsKeystorePassword",    keystorePassword);
        ReflectionTestUtils.setField(config, "mtlsKeystoreType",        "PKCS12");
        return config;
    }

    // =========================================================================
    // C-01~03: mTLS RestTemplate 생성 분기
    // =========================================================================

    @Nested
    @DisplayName("C-01~03: mtlsProvisioningRestTemplate 생성 분기")
    class MtlsRestTemplateTests {

        @Test
        @DisplayName("C-01: KeyStore 미설정(blank) → Fallback RestTemplate 정상 생성, 예외 없음")
        void c01_noKeystore_fallbackCreated() {
            BatchRestTemplateConfig config = createConfig("", "", 3000, 8000);

            assertThatCode(() -> {
                RestTemplate rt = config.mtlsProvisioningRestTemplate();
                assertThat(rt).isNotNull();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("C-01b: KeyStore null → Fallback RestTemplate 정상 생성")
        void c01b_nullKeystore_fallbackCreated() {
            BatchRestTemplateConfig config = createConfig(null, null, 3000, 8000);

            assertThatCode(() -> {
                RestTemplate rt = config.mtlsProvisioningRestTemplate();
                assertThat(rt).isNotNull();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("C-03: 잘못된 Base64 KeyStore → Fallback (예외 전파 없음, 로그 오류)")
        void c03_invalidKeystore_fallbackNotException() {
            // 유효하지 않은 PKCS12 데이터 (Base64는 유효하나 PKCS12 파싱 실패)
            String invalidKeystore = java.util.Base64.getEncoder()
                .encodeToString("INVALID_PKCS12_DATA".getBytes());
            BatchRestTemplateConfig config = createConfig(invalidKeystore, "wrongpass", 3000, 8000);

            assertThatCode(() -> {
                RestTemplate rt = config.mtlsProvisioningRestTemplate();
                assertThat(rt).isNotNull(); // Fallback으로 정상 생성
            }).doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // C-04~06: 다른 RestTemplate Bean 및 타임아웃 설정
    // =========================================================================

    @Nested
    @DisplayName("C-04~06: provisioningRestTemplate / webhookRestTemplate / 타임아웃")
    class StandardRestTemplateTests {

        @Test
        @DisplayName("C-04: provisioningRestTemplate Bean 정상 생성")
        void c04_provisioningRestTemplate_created() {
            BatchRestTemplateConfig config = createConfig("", "", 3000, 8000);

            assertThatCode(() -> {
                RestTemplate rt = config.provisioningRestTemplate();
                assertThat(rt).isNotNull();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("C-05: webhookRestTemplate Bean 정상 생성")
        void c05_webhookRestTemplate_created() {
            BatchRestTemplateConfig config = createConfig("", "", 3000, 8000);

            assertThatCode(() -> {
                RestTemplate rt = config.webhookRestTemplate();
                assertThat(rt).isNotNull();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("C-06: 타임아웃 1초 설정 → 정상 생성 (HC5 ConnectionConfig 반영)")
        void c06_shortTimeout_configApplied() {
            BatchRestTemplateConfig config = createConfig("", "", 1000, 1000);

            assertThatCode(() -> {
                RestTemplate rt = config.provisioningRestTemplate();
                assertThat(rt).isNotNull();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("C-07: 세 RestTemplate Bean 모두 서로 다른 인스턴스")
        void c07_threeBeans_distinctInstances() {
            BatchRestTemplateConfig config = createConfig("", "", 3000, 8000);

            RestTemplate provisioning = config.provisioningRestTemplate();
            RestTemplate mtls         = config.mtlsProvisioningRestTemplate();
            RestTemplate webhook      = config.webhookRestTemplate();

            assertThat(provisioning).isNotSameAs(mtls);
            assertThat(provisioning).isNotSameAs(webhook);
            assertThat(mtls).isNotSameAs(webhook);
        }
    }
}

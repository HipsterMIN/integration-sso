package kr.go.smes.ido.crypto.kms;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * VaultKmsClient 단위 테스트 — Vault REST API Mocking
 *
 * <p>실제 Vault 서버 없이 RestTemplate을 Mocking하여
 * Transit encrypt/decrypt API 호출 흐름을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VaultKmsClient — Vault Transit Engine 단위 테스트")
class VaultKmsClientTest {

    @Mock
    private RestTemplate restTemplate;

    private VaultKmsClient client;

    private static final String VAULT_ADDR    = "http://vault:8200";
    private static final String TRANSIT_PATH  = "transit";
    private static final String KEY_NAME      = "ido-handoff-key";
    private static final String TEST_TOKEN    = "test-vault-token";

    @BeforeEach
    void setUp() {
        client = new VaultKmsClient(new ObjectMapper());

        // 필드 직접 주입 (Spring Context 없이 단위 테스트)
        ReflectionTestUtils.setField(client, "vaultAddress",    VAULT_ADDR);
        ReflectionTestUtils.setField(client, "transitPath",     TRANSIT_PATH);
        ReflectionTestUtils.setField(client, "keyName",         KEY_NAME);
        ReflectionTestUtils.setField(client, "authMethod",      "token");
        ReflectionTestUtils.setField(client, "staticToken",     TEST_TOKEN);
        ReflectionTestUtils.setField(client, "roleId",          "");
        ReflectionTestUtils.setField(client, "secretId",        "");
        ReflectionTestUtils.setField(client, "k8sRole",         "ido");
        ReflectionTestUtils.setField(client, "k8sSaTokenPath",  "/nonexistent/sa/token");
        ReflectionTestUtils.setField(client, "vaultNamespace",  "");
        ReflectionTestUtils.setField(client, "connectionTimeoutMs", 3000);
        ReflectionTestUtils.setField(client, "requestTimeoutMs",    5000);
        ReflectionTestUtils.setField(client, "restTemplate",    restTemplate);
        ReflectionTestUtils.setField(client, "clientToken",     TEST_TOKEN);
    }

    // ══════════════════════════════════════════════════════════════════════
    // encrypt
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("encrypt — Transit 암호화 API")
    class Encrypt {

        @Test
        @DisplayName("32바이트 DEK 암호화 성공 → vault:v1:... ciphertext 반환")
        void encryptSuccess() {
            // given
            byte[] dek = new byte[32];
            new SecureRandom().nextBytes(dek);
            String expectedCiphertext = "vault:v1:ABCdef1234567890==";

            String vaultResponse = "{\"data\":{\"ciphertext\":\"" + expectedCiphertext + "\"}}";
            when(restTemplate.exchange(
                contains("/encrypt/"), eq(org.springframework.http.HttpMethod.POST),
                any(), eq(String.class))
            ).thenReturn(ResponseEntity.ok(vaultResponse));

            // when
            String result = client.encrypt(dek);

            // then
            assertThat(result).isEqualTo(expectedCiphertext);
        }

        @Test
        @DisplayName("Vault 요청 body에 plaintext(Base64)가 포함되어야 함")
        void encryptRequestBodyContainsBase64Plaintext() {
            // given
            byte[] dek = "test-32-bytes-key-for-aes256!!!".getBytes();
            String expectedBase64 = Base64.getEncoder().encodeToString(dek);
            String vaultResponse = "{\"data\":{\"ciphertext\":\"vault:v1:test\"}}";

            ArgumentCaptor<org.springframework.http.HttpEntity> bodyCaptor =
                ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);

            when(restTemplate.exchange(anyString(), any(), bodyCaptor.capture(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(vaultResponse));

            // when
            client.encrypt(dek);

            // then
            String requestBody = bodyCaptor.getValue().getBody().toString();
            assertThat(requestBody)
                .contains("plaintext")
                .contains(expectedBase64);
        }

        @Test
        @DisplayName("Vault 응답에 ciphertext 없으면 KmsEncryptException 발생")
        void encryptEmptyCiphertextThrowsException() {
            // given
            byte[] dek = new byte[32];
            String vaultResponse = "{\"data\":{\"ciphertext\":\"\"}}";

            when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(vaultResponse));

            // when / then
            assertThatThrownBy(() -> client.encrypt(dek))
                .isInstanceOf(KmsClient.KmsEncryptException.class)
                .hasMessageContaining("ciphertext 없음");
        }

        @Test
        @DisplayName("Vault 통신 오류 시 KmsEncryptException 발생")
        void encryptNetworkErrorThrowsException() {
            // given
            byte[] dek = new byte[32];
            when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenThrow(new RuntimeException("Connection refused"));

            // when / then
            assertThatThrownBy(() -> client.encrypt(dek))
                .isInstanceOf(KmsClient.KmsEncryptException.class)
                .hasMessageContaining("통신 오류");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // decrypt
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("decrypt — Transit 복호화 API")
    class Decrypt {

        @Test
        @DisplayName("vault:v1:... ciphertext 복호화 성공 → 32바이트 DEK 반환")
        void decryptSuccess() {
            // given
            byte[] originalDek = new byte[32];
            new SecureRandom().nextBytes(originalDek);
            String plainBase64 = Base64.getEncoder().encodeToString(originalDek);
            String ciphertext  = "vault:v1:ABCdef1234";

            String vaultResponse = "{\"data\":{\"plaintext\":\"" + plainBase64 + "\"}}";
            when(restTemplate.exchange(
                contains("/decrypt/"), eq(org.springframework.http.HttpMethod.POST),
                any(), eq(String.class))
            ).thenReturn(ResponseEntity.ok(vaultResponse));

            // when
            byte[] result = client.decrypt(ciphertext);

            // then
            assertThat(result).isEqualTo(originalDek);
        }

        @Test
        @DisplayName("Vault 요청 body에 ciphertext가 포함되어야 함")
        void decryptRequestBodyContainsCiphertext() {
            // given
            byte[] dek = new byte[32];
            String ciphertext = "vault:v1:SomeLongCipherText";
            String plainBase64 = Base64.getEncoder().encodeToString(dek);
            String vaultResponse = "{\"data\":{\"plaintext\":\"" + plainBase64 + "\"}}";

            ArgumentCaptor<org.springframework.http.HttpEntity> bodyCaptor =
                ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);

            when(restTemplate.exchange(anyString(), any(), bodyCaptor.capture(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(vaultResponse));

            // when
            client.decrypt(ciphertext);

            // then
            String requestBody = bodyCaptor.getValue().getBody().toString();
            assertThat(requestBody)
                .contains("ciphertext")
                .contains(ciphertext);
        }

        @Test
        @DisplayName("Vault 응답에 plaintext 없으면 KmsDecryptException 발생")
        void decryptEmptyPlaintextThrowsException() {
            // given
            String vaultResponse = "{\"data\":{\"plaintext\":\"\"}}";
            when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(vaultResponse));

            // when / then
            assertThatThrownBy(() -> client.decrypt("vault:v1:test"))
                .isInstanceOf(KmsClient.KmsDecryptException.class)
                .hasMessageContaining("plaintext 없음");
        }

        @Test
        @DisplayName("ciphertext가 null이면 KmsDecryptException 발생")
        void decryptNullCiphertextThrowsException() {
            // when / then
            assertThatThrownBy(() -> client.decrypt(null))
                .isInstanceOf(KmsClient.KmsDecryptException.class);
        }

        @Test
        @DisplayName("ciphertext가 빈 문자열이면 KmsDecryptException 발생")
        void decryptBlankCiphertextThrowsException() {
            // when / then
            assertThatThrownBy(() -> client.decrypt(""))
                .isInstanceOf(KmsClient.KmsDecryptException.class)
                .hasMessageContaining("null");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // isHealthy
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("isHealthy — Vault 헬스체크")
    class HealthCheck {

        @Test
        @DisplayName("HTTP 200 응답 시 healthy=true")
        void healthyWhenHttp200() {
            when(restTemplate.getForEntity(contains("/v1/sys/health"), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

            assertThat(client.isHealthy()).isTrue();
        }

        @Test
        @DisplayName("HTTP 429 응답(Standby) 시 healthy=true (읽기 가능 상태)")
        void healthyWhenHttp429Standby() {
            when(restTemplate.getForEntity(contains("/v1/sys/health"), eq(String.class)))
                .thenReturn(ResponseEntity.status(429).body("{}"));

            assertThat(client.isHealthy()).isTrue();
        }

        @Test
        @DisplayName("HTTP 500 응답 시 healthy=false")
        void unhealthyWhenHttp500() {
            when(restTemplate.getForEntity(contains("/v1/sys/health"), eq(String.class)))
                .thenReturn(ResponseEntity.internalServerError().body("{}"));

            assertThat(client.isHealthy()).isFalse();
        }

        @Test
        @DisplayName("네트워크 오류 시 healthy=false (예외 전파 없음)")
        void unhealthyWhenNetworkError() {
            when(restTemplate.getForEntity(contains("/v1/sys/health"), eq(String.class)))
                .thenThrow(new RuntimeException("Connection refused"));

            assertThat(client.isHealthy()).isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 메타데이터
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("providerName = 'vault'")
    void providerName() {
        assertThat(client.providerName()).isEqualTo("vault");
    }

    @Test
    @DisplayName("X-Vault-Token 헤더가 모든 Transit 요청에 포함되어야 함")
    void vaultTokenHeaderIncluded() {
        // given
        String vaultResponse = "{\"data\":{\"ciphertext\":\"vault:v1:test\"}}";
        ArgumentCaptor<org.springframework.http.HttpEntity> captor =
            ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
        when(restTemplate.exchange(anyString(), any(), captor.capture(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(vaultResponse));

        // when
        client.encrypt(new byte[32]);

        // then
        org.springframework.http.HttpHeaders headers = captor.getValue().getHeaders();
        assertThat(headers.get("X-Vault-Token"))
            .describedAs("X-Vault-Token 헤더 포함 필수")
            .isNotNull()
            .contains(TEST_TOKEN);
    }
}

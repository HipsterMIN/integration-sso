package kr.go.smes.ido.crypto.kms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

/**
 * LocalKmsClient 단위 테스트
 *
 * <p>KMS Off 모드(IDO_KMS_ENABLED=false) 동작 검증:
 * <ul>
 *   <li>encrypt: Base64 인코딩만 수행</li>
 *   <li>decrypt: Base64 디코딩만 수행 (encrypt와 라운드트립 검증)</li>
 *   <li>isHealthy: 항상 true</li>
 *   <li>providerName: "local"</li>
 * </ul>
 */
@DisplayName("LocalKmsClient — KMS Off 모드 단위 테스트")
class LocalKmsClientTest {

    private LocalKmsClient client;

    @BeforeEach
    void setUp() {
        client = new LocalKmsClient();
    }

    // ══════════════════════════════════════════════════════════════════════
    // encrypt / decrypt 라운드트립
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("encrypt → decrypt 라운드트립")
    class RoundTrip {

        @Test
        @DisplayName("AES-256 키 32바이트 라운드트립 성공")
        void aes256RoundTrip() {
            // given
            byte[] originalKey = new byte[32];
            new SecureRandom().nextBytes(originalKey);

            // when
            String encrypted = client.encrypt(originalKey);
            byte[] decrypted = client.decrypt(encrypted);

            // then
            assertThat(decrypted).isEqualTo(originalKey);
        }

        @Test
        @DisplayName("HMAC-SHA256 키 32바이트 라운드트립 성공")
        void hmac256RoundTrip() {
            // given
            byte[] originalKey = new byte[32];
            new SecureRandom().nextBytes(originalKey);

            // when
            String encrypted = client.encrypt(originalKey);
            byte[] decrypted = client.decrypt(encrypted);

            // then
            assertThat(decrypted)
                .describedAs("encrypt() → decrypt() 결과가 원본과 일치해야 함")
                .isEqualTo(originalKey);
        }

        @Test
        @DisplayName("encrypt 결과는 유효한 Base64 문자열이어야 함")
        void encryptReturnsBase64() {
            // given
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);

            // when
            String encrypted = client.encrypt(key);

            // then
            assertThatCode(() -> Base64.getDecoder().decode(encrypted))
                .describedAs("encrypt() 결과는 Base64 디코딩 가능해야 함")
                .doesNotThrowAnyException();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // decrypt — 입력 형식 허용성
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("decrypt — Base64 입력 유연성")
    class DecryptFormats {

        @Test
        @DisplayName("표준 Base64 (+ /) 디코딩 성공")
        void standardBase64() {
            // given
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            String standard = Base64.getEncoder().encodeToString(key);

            // when / then
            assertThat(client.decrypt(standard)).isEqualTo(key);
        }

        @Test
        @DisplayName("URL-safe Base64 (- _) 디코딩 성공")
        void urlSafeBase64() {
            // given
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            String urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(key);

            // URL-safe 문자가 있는지 확인 (랜덤에 따라 없을 수도 있음)
            // when / then
            assertThat(client.decrypt(urlSafe)).isEqualTo(key);
        }

        @Test
        @DisplayName("패딩 없는 Base64 디코딩 성공")
        void noPaddingBase64() {
            // given
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            String noPadding = Base64.getEncoder().withoutPadding().encodeToString(key);

            // when / then
            assertThat(client.decrypt(noPadding)).isEqualTo(key);
        }

        @Test
        @DisplayName("잘못된 Base64 입력 시 KmsDecryptException 발생")
        void invalidBase64ThrowsException() {
            // given
            String invalid = "!@#$%^&*()_NOT_BASE64";

            // when / then
            assertThatThrownBy(() -> client.decrypt(invalid))
                .isInstanceOf(KmsClient.KmsDecryptException.class)
                .hasMessageContaining("Base64 디코딩 실패");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 메타데이터
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("providerName = 'local'")
    void providerName() {
        assertThat(client.providerName()).isEqualTo("local");
    }

    @Test
    @DisplayName("isHealthy = true (항상)")
    void isHealthy() {
        assertThat(client.isHealthy()).isTrue();
    }
}

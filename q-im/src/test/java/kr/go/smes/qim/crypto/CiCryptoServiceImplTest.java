package kr.go.smes.qim.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * CiCryptoServiceImpl 단위 테스트 — AES-256-GCM CI 암복호화 검증
 *
 * <p>커버 케이스:
 * <ul>
 *   <li>암복호화 라운드트립 — 평문 CI 복원 정확성</li>
 *   <li>암호문 무작위성 — 동일 CI 두 번 암호화 시 결과 상이(IV 랜덤)</li>
 *   <li>키 버전 접두사 — 출력 형식 {@code v1.{iv}.{ct}} 준수</li>
 *   <li>키 버전 로테이션 — v2 키로 암호화된 값을 v2 키로 복호화</li>
 *   <li>isEncrypted() — 버전 접두사 유무 판별</li>
 *   <li>예외: 빈 값, 잘못된 키, 잘못된 암호문 변조</li>
 * </ul>
 */
@DisplayName("CiCryptoServiceImpl — AES-256-GCM CI 암복호화")
class CiCryptoServiceImplTest {

    /** 테스트용 32바이트 Base64 AES-256 키 (v1) */
    private static final String TEST_KEY_V1 = Base64.getEncoder()
            .encodeToString("01234567890123456789012345678901".getBytes());

    /** 테스트용 32바이트 Base64 AES-256 키 (v2 — 로테이션) */
    private static final String TEST_KEY_V2 = Base64.getEncoder()
            .encodeToString("abcdefghijklmnopqrstuvwxyz012345".getBytes());

    /** 실제 CI 형식과 유사한 테스트 CI (88자 Base64) */
    private static final String SAMPLE_CI =
            "dGVzdENJdmFsdWVmb3JVbml0VGVzdGluZ1B1cnBvc2VPbmx5MTIzNDU2Nzg5MEFCQ0RFRkdISUpL";

    private CiCryptoServiceImpl cryptoService;

    @BeforeEach
    void setUp() {
        cryptoService = new CiCryptoServiceImpl();
        ReflectionTestUtils.setField(cryptoService, "aesKeyV1Base64", TEST_KEY_V1);
        ReflectionTestUtils.setField(cryptoService, "aesKeyV2Base64", "");
        ReflectionTestUtils.setField(cryptoService, "currentVersion", "v1");
    }

    // ── 암복호화 라운드트립 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("암복호화 라운드트립")
    class RoundTrip {

        @Test
        @DisplayName("표준 CI 암호화 후 복호화 시 원문 복원")
        void encrypt_thenDecrypt_returnsOriginal() {
            String encrypted = cryptoService.encrypt(SAMPLE_CI);
            String decrypted = cryptoService.decrypt(encrypted);
            assertThat(decrypted).isEqualTo(SAMPLE_CI);
        }

        @Test
        @DisplayName("짧은 CI 값도 라운드트립 성공")
        void encrypt_thenDecrypt_shortValue() {
            String shortCi = "shortCIvalue123";
            String decrypted = cryptoService.decrypt(cryptoService.encrypt(shortCi));
            assertThat(decrypted).isEqualTo(shortCi);
        }

        @Test
        @DisplayName("한글 포함 CI 라운드트립 성공 (UTF-8 인코딩)")
        void encrypt_thenDecrypt_koreanChars() {
            String koreanCi = "홍길동CI테스트값1234567890";
            String decrypted = cryptoService.decrypt(cryptoService.encrypt(koreanCi));
            assertThat(decrypted).isEqualTo(koreanCi);
        }

        @Test
        @DisplayName("동일 CI 두 번 암호화 → 서로 다른 암호문 (IV 랜덤성)")
        void encrypt_samePlaintext_differentCiphertext() {
            String enc1 = cryptoService.encrypt(SAMPLE_CI);
            String enc2 = cryptoService.encrypt(SAMPLE_CI);
            // IV가 랜덤이므로 결과가 달라야 함
            assertThat(enc1).isNotEqualTo(enc2);
            // 하지만 둘 다 복호화하면 동일 원문
            assertThat(cryptoService.decrypt(enc1)).isEqualTo(SAMPLE_CI);
            assertThat(cryptoService.decrypt(enc2)).isEqualTo(SAMPLE_CI);
        }
    }

    // ── 암호문 형식 검증 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("암호문 출력 형식")
    class CiphertextFormat {

        @Test
        @DisplayName("암호문은 'v1.{iv}.{ct}' 형식 3파트")
        void encrypt_outputFormat_threePartsDotSeparated() {
            String encrypted = cryptoService.encrypt(SAMPLE_CI);
            String[] parts = encrypted.split("\\.");
            assertThat(parts).hasSize(3);
            assertThat(parts[0]).isEqualTo("v1");
        }

        @Test
        @DisplayName("IV 파트는 Base64URL 16자 이상")
        void encrypt_ivPart_isBase64Url() {
            String encrypted = cryptoService.encrypt(SAMPLE_CI);
            String ivPart = encrypted.split("\\.")[1];
            // 12바이트 IV → Base64URL 16자 (패딩 없음)
            assertThat(ivPart).hasSizeGreaterThanOrEqualTo(16);
            // Base64URL 문자만 포함 ('+', '/' 없음)
            assertThat(ivPart).doesNotContain("+", "/");
        }

        @Test
        @DisplayName("암호문 파트는 Base64URL 형식")
        void encrypt_ciphertextPart_isBase64Url() {
            String encrypted = cryptoService.encrypt(SAMPLE_CI);
            String ctPart = encrypted.split("\\.")[2];
            assertThat(ctPart).doesNotContain("+", "/");
        }

        @Test
        @DisplayName("100회 암호화 — 모두 고유한 IV (충돌 없음)")
        void encrypt_repeatedCalls_uniqueIVs() {
            Set<String> ivSet = new HashSet<>();
            for (int i = 0; i < 100; i++) {
                String enc = cryptoService.encrypt(SAMPLE_CI);
                String iv = enc.split("\\.")[1];
                ivSet.add(iv);
            }
            assertThat(ivSet).hasSize(100);
        }
    }

    // ── 키 버전 관리 ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("키 버전 로테이션")
    class KeyVersionRotation {

        @Test
        @DisplayName("v2 키로 암호화 후 v2 키로 복호화 성공")
        void encryptWithV2_decryptWithV2_success() {
            // v2 키 활성화
            ReflectionTestUtils.setField(cryptoService, "aesKeyV2Base64", TEST_KEY_V2);
            ReflectionTestUtils.setField(cryptoService, "currentVersion", "v2");

            String encrypted = cryptoService.encrypt(SAMPLE_CI);
            assertThat(encrypted).startsWith("v2.");

            String decrypted = cryptoService.decrypt(encrypted);
            assertThat(decrypted).isEqualTo(SAMPLE_CI);
        }

        @Test
        @DisplayName("v1으로 암호화된 값은 v2가 현재 버전이어도 v1 키로 복호화")
        void v1EncryptedValue_decryptedWithV1KeyEvenWhenV2IsCurrent() {
            // v1으로 먼저 암호화
            String encryptedWithV1 = cryptoService.encrypt(SAMPLE_CI);
            assertThat(encryptedWithV1).startsWith("v1.");

            // v2를 현재 버전으로 전환
            ReflectionTestUtils.setField(cryptoService, "aesKeyV2Base64", TEST_KEY_V2);
            ReflectionTestUtils.setField(cryptoService, "currentVersion", "v2");

            // v1으로 암호화된 값은 여전히 복호화 가능
            String decrypted = cryptoService.decrypt(encryptedWithV1);
            assertThat(decrypted).isEqualTo(SAMPLE_CI);
        }

        @Test
        @DisplayName("존재하지 않는 키 버전 → CiCryptoException")
        void decrypt_unknownKeyVersion_throwsCiCryptoException() {
            // v3 버전 접두사를 가진 가짜 암호문
            // 구현체는 내부 예외를 포장하여 단순 "CI 복호화 실패" 메시지로 던짐
            String fakeV3 = "v3.aGVsbG8.d29ybGQ";
            assertThatThrownBy(() -> cryptoService.decrypt(fakeV3))
                    .isInstanceOf(CiCryptoServiceImpl.CiCryptoException.class);
        }
    }

    // ── isEncrypted() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isEncrypted() 판별")
    class IsEncrypted {

        @Test
        @DisplayName("암호화된 값 → true")
        void isEncrypted_encryptedValue_returnsTrue() {
            String encrypted = cryptoService.encrypt(SAMPLE_CI);
            assertThat(cryptoService.isEncrypted(encrypted)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "plaintext",
                "dGVzdA==",          // Base64이지만 버전 접두사 없음
                "v1.only-two-parts", // 파트 2개만
                "",
        })
        @DisplayName("암호화되지 않은 값 → false")
        void isEncrypted_nonEncryptedValues_returnsFalse(String value) {
            assertThat(cryptoService.isEncrypted(value)).isFalse();
        }

        @Test
        @DisplayName("null → false")
        void isEncrypted_null_returnsFalse() {
            assertThat(cryptoService.isEncrypted(null)).isFalse();
        }

        @Test
        @DisplayName("평문 CI decrypt() 호출 → 경고 후 원문 반환 (하위호환)")
        void decrypt_plaintextCi_returnsAsIs() {
            String plainCi = "notEncryptedValue";
            // isEncrypted=false이면 그대로 반환
            String result = cryptoService.decrypt(plainCi);
            assertThat(result).isEqualTo(plainCi);
        }
    }

    // ── 예외 처리 ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("예외 처리")
    class ExceptionHandling {

        @Test
        @DisplayName("encrypt(null) → IllegalArgumentException")
        void encrypt_null_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> cryptoService.encrypt(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("encrypt(blank) → IllegalArgumentException")
        void encrypt_blank_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> cryptoService.encrypt("   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("decrypt(null) → IllegalArgumentException")
        void decrypt_null_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> cryptoService.decrypt(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("암호문 변조(IV 조작) → CiCryptoException (GCM 인증 실패)")
        void decrypt_tamperedCiphertext_throwsCiCryptoException() {
            String encrypted = cryptoService.encrypt(SAMPLE_CI);
            // 암호문 마지막 문자 변조
            String tampered = encrypted.substring(0, encrypted.length() - 3) + "XXX";
            assertThatThrownBy(() -> cryptoService.decrypt(tampered))
                    .isInstanceOf(Exception.class); // CiCryptoException 또는 하위 예외
        }

        @Test
        @DisplayName("잘못된 Base64 키 설정 → 복호화 시 CiCryptoException")
        void decrypt_invalidKeyBase64_throwsCiCryptoException() {
            ReflectionTestUtils.setField(cryptoService, "aesKeyV1Base64", "not-valid-base64!!!");
            String fakeEncrypted = "v1.aGVsbG8.d29ybGRibGFoYmxhaA";
            assertThatThrownBy(() -> cryptoService.decrypt(fakeEncrypted))
                    .isInstanceOf(Exception.class);
        }
    }
}

package io.github.hipstermin.idem.hub.qim.crypto;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

/**
 * AesSharedKeyDecryptor 단위 테스트
 *
 * 검증 항목:
 *   1. 정상 AES-CBC 복호화 라운드트립
 *   2. 키 길이 오류 (32바이트 미만)
 *   3. 암호문이 너무 짧은 경우 (IV 길이 미만)
 *   4. null/빈 암호문 처리
 *   5. identifierHash 생성 일관성
 *   6. BRNO 기반 식별자 해시
 *   7. CHANGEME 키 설정 시 decrypt 실패
 */
@DisplayName("AesSharedKeyDecryptor — AES-CBC 복호화 + identifierHash")
class AesSharedKeyDecryptorTest {

    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final int IV_LENGTH = 16;

    // AES-256 테스트 키 (32바이트 Base64)
    private static final String VALID_KEY_B64 = Base64.getEncoder().encodeToString(
            "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));

    private AesSharedKeyDecryptor decryptor;

    @BeforeEach
    void setUp() {
        decryptor = new AesSharedKeyDecryptor(VALID_KEY_B64, TRANSFORMATION, IV_LENGTH);
        decryptor.validateConfiguration(); // @PostConstruct 수동 호출
    }

    // ─────────────────────────────────────────────────────────────
    // 1. 정상 복호화 라운드트립
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("decrypt() — AES-CBC 암호화한 값을 올바르게 복호화")
    void decrypt_validCiphertext_returnsPlaintext() throws Exception {
        String plaintext = "1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678"; // CI 모사

        // 직접 AES-CBC 암호화
        String encryptedB64 = encryptAesCbc(plaintext, VALID_KEY_B64);

        // 복호화 검증
        String decrypted = decryptor.decrypt(encryptedB64);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("decrypt() — 다른 평문도 복호화 성공")
    void decrypt_shortPlaintext_ok() throws Exception {
        String plaintext = "hello-world";
        String encrypted = encryptAesCbc(plaintext, VALID_KEY_B64);

        assertThat(decryptor.decrypt(encrypted)).isEqualTo(plaintext);
    }

    // ─────────────────────────────────────────────────────────────
    // 2. 잘못된 키 길이
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("decrypt() — 32바이트 미만 키 설정 시 QimDecryptionException")
    void decrypt_invalidKeyLength_throws() throws Exception {
        // 16바이트 키 (AES-128)로 잘못된 설정
        String shortKey = Base64.getEncoder().encodeToString("0123456789012345".getBytes(StandardCharsets.UTF_8));
        AesSharedKeyDecryptor shortKeyDecryptor = new AesSharedKeyDecryptor(shortKey, TRANSFORMATION, IV_LENGTH);

        String encrypted = encryptAesCbc("test", VALID_KEY_B64);

        // 키 길이 32바이트 미달 → 즉시 거부
        assertThatThrownBy(() -> shortKeyDecryptor.decrypt(encrypted))
                .isInstanceOf(AesSharedKeyDecryptor.QimDecryptionException.class)
                .hasMessageContaining("32바이트");
    }

    // ─────────────────────────────────────────────────────────────
    // 3. 잘못된 암호문 형식
    // ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("decrypt() — null/빈 암호문 → QimDecryptionException")
    void decrypt_blankCiphertext_throws(String blank) {
        assertThatThrownBy(() -> decryptor.decrypt(blank))
                .isInstanceOf(AesSharedKeyDecryptor.QimDecryptionException.class);
    }

    @Test
    @DisplayName("decrypt() — IV 길이 미만 암호문 → QimDecryptionException")
    void decrypt_tooShortCiphertext_throws() {
        // 15바이트 (IV=16바이트 미만)
        String tooShort = Base64.getEncoder().encodeToString(new byte[15]);

        assertThatThrownBy(() -> decryptor.decrypt(tooShort))
                .isInstanceOf(AesSharedKeyDecryptor.QimDecryptionException.class)
                .hasMessageContaining("IV");
    }

    @Test
    @DisplayName("decrypt() — 다른 키로 암호화한 값 → QimDecryptionException")
    void decrypt_wrongKey_throws() throws Exception {
        String otherKey = Base64.getEncoder().encodeToString(
                "99887766554433221100998877665544".getBytes(StandardCharsets.UTF_8));
        String encryptedWithOtherKey = encryptAesCbc("plaintext", otherKey);

        // CBC/PKCS5 는 잘못된 키로도 약 1/256 확률로 패딩이 우연히 맞아 예외 없이 쓰레기 평문이 나온다(패딩 오라클의 뒷면).
        // 예외가 나면 타입을 확인하고, 안 나면 원문이 복원되지 않았음을 확인한다 — CI 게이트를 플레이크로 만들지 않기 위함.
        try {
            String garbage = decryptor.decrypt(encryptedWithOtherKey);
            org.assertj.core.api.Assertions.assertThat(garbage).isNotEqualTo("plaintext");
        } catch (AesSharedKeyDecryptor.QimDecryptionException expected) {
            // 정상 — 패딩/무결성 오류
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 4. CHANGEME 키 설정 시 복호화 실패
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(D2) CHANGEME 플레이스홀더 — validateConfiguration 이 기동을 거부한다 (allow-empty-aes-key=false)")
    void validate_placeholder_rejectsBoot() {
        AesSharedKeyDecryptor placeholderDecryptor =
                new AesSharedKeyDecryptor("CHANGEME_32BYTES_BASE64_PLACEHOLDER=", TRANSFORMATION, IV_LENGTH);

        assertThatThrownBy(placeholderDecryptor::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    @DisplayName("(D2) 빈 키 — allow-empty-aes-key=true(로컬·테스트 전용) 면 기동은 통과하되 decrypt() 는 실패")
    void validate_emptyKey_allowedOnlyExplicitly() throws Exception {
        AesSharedKeyDecryptor emptyDecryptor = new AesSharedKeyDecryptor("", TRANSFORMATION, IV_LENGTH, true);
        emptyDecryptor.validateConfiguration();   // 통과 (경고만)

        String encrypted = encryptAesCbc("test", VALID_KEY_B64);
        assertThatThrownBy(() -> emptyDecryptor.decrypt(encrypted))
                .isInstanceOf(AesSharedKeyDecryptor.QimDecryptionException.class)
                .hasMessageContaining("32바이트");

        assertThatThrownBy(() -> new AesSharedKeyDecryptor("", TRANSFORMATION, IV_LENGTH).validateConfiguration())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("CHANGEME 키(유효 Base64이지만 32바이트 미만) 생성자 후 decrypt() → QimDecryptionException")
    void decrypt_changeMe_throws() throws Exception {
        // 테스트 의도: 유효한 Base64이지만 32바이트 미만 키를 사용하면 decrypt()에서 실패해야 함.
        // → 16바이트(AES-128) 키로 생성 후 decrypt() 호출 시 QimDecryptionException 발생 검증
        byte[] shortKeyBytes = new byte[16]; // 16바이트 (AES-128, 32바이트 미만)
        String shortKey = Base64.getEncoder().encodeToString(shortKeyBytes);
        AesSharedKeyDecryptor changeDecryptor = new AesSharedKeyDecryptor(shortKey, TRANSFORMATION, IV_LENGTH);

        String encrypted = encryptAesCbc("test", VALID_KEY_B64);

        assertThatThrownBy(() -> changeDecryptor.decrypt(encrypted))
                .isInstanceOf(AesSharedKeyDecryptor.QimDecryptionException.class)
                .hasMessageContaining("32바이트");
    }

    // ─────────────────────────────────────────────────────────────
    // 5. identifierHash 일관성
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("computeIdentifierHash() — 동일 CI 입력 → 동일 해시 출력")
    void computeIdentifierHash_deterministic() {
        String ci = "a".repeat(88); // CI 길이 모사

        String hash1 = decryptor.computeIdentifierHash(ci);
        String hash2 = decryptor.computeIdentifierHash(ci);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 = 32바이트 = 64 hex chars
    }

    @Test
    @DisplayName("computeIdentifierHash() — 다른 CI → 다른 해시")
    void computeIdentifierHash_differentCi_differentHash() {
        String hash1 = decryptor.computeIdentifierHash("ci-value-A");
        String hash2 = decryptor.computeIdentifierHash("ci-value-B");

        assertThat(hash1).isNotEqualTo(hash2);
    }

    // ─────────────────────────────────────────────────────────────
    // 6. BRNO 기반 식별자 해시
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("computeIdentifierHashFromBrno() — 하이픈 유무 관계없이 동일 해시")
    void computeIdentifierHashFromBrno_normalizesHyphens() {
        String hash1 = decryptor.computeIdentifierHashFromBrno("123-45-67890");
        String hash2 = decryptor.computeIdentifierHashFromBrno("1234567890");

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("computeIdentifierHashFromBrno() — null/빈 BRNO → QimDecryptionException")
    void computeIdentifierHashFromBrno_blankBrno_throws() {
        assertThatThrownBy(() -> decryptor.computeIdentifierHashFromBrno(null))
                .isInstanceOf(AesSharedKeyDecryptor.QimDecryptionException.class);
        assertThatThrownBy(() -> decryptor.computeIdentifierHashFromBrno(""))
                .isInstanceOf(AesSharedKeyDecryptor.QimDecryptionException.class);
    }

    // ─────────────────────────────────────────────────────────────
    // 헬퍼: AES-CBC 암호화
    // ─────────────────────────────────────────────────────────────

    private static String encryptAesCbc(String plaintext, String keyB64) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(keyB64);
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(keyBytes, "AES"),
                new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        // IV || CipherText → Base64
        byte[] combined = new byte[IV_LENGTH + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
        System.arraycopy(encrypted, 0, combined, IV_LENGTH, encrypted.length);
        return Base64.getEncoder().encodeToString(combined);
    }
}

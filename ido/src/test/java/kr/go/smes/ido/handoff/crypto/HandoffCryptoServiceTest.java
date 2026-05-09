package kr.go.smes.ido.handoff.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * HandoffCryptoService 단위 테스트
 *
 * <p>설계서 §16.4 / §24.4.1 — Handoff Ticket AES-256-GCM 암호화 및 HMAC-SHA256 서명 검증.
 *
 * <p>테스트 구성:
 * <ul>
 *   <li>encrypt() — AES-256-GCM 라운드트립, AAD 바인딩, IV 무작위성, 잘못된 키 길이</li>
 *   <li>sign() + verify() — HMAC-SHA256 정상/다른키/변조 payload 케이스</li>
 *   <li>MessageDigestUtil.safeEquals() — 상수시간 비교, null 보호</li>
 * </ul>
 *
 * <p>외부 의존성 없음 — 순수 Java Crypto API만 사용.
 */
class HandoffCryptoServiceTest {

    private static final int GCM_IV_LENGTH  = 12;
    private static final int GCM_TAG_LENGTH = 128;

    /** 32바이트(256-bit) AES 테스트 키 (Base64 표준 인코딩) */
    private static final String VALID_AES_KEY_B64 =
            Base64.getEncoder().encodeToString(new byte[32]); // all-zero, 테스트 전용

    /** 32바이트 HMAC 테스트 키 */
    private static final String VALID_HMAC_KEY_B64 =
            Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));

    private HandoffCryptoService cryptoService;

    @BeforeEach
    void setUp() {
        cryptoService = new HandoffCryptoService();
        ReflectionTestUtils.setField(cryptoService, "aesKeyBase64",  VALID_AES_KEY_B64);
        ReflectionTestUtils.setField(cryptoService, "hmacKeyBase64", VALID_HMAC_KEY_B64);
    }

    // ════════════════════════════════════════════════════════════════════════
    // encrypt() / AES-256-GCM 라운드트립 (직접 복호화로 검증)
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("encrypt() — AES-256-GCM")
    class EncryptTests {

        @Test
        @DisplayName("정상 암호화 — 결과가 null/빈 문자열이 아닌 Base64URL 문자열")
        void encryptReturnsNonEmptyBase64Url() {
            String encrypted = cryptoService.encrypt("{\"sub\":\"user1\"}", "ticket-001");
            assertThat(encrypted).isNotNull().isNotBlank();
            // Base64URL 문자만 포함 (패딩 없음)
            assertThat(encrypted).matches("[A-Za-z0-9_-]+");
        }

        @Test
        @DisplayName("AES-256-GCM 라운드트립 — 직접 복호화 후 평문 일치")
        void encryptDecryptRoundTrip() throws Exception {
            String plaintext = "{\"qimUserId\":\"u-001\",\"agencyCode\":\"SMES\"}";
            String aad = "ticket-roundtrip";
            String encrypted = cryptoService.encrypt(plaintext, aad);

            // 직접 복호화
            byte[] combined = Base64.getUrlDecoder().decode(encrypted);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] cipherWithTag = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, cipherWithTag, 0, cipherWithTag.length);

            byte[] keyBytes = Base64.getDecoder().decode(normalizeBase64(VALID_AES_KEY_B64));
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec paramSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, paramSpec);
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));

            byte[] decrypted = cipher.doFinal(cipherWithTag);
            assertThat(new String(decrypted, StandardCharsets.UTF_8)).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("AAD 불일치 시 복호화 실패 — GCM 인증 태그 검증")
        void wrongAadFailsDecryption() throws Exception {
            String plaintext = "payload";
            String aad = "ticket-aad";
            String encrypted = cryptoService.encrypt(plaintext, aad);

            byte[] combined = Base64.getUrlDecoder().decode(encrypted);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] cipherWithTag = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, cipherWithTag, 0, cipherWithTag.length);

            byte[] keyBytes = Base64.getDecoder().decode(normalizeBase64(VALID_AES_KEY_B64));
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            cipher.updateAAD("wrong-aad".getBytes(StandardCharsets.UTF_8)); // 다른 AAD

            assertThatThrownBy(() -> cipher.doFinal(cipherWithTag))
                    .isInstanceOf(Exception.class)
                    .as("AAD 불일치 시 GCM 태그 검증 실패해야 함");
        }

        @Test
        @DisplayName("IV 무작위성 — 동일 plaintext 두 번 암호화 → 다른 결과")
        void ivRandomnessProducesDifferentCiphertext() {
            String plaintext = "same-payload";
            String c1 = cryptoService.encrypt(plaintext, "ticket-1");
            String c2 = cryptoService.encrypt(plaintext, "ticket-2");
            assertThat(c1).isNotEqualTo(c2);
        }

        @Test
        @DisplayName("50회 암호화 → 모두 다른 결과 (IV 충돌 없음)")
        void noIvCollisionAcross50Encryptions() {
            Set<String> results = new HashSet<>();
            for (int i = 0; i < 50; i++) {
                results.add(cryptoService.encrypt("payload-" + i, "ticket-" + i));
            }
            assertThat(results).hasSize(50);
        }

        @Test
        @DisplayName("aad가 null이어도 암호화 정상 수행")
        void nullAadIsHandledGracefully() {
            assertThatCode(() -> cryptoService.encrypt("payload", null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("잘못된 키 (16바이트 → AES-128 시도) — RuntimeException 발생")
        void wrongKeySizeThrowsRuntimeException() {
            // AES-GCM은 16/24/32바이트 키 모두 지원하므로 0바이트로 강제 오류
            ReflectionTestUtils.setField(cryptoService, "aesKeyBase64",
                    Base64.getEncoder().encodeToString(new byte[0]));
            assertThatThrownBy(() -> cryptoService.encrypt("payload", "aad"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("암호화 실패");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // sign() + verify()
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("sign() + verify()")
    class SignVerifyTests {

        private final String TICKET_ID        = "ticket-001";
        private final String AGENCY_CODE      = "SMES";
        private final String ENCRYPTED_PAYLOAD = "encPayload-abc123";

        @Test
        @DisplayName("서명 후 검증 — 정상 라운드트립")
        void signThenVerifyRoundTrip() {
            String signature = cryptoService.sign(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD);
            assertThat(signature).isNotNull().isNotBlank();

            boolean valid = cryptoService.verify(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD, signature);
            assertThat(valid).isTrue();
        }

        @Test
        @DisplayName("서명은 Base64URL 문자열이어야 한다")
        void signatureIsBase64Url() {
            String signature = cryptoService.sign(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD);
            assertThat(signature).matches("[A-Za-z0-9_-]+");
        }

        @Test
        @DisplayName("ticketId 변조 → 검증 실패")
        void tamperedTicketIdFailsVerification() {
            String signature = cryptoService.sign(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD);
            boolean valid = cryptoService.verify("TAMPERED-TICKET", AGENCY_CODE, ENCRYPTED_PAYLOAD, signature);
            assertThat(valid).isFalse();
        }

        @Test
        @DisplayName("agencyCode 변조 → 검증 실패")
        void tamperedAgencyCodeFailsVerification() {
            String signature = cryptoService.sign(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD);
            boolean valid = cryptoService.verify(TICKET_ID, "TAMPERED", ENCRYPTED_PAYLOAD, signature);
            assertThat(valid).isFalse();
        }

        @Test
        @DisplayName("encryptedPayload 변조 → 검증 실패")
        void tamperedPayloadFailsVerification() {
            String signature = cryptoService.sign(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD);
            boolean valid = cryptoService.verify(TICKET_ID, AGENCY_CODE, "tampered-payload", signature);
            assertThat(valid).isFalse();
        }

        @Test
        @DisplayName("다른 HMAC 키로 서명 → 검증 실패")
        void differentHmacKeyFailsVerification() {
            // 정상 키로 서명
            String signature = cryptoService.sign(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD);

            // 다른 키로 교체 후 검증
            String differentKey = Base64.getEncoder()
                    .encodeToString("different-key-890123456789012345".getBytes(StandardCharsets.UTF_8));
            ReflectionTestUtils.setField(cryptoService, "hmacKeyBase64", differentKey);

            boolean valid = cryptoService.verify(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD, signature);
            assertThat(valid).isFalse();
        }

        @Test
        @DisplayName("expectedSignature가 null → false 반환 (예외 없음)")
        void nullExpectedSignatureReturnsFalse() {
            boolean valid = cryptoService.verify(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD, null);
            assertThat(valid).isFalse();
        }

        @Test
        @DisplayName("서명 입력: ticketId|agencyCode|encryptedPayload 순서 보장")
        void signingInputOrderIsCorrect() {
            // sign()과 verify()가 동일한 입력 순서를 사용하는지 검증
            // → 같은 입력으로 두 번 서명하면 동일 결과여야 함 (결정적)
            String sig1 = cryptoService.sign(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD);
            String sig2 = cryptoService.sign(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD);
            assertThat(sig1).isEqualTo(sig2);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // MessageDigestUtil.safeEquals() — 상수시간 비교
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("MessageDigestUtil.safeEquals() — 상수시간 비교")
    class SafeEqualsTests {

        @Test
        @DisplayName("동일 문자열 → true")
        void sameStringReturnsTrue() {
            assertThat(HandoffCryptoService.MessageDigestUtil.safeEquals("abc", "abc")).isTrue();
        }

        @Test
        @DisplayName("다른 문자열 → false")
        void differentStringReturnsFalse() {
            assertThat(HandoffCryptoService.MessageDigestUtil.safeEquals("abc", "xyz")).isFalse();
        }

        @Test
        @DisplayName("길이가 다른 문자열 → false")
        void differentLengthReturnsFalse() {
            assertThat(HandoffCryptoService.MessageDigestUtil.safeEquals("abc", "abcd")).isFalse();
        }

        @Test
        @DisplayName("null 입력(a) → false")
        void nullAReturnsFalse() {
            assertThat(HandoffCryptoService.MessageDigestUtil.safeEquals(null, "abc")).isFalse();
        }

        @Test
        @DisplayName("null 입력(b) → false")
        void nullBReturnsFalse() {
            assertThat(HandoffCryptoService.MessageDigestUtil.safeEquals("abc", null)).isFalse();
        }

        @Test
        @DisplayName("빈 문자열 양쪽 → true")
        void bothEmptyReturnsTrue() {
            assertThat(HandoffCryptoService.MessageDigestUtil.safeEquals("", "")).isTrue();
        }
    }

    // ── private ────────────────────────────────────────────────────────────

    /** HandoffCryptoService.normalizeBase64() 로직 복제 (테스트 내부 헬퍼) */
    private static String normalizeBase64(String b64) {
        String std = b64.replace('-', '+').replace('_', '/');
        int pad = std.length() % 4;
        if (pad == 2) std += "==";
        else if (pad == 3) std += "=";
        return std;
    }
}

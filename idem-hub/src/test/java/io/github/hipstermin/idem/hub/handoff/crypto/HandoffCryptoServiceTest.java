package io.github.hipstermin.idem.hub.handoff.crypto;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import io.github.hipstermin.idem.hub.crypto.KeyVersionRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * HandoffCryptoService 단위 테스트
 *
 * <p>설계서 §16.4 / §24.4.1 — Handoff Ticket AES-256-GCM 암호화 및 HMAC-SHA256 서명 검증.
 *
 * <p>테스트 구성:
 * <ul>
 *   <li>{@code encrypt()} — 버전 접두사 포맷, AAD 바인딩, IV 무작위성</li>
 *   <li>{@code decrypt()} — 버전 접두사 라운드트립, 레거시 포맷 하위호환, 잘못된 포맷 예외</li>
 *   <li>{@code isVersioned()} — 포맷 판별 패턴 검증</li>
 *   <li>{@code sign()} + {@code verify()} — HMAC-SHA256 정상/변조/null 케이스</li>
 *   <li>{@code MessageDigestUtil.safeEquals()} — 상수시간 비교 null 보호</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("HandoffCryptoService 단위 테스트")
class HandoffCryptoServiceTest {

    private static final int GCM_IV_LENGTH  = 12;
    private static final int GCM_TAG_LENGTH = 128;

    // 32바이트(256-bit) 테스트 키
    private static final byte[] AES_KEY_BYTES  = new byte[32]; // all-zero
    private static final byte[] HMAC_KEY_BYTES =
            "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);

    private static final String AES_KEY_B64  = Base64.getEncoder().encodeToString(AES_KEY_BYTES);
    private static final String HMAC_KEY_B64 = Base64.getEncoder().encodeToString(HMAC_KEY_BYTES);

    private static final String VERSION_V1 = "v1";
    private static final String VERSION_V2 = "v2";

    @Mock
    KeyVersionRegistry keyVersionRegistry;

    @InjectMocks
    HandoffCryptoService sut;

    @BeforeEach
    void setUp() {
        // 기본 stub: currentAesVersion/currentHmacVersion → v1, resolveAesKey/resolveHmacKey → 테스트 키
        given(keyVersionRegistry.currentAesVersion()).willReturn(VERSION_V1);
        given(keyVersionRegistry.currentHmacVersion()).willReturn(VERSION_V1);
        given(keyVersionRegistry.resolveAesKey(VERSION_V1)).willReturn(AES_KEY_BYTES);
        given(keyVersionRegistry.resolveHmacKey(VERSION_V1)).willReturn(HMAC_KEY_BYTES);
        // isVersioned()는 HandoffCryptoService 내부 정규식으로 처리 — KeyVersionRegistry 호출 없음
    }

    // ════════════════════════════════════════════════════════════════════════
    // encrypt() — 버전 접두사 AES-256-GCM
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("encrypt() — 버전 접두사 AES-256-GCM")
    class EncryptTests {

        @Test
        @DisplayName("출력 포맷이 v{n}.{base64url}.{base64url} 이어야 한다")
        void outputFormatHasVersionPrefix() {
            String encrypted = sut.encrypt("{\"sub\":\"user1\"}", "ticket-001");

            assertThat(encrypted)
                    .as("버전 접두사 포맷: v1.xxx.yyy")
                    .matches("^v\\d+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");
        }

        @Test
        @DisplayName("버전 접두사가 현재 활성 버전(v1)이어야 한다")
        void versionPrefixMatchesCurrentVersion() {
            String encrypted = sut.encrypt("payload", "ticket-001");

            assertThat(encrypted).startsWith("v1.");
        }

        @Test
        @DisplayName("v2로 로테이션 후 암호화 — v2 접두사")
        void versionPrefixChangesAfterRotation() {
            byte[] v2Key = new byte[32]; // 다른 키 (AES는 0바이트 키 허용 안 됨 — 32바이트 유지)
            v2Key[0] = 1; // v2는 첫 바이트만 다름
            given(keyVersionRegistry.currentAesVersion()).willReturn(VERSION_V2);
            given(keyVersionRegistry.resolveAesKey(VERSION_V2)).willReturn(v2Key);

            String encrypted = sut.encrypt("payload", "ticket-001");

            assertThat(encrypted).startsWith("v2.");
        }

        @Test
        @DisplayName("encrypt → decrypt 라운드트립 — 평문 복원")
        void encryptDecryptRoundTrip() throws Exception {
            String plaintext = "{\"qimUserId\":\"u-001\",\"agencyCode\":\"SMES\"}";
            String aad       = "ticket-roundtrip";

            String encrypted = sut.encrypt(plaintext, aad);

            // 직접 복호화 (JCE API)
            String[] parts      = encrypted.split("\\.", 3);
            byte[]   iv         = Base64.getUrlDecoder().decode(parts[1]);
            byte[]   cipherBytes = Base64.getUrlDecoder().decode(parts[2]);

            SecretKeySpec keySpec = new SecretKeySpec(AES_KEY_BYTES, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));

            byte[] plain = cipher.doFinal(cipherBytes);
            assertThat(new String(plain, StandardCharsets.UTF_8)).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("AAD 불일치 시 복호화 실패 — GCM 인증 태그 검증")
        void wrongAadFailsDecryption() throws Exception {
            String encrypted = sut.encrypt("payload", "ticket-aad");

            String[] parts      = encrypted.split("\\.", 3);
            byte[]   iv         = Base64.getUrlDecoder().decode(parts[1]);
            byte[]   cipherBytes = Base64.getUrlDecoder().decode(parts[2]);

            SecretKeySpec keySpec = new SecretKeySpec(AES_KEY_BYTES, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            cipher.updateAAD("wrong-aad".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> cipher.doFinal(cipherBytes))
                    .isInstanceOf(Exception.class)
                    .as("AAD 불일치 → GCM 태그 검증 실패해야 함");
        }

        @Test
        @DisplayName("aad가 null이어도 암호화 정상 수행")
        void nullAadIsHandledGracefully() {
            assertThatCode(() -> sut.encrypt("payload", null))
                    .doesNotThrowAnyException();
        }

        @RepeatedTest(20)
        @DisplayName("동일 평문 반복 암호화 → IV 충돌 없음 (확률적)")
        void ivUniquenessAcrossRepeatedEncryptions() {
            Set<String> ivSet = new HashSet<>();
            for (int i = 0; i < 20; i++) {
                String enc = sut.encrypt("same-payload", "ticket-" + i);
                String ivPart = enc.split("\\.")[1]; // v{n}.{iv}.{ct} 에서 iv 추출
                ivSet.add(ivPart);
            }
            assertThat(ivSet).hasSize(20).as("20개 암호화에서 IV 충돌 없어야 함");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // decrypt() — 버전 접두사 복호화 + 레거시 하위호환
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("decrypt() — 버전별 복호화 및 레거시 하위호환")
    class DecryptTests {

        @Test
        @DisplayName("encrypt() 결과를 decrypt()로 복원 — 완전한 라운드트립")
        void fullRoundTrip() {
            String plaintext = "full-roundtrip-payload";
            String aad       = "ticket-rt";

            String encrypted = sut.encrypt(plaintext, aad);
            String decrypted = sut.decrypt(encrypted, aad);

            assertThat(decrypted).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("v1 키로 암호화 → v1 키로 복호화 성공")
        void decryptWithMatchingV1Key() {
            String encrypted = sut.encrypt("hello-world", "aad-001");
            // v1 키 stub은 @BeforeEach에 이미 설정됨
            given(keyVersionRegistry.resolveAesKey(VERSION_V1)).willReturn(AES_KEY_BYTES);

            String decrypted = sut.decrypt(encrypted, "aad-001");
            assertThat(decrypted).isEqualTo("hello-world");
        }

        @Test
        @DisplayName("레거시 포맷(버전 접두사 없음) — v1 키로 복호화 시도")
        void legacyFormatDecryptedWithV1Key() throws Exception {
            // 레거시 포맷: Base64URL(IV || ciphertext+tag) — 단일 연결
            byte[] iv = new byte[GCM_IV_LENGTH];
            SecretKeySpec keySpec = new SecretKeySpec(AES_KEY_BYTES, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            cipher.updateAAD("legacy-aad".getBytes(StandardCharsets.UTF_8));
            byte[] cipherBytes = cipher.doFinal("legacy-payload".getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);
            String legacyEncrypted = Base64.getUrlEncoder().withoutPadding().encodeToString(combined);

            // 버전 접두사 없음 확인
            assertThat(legacyEncrypted).doesNotMatch("^v\\d+\\..+\\..+$");

            // v1 키로 복호화 가능해야 함
            String decrypted = sut.decrypt(legacyEncrypted, "legacy-aad");
            assertThat(decrypted).isEqualTo("legacy-payload");
        }

        @Test
        @DisplayName("null payload → IllegalArgumentException")
        void nullPayloadThrowsException() {
            assertThatThrownBy(() -> sut.decrypt(null, "aad"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("빈 payload → IllegalArgumentException")
        void blankPayloadThrowsException() {
            assertThatThrownBy(() -> sut.decrypt("   ", "aad"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("존재하지 않는 버전 접두사 → KeyNotFoundException 포장 RuntimeException")
        void unknownVersionThrowsRuntimeException() {
            given(keyVersionRegistry.resolveAesKey("v99"))
                    .willThrow(new KeyVersionRegistry.KeyNotFoundException("v99 키 없음"));

            assertThatThrownBy(() -> sut.decrypt("v99.aXY.Y3Q", "aad"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("복호화 실패");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // isVersioned()
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("isVersioned() — 버전 접두사 포맷 판별")
    class IsVersionedTests {

        @Test
        @DisplayName("v1.xxx.yyy → true")
        void v1FormatReturnsTrue() {
            assertThat(sut.isVersioned("v1.SGVsbG8.d29ybGQ")).isTrue();
        }

        @Test
        @DisplayName("v12.xxx.yyy → true (버전 번호 2자리)")
        void multiDigitVersionReturnsTrue() {
            assertThat(sut.isVersioned("v12.aXY.Y3Q")).isTrue();
        }

        @Test
        @DisplayName("레거시 Base64URL (접두사 없음) → false")
        void legacyBase64ReturnsFalse() {
            assertThat(sut.isVersioned("SGVsbG9Xb3JsZA")).isFalse();
        }

        @Test
        @DisplayName("null → false")
        void nullReturnsFalse() {
            assertThat(sut.isVersioned(null)).isFalse();
        }

        @Test
        @DisplayName("두 부분만 있는 경우 → false")
        void twoParts_ReturnsFalse() {
            assertThat(sut.isVersioned("v1.aXY")).isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // sign() + verify()
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("sign() + verify() — HMAC-SHA256")
    class SignVerifyTests {

        private static final String TICKET_ID        = "ticket-001";
        private static final String AGENCY_CODE      = "SMES";
        private static final String ENCRYPTED_PAYLOAD = "v1.aXY.Y3Q";

        @Test
        @DisplayName("서명 후 검증 — 정상 라운드트립")
        void signThenVerifyRoundTrip() {
            String signature = sut.sign(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD);

            assertThat(signature).isNotNull().isNotBlank();
            assertThat(sut.verify(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD, signature)).isTrue();
        }

        @Test
        @DisplayName("서명 결과는 Base64URL 문자열이어야 한다")
        void signatureIsBase64Url() {
            String signature = sut.sign(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD);
            assertThat(signature).matches("[A-Za-z0-9_-]+");
        }

        @Test
        @DisplayName("동일 입력 → 결정적(deterministic) 서명")
        void signIsDeterministic() {
            String sig1 = sut.sign(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD);
            String sig2 = sut.sign(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD);
            assertThat(sig1).isEqualTo(sig2);
        }

        @Test
        @DisplayName("ticketId 변조 → 검증 실패")
        void tamperedTicketIdFailsVerification() {
            String sig = sut.sign(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD);
            assertThat(sut.verify("TAMPERED", AGENCY_CODE, ENCRYPTED_PAYLOAD, sig)).isFalse();
        }

        @Test
        @DisplayName("agencyCode 변조 → 검증 실패")
        void tamperedAgencyCodeFailsVerification() {
            String sig = sut.sign(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD);
            assertThat(sut.verify(TICKET_ID, "TAMPERED", ENCRYPTED_PAYLOAD, sig)).isFalse();
        }

        @Test
        @DisplayName("encryptedPayload 변조 → 검증 실패")
        void tamperedPayloadFailsVerification() {
            String sig = sut.sign(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD);
            assertThat(sut.verify(TICKET_ID, AGENCY_CODE, "tampered", sig)).isFalse();
        }

        @Test
        @DisplayName("다른 HMAC 키 반환 시 검증 실패")
        void differentHmacKeyFailsVerification() {
            // 서명 (v1 키)
            String sig = sut.sign(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD);

            // verify 시 다른 키 반환
            byte[] differentKey = "different-key-890123456789012345".getBytes(StandardCharsets.UTF_8);
            given(keyVersionRegistry.resolveHmacKey(VERSION_V1)).willReturn(differentKey);

            assertThat(sut.verify(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD, sig)).isFalse();
        }

        @Test
        @DisplayName("expectedSignature가 null → false (예외 없음)")
        void nullExpectedSignatureReturnsFalse() {
            assertThat(sut.verify(TICKET_ID, AGENCY_CODE, ENCRYPTED_PAYLOAD, null)).isFalse();
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
        @DisplayName("null(a) → false")
        void nullAReturnsFalse() {
            assertThat(HandoffCryptoService.MessageDigestUtil.safeEquals(null, "abc")).isFalse();
        }

        @Test
        @DisplayName("null(b) → false")
        void nullBReturnsFalse() {
            assertThat(HandoffCryptoService.MessageDigestUtil.safeEquals("abc", null)).isFalse();
        }

        @Test
        @DisplayName("양쪽 빈 문자열 → true")
        void bothEmptyReturnsTrue() {
            assertThat(HandoffCryptoService.MessageDigestUtil.safeEquals("", "")).isTrue();
        }
    }
}

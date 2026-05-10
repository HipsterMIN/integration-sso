package kr.go.smes.ido.auth.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

/**
 * NiceCryptoUtil 암호화 유틸리티 단위 테스트
 *
 * <p>NICE IDO 인증 서버와의 연동에 사용되는 암호화 함수들을 검증한다.
 * 외부 의존성 없는 순수 단위 테스트.
 *
 * <p><b>테스트 항목:</b>
 * <ul>
 *   <li>deriveKey: PBKDF2WithHmacSHA256 키 파생</li>
 *   <li>hmacSha256Base64Url: HMAC-SHA256 무결성 검증</li>
 *   <li>aesGcmDecrypt: AES-256-GCM 복호화</li>
 *   <li>extractAesKey / extractHmacKey: 키 분리 편의 메서드</li>
 * </ul>
 */
@DisplayName("NiceCryptoUtil — NICE 암호화 유틸리티 단위 테스트")
class NiceCryptoUtilTest {

    // ── 테스트 픽스처 ──────────────────────────────────────────────────────────

    private static final String TEST_TICKET = "test-ticket-value-for-pbkdf2";
    private static final String TEST_TRANSACTION_ID = "NICE_TEST_TRANSACTION_20240510";
    private static final int TEST_ITERATORS = 1000;

    // ── deriveKey 테스트 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("deriveKey: PBKDF2 키 파생 결과는 null이 아니며 비어있지 않아야 한다")
    void deriveKey_shouldReturnNonEmptyString() {
        // when
        String keyString = NiceCryptoUtil.deriveKey(TEST_TICKET, TEST_TRANSACTION_ID, TEST_ITERATORS);

        // then
        assertThat(keyString).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("deriveKey: 동일 입력으로 동일 키를 반환해야 한다 (결정적 함수)")
    void deriveKey_shouldBeDeterministic() {
        // when
        String key1 = NiceCryptoUtil.deriveKey(TEST_TICKET, TEST_TRANSACTION_ID, TEST_ITERATORS);
        String key2 = NiceCryptoUtil.deriveKey(TEST_TICKET, TEST_TRANSACTION_ID, TEST_ITERATORS);

        // then
        assertThat(key1).isEqualTo(key2);
    }

    @Test
    @DisplayName("deriveKey: 다른 ticket으로 다른 키를 반환해야 한다")
    void deriveKey_shouldReturnDifferentKeyForDifferentTicket() {
        // when
        String key1 = NiceCryptoUtil.deriveKey("ticket-A", TEST_TRANSACTION_ID, TEST_ITERATORS);
        String key2 = NiceCryptoUtil.deriveKey("ticket-B", TEST_TRANSACTION_ID, TEST_ITERATORS);

        // then
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    @DisplayName("deriveKey: 다른 transactionId로 다른 키를 반환해야 한다")
    void deriveKey_shouldReturnDifferentKeyForDifferentTransactionId() {
        // when
        String key1 = NiceCryptoUtil.deriveKey(TEST_TICKET, "txn-001", TEST_ITERATORS);
        String key2 = NiceCryptoUtil.deriveKey(TEST_TICKET, "txn-002", TEST_ITERATORS);

        // then
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    @DisplayName("deriveKey: 결과는 Base64 URL-safe 인코딩이어야 한다 (패딩 없음)")
    void deriveKey_shouldReturnBase64UrlEncodedString() {
        // when
        String keyString = NiceCryptoUtil.deriveKey(TEST_TICKET, TEST_TRANSACTION_ID, TEST_ITERATORS);

        // then: Base64 URL-safe 문자만 포함 ('+', '/' 없음, '=' 패딩 없음)
        assertThat(keyString).matches("[A-Za-z0-9_-]+");
    }

    // ── extractAesKey / extractHmacKey 테스트 ─────────────────────────────────

    @Test
    @DisplayName("extractAesKey: 키 문자열 앞 32바이트를 AES 키로 추출해야 한다")
    void extractAesKey_shouldReturn32Bytes() {
        // given
        String keyString = NiceCryptoUtil.deriveKey(TEST_TICKET, TEST_TRANSACTION_ID, TEST_ITERATORS);

        // when
        byte[] aesKey = NiceCryptoUtil.extractAesKey(keyString);

        // then: AES-256 = 32바이트
        assertThat(aesKey).hasSize(32);
        assertThat(aesKey)
                .isEqualTo(keyString.substring(0, 32).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("extractHmacKey: 키 문자열 48~79 인덱스를 HMAC 키로 추출해야 한다")
    void extractHmacKey_shouldReturn32CharString() {
        // given
        String keyString = NiceCryptoUtil.deriveKey(TEST_TICKET, TEST_TRANSACTION_ID, TEST_ITERATORS);

        // when
        String hmacKey = NiceCryptoUtil.extractHmacKey(keyString);

        // then: 32자 HMAC 키
        assertThat(hmacKey).hasSize(32);
        assertThat(hmacKey).isEqualTo(keyString.substring(48, 80));
    }

    // ── hmacSha256Base64Url 테스트 ─────────────────────────────────────────────

    @Test
    @DisplayName("hmacSha256Base64Url: 동일 입력으로 동일 HMAC 값을 반환해야 한다")
    void hmacSha256Base64Url_shouldBeDeterministic() {
        // given
        String data = "test-enc-data-base64url-string";
        String hmacKey = "test-hmac-key-32-characters-long";

        // when
        String hmac1 = NiceCryptoUtil.hmacSha256Base64Url(data, hmacKey);
        String hmac2 = NiceCryptoUtil.hmacSha256Base64Url(data, hmacKey);

        // then
        assertThat(hmac1).isEqualTo(hmac2);
    }

    @Test
    @DisplayName("hmacSha256Base64Url: 다른 데이터로 다른 HMAC 값을 반환해야 한다")
    void hmacSha256Base64Url_shouldReturnDifferentValueForDifferentData() {
        // given
        String hmacKey = "test-hmac-key-32-characters-long";

        // when
        String hmac1 = NiceCryptoUtil.hmacSha256Base64Url("data-A", hmacKey);
        String hmac2 = NiceCryptoUtil.hmacSha256Base64Url("data-B", hmacKey);

        // then
        assertThat(hmac1).isNotEqualTo(hmac2);
    }

    @Test
    @DisplayName("hmacSha256Base64Url: 결과는 Base64 URL-safe 인코딩이어야 한다")
    void hmacSha256Base64Url_shouldReturnBase64UrlEncoding() {
        // given
        String data = "test-data";
        String hmacKey = "test-hmac-key-32-characters-long";

        // when
        String hmac = NiceCryptoUtil.hmacSha256Base64Url(data, hmacKey);

        // then: Base64 URL-safe 문자만 포함
        assertThat(hmac).isNotBlank().matches("[A-Za-z0-9_-]+");
    }

    // ── AES-GCM 암호화/복호화 통합 테스트 ────────────────────────────────────

    @Test
    @DisplayName("deriveKey + extractAesKey + extractHmacKey + hmacSha256Base64Url: 무결성 검증 플로우 통합")
    void integrityVerification_shouldWorkEndToEnd() {
        // given: 키 파생
        String keyString = NiceCryptoUtil.deriveKey(TEST_TICKET, TEST_TRANSACTION_ID, TEST_ITERATORS);
        String hmacKey = NiceCryptoUtil.extractHmacKey(keyString);

        // when: 임의 데이터의 HMAC 계산 → 동일 키로 재계산 → 비교
        String testData = "test-encrypted-data-value";
        String hmac1 = NiceCryptoUtil.hmacSha256Base64Url(testData, hmacKey);
        String hmac2 = NiceCryptoUtil.hmacSha256Base64Url(testData, hmacKey);

        // then: 동일 키+데이터 → 동일 HMAC (무결성 검증 통과)
        assertThat(hmac1).isEqualTo(hmac2);

        // 데이터 변조 시 HMAC 불일치 확인
        String hmacTampered = NiceCryptoUtil.hmacSha256Base64Url("tampered-data", hmacKey);
        assertThat(hmac1).isNotEqualTo(hmacTampered);
    }

    @Test
    @DisplayName("aesGcmDecrypt: 잘못된 encData 형식 시 IllegalStateException 발생")
    void aesGcmDecrypt_shouldThrowExceptionForInvalidData() {
        // given: 잘못된 형식의 데이터 (16바이트 미만)
        byte[] key = "32bytesKey123456789012345678901".getBytes(StandardCharsets.UTF_8);
        // Base64URL 인코딩된 10바이트 (IV 16바이트 미만 → 복호화 실패)
        String invalidEncData = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("short".getBytes(StandardCharsets.UTF_8));

        // when & then
        assertThatThrownBy(() -> NiceCryptoUtil.aesGcmDecrypt(key, invalidEncData))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[NICE] AES-GCM 복호화 오류");
    }

    @Test
    @DisplayName("aesGcmDecrypt: null/빈 key 시 IllegalStateException 발생")
    void aesGcmDecrypt_shouldThrowExceptionForNullKey() {
        // given
        String validBase64UrlData = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("somedata1234567890".getBytes(StandardCharsets.UTF_8));

        // when & then: 빈 키 배열 → 잘못된 키 길이
        assertThatThrownBy(() -> NiceCryptoUtil.aesGcmDecrypt(new byte[0], validBase64UrlData))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 500, 1000, 3000})
    @DisplayName("deriveKey: 다양한 iterators 값으로도 정상 동작해야 한다")
    void deriveKey_shouldWorkWithVariousIterators(int iterators) {
        // when
        String key = NiceCryptoUtil.deriveKey(TEST_TICKET, TEST_TRANSACTION_ID, iterators);

        // then
        assertThat(key).isNotNull().isNotBlank();
        // 모든 키는 동일 길이의 Base64URL 문자열
        assertThat(key.length()).isGreaterThan(60); // 512bit = 86자 Base64URL (패딩 없음)
    }
}

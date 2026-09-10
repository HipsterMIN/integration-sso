package io.github.hipstermin.idem.common.util;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ApiKeyHashUtil — PBKDF2-HMAC-SHA256 해시 유틸 단위 테스트
 *
 * 검증 항목:
 *   1. 해시 생성 포맷 (pbkdf2: 접두사)
 *   2. 해시-검증 라운드트립 일치성
 *   3. 서로 다른 키의 해시 불일치
 *   4. 동일 키의 두 해시가 서로 다름 (Salt 무작위성)
 *   5. 잘못된 입력 처리 (null, 빈 문자열)
 *   6. CHANGEME sentinel 검출
 *   7. 손상된 해시 포맷 처리
 */
@DisplayName("ApiKeyHashUtil — PBKDF2 API Key 해시 유틸")
class ApiKeyHashUtilTest {

    private static final String VALID_KEY = "my-super-secret-api-key-12345678";

    // ─────────────────────────────────────────────────────────────
    // 1. 해시 생성
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("hash() — pbkdf2: 접두사로 시작하는 해시 생성")
    void hash_producesCorrectFormat() {
        String hash = ApiKeyHashUtil.hash(VALID_KEY);

        assertThat(hash).startsWith("pbkdf2:");
        // 포맷: pbkdf2:{iterations}:{saltBase64}:{hashBase64} → 4 parts
        assertThat(hash.split(":")).hasSize(4);
    }

    @Test
    @DisplayName("hash() — 동일 키로 두 번 해시해도 Salt 다름 (무작위성)")
    void hash_differentSaltEachTime() {
        String hash1 = ApiKeyHashUtil.hash(VALID_KEY);
        String hash2 = ApiKeyHashUtil.hash(VALID_KEY);

        // 두 해시가 서로 달라야 함 (Salt 무작위성 보장)
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("hash() — null/빈 키 입력 시 IllegalArgumentException")
    void hash_throwsOnBlankKey(String blankKey) {
        assertThatThrownBy(() -> ApiKeyHashUtil.hash(blankKey))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─────────────────────────────────────────────────────────────
    // 2. 해시 검증
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("verify() — 올바른 키+해시 쌍 → true")
    void verify_validKeyAndHash_returnsTrue() {
        String hash = ApiKeyHashUtil.hash(VALID_KEY);

        assertThat(ApiKeyHashUtil.verify(VALID_KEY, hash)).isTrue();
    }

    @Test
    @DisplayName("verify() — 잘못된 키 → false")
    void verify_wrongKey_returnsFalse() {
        String hash = ApiKeyHashUtil.hash(VALID_KEY);

        assertThat(ApiKeyHashUtil.verify("wrong-api-key", hash)).isFalse();
    }

    @Test
    @DisplayName("verify() — 다른 Salt로 생성된 해시와도 올바른 키면 true")
    void verify_sameKeyDifferentHash_bothTrue() {
        String hash1 = ApiKeyHashUtil.hash(VALID_KEY);
        String hash2 = ApiKeyHashUtil.hash(VALID_KEY);

        // 두 해시가 달라도 동일 키로 검증하면 모두 true
        assertThat(ApiKeyHashUtil.verify(VALID_KEY, hash1)).isTrue();
        assertThat(ApiKeyHashUtil.verify(VALID_KEY, hash2)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("verify() — null/빈 키 → false")
    void verify_blankKey_returnsFalse(String blankKey) {
        String hash = ApiKeyHashUtil.hash(VALID_KEY);
        assertThat(ApiKeyHashUtil.verify(blankKey, hash)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("verify() — null/빈 해시 → false")
    void verify_blankHash_returnsFalse(String blankHash) {
        assertThat(ApiKeyHashUtil.verify(VALID_KEY, blankHash)).isFalse();
    }

    // ─────────────────────────────────────────────────────────────
    // 3. 보안 Sentinel 감지
    // ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"CHANGEME", "CHANGEME_32BYTES_BASE64_PLACEHOLDER="})
    @DisplayName("verify() — CHANGEME sentinel 값 → 즉시 false")
    void verify_changeMe_returnsFalse(String sentinel) {
        assertThat(ApiKeyHashUtil.verify(VALID_KEY, sentinel)).isFalse();
    }

    // ─────────────────────────────────────────────────────────────
    // 4. 포맷 감지
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isPbkdf2Format() — pbkdf2: 접두사 → true")
    void isPbkdf2Format_validHash_returnsTrue() {
        String hash = ApiKeyHashUtil.hash(VALID_KEY);
        assertThat(ApiKeyHashUtil.isPbkdf2Format(hash)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHANGEME", "plain-text-key", "md5:abc123"})
    @DisplayName("isPbkdf2Format() — 비PBKDF2 문자열 → false")
    void isPbkdf2Format_nonPbkdf2_returnsFalse(String nonPbkdf2) {
        assertThat(ApiKeyHashUtil.isPbkdf2Format(nonPbkdf2)).isFalse();
    }

    @Test
    @DisplayName("verify() — 손상된 포맷(parts != 4) → false (예외 아님)")
    void verify_corruptedHash_returnsFalse() {
        assertThat(ApiKeyHashUtil.verify(VALID_KEY, "pbkdf2:only:twoparts")).isFalse();
        assertThat(ApiKeyHashUtil.verify(VALID_KEY, "pbkdf2:bad:BASE64!@#:hash")).isFalse();
    }

    // ─────────────────────────────────────────────────────────────
    // 5. 타이밍 일관성 (성능 상한 확인)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("verify() — 해시 검증 시간 3초 이내 (PBKDF2 과부하 방지)")
    void verify_completesWithinTimeLimit() {
        String hash = ApiKeyHashUtil.hash(VALID_KEY);

        long start = System.currentTimeMillis();
        ApiKeyHashUtil.verify(VALID_KEY, hash);
        long elapsed = System.currentTimeMillis() - start;

        // PBKDF2 310,000회 반복은 수백ms 이내에 완료되어야 함
        assertThat(elapsed).isLessThan(3_000L);
    }
}

package io.github.hipstermin.idem.common.util;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import java.util.Base64;

/**
 * API Key PBKDF2-HMAC-SHA256 해시 유틸리티
 *
 * <p>설계 원칙:
 * <ul>
 *   <li>PBKDF2WithHmacSHA256 — NIST SP 800-132 권장 알고리즘</li>
 *   <li>반복 횟수: 310,000회 — OWASP 2023 최소 권장값</li>
 *   <li>Salt: 16바이트 (128비트) — SecureRandom 생성</li>
 *   <li>Key 길이: 32바이트 (256비트)</li>
 *   <li>저장 포맷: {@code pbkdf2:{iterations}:{saltBase64}:{hashBase64}}</li>
 *   <li>타이밍 공격 방지: {@code CryptoProvider.constantTimeEquals} 상수 시간 비교</li>
 * </ul>
 *
 * <p>사용 예:
 * <pre>{@code
 * // 최초 API Key 해시 생성 (운영 키 등록 시)
 * String hash = ApiKeyHashUtil.hash("my-secret-api-key");
 * // → "pbkdf2:310000:BASE64_SALT:BASE64_HASH"
 *
 * // 검증 (수신 API Key와 저장된 해시 비교)
 * boolean valid = ApiKeyHashUtil.verify("my-secret-api-key", hash);
 * }</pre>
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html">OWASP Password Storage</a>
 */
public final class ApiKeyHashUtil {

    private static final String ALGORITHM      = "PBKDF2WithHmacSHA256";
    private static final String HASH_PREFIX    = "pbkdf2";
    private static final int    ITERATIONS     = 310_000;
    private static final int    SALT_LENGTH    = 16;   // bytes
    private static final int    KEY_LENGTH     = 256;  // bits


    private ApiKeyHashUtil() {}

    /**
     * API Key를 PBKDF2 해시로 변환한다.
     *
     * @param rawApiKey 평문 API Key
     * @return {@code pbkdf2:{iterations}:{saltBase64}:{hashBase64}} 형식의 해시 문자열
     * @throws IllegalArgumentException rawApiKey가 null 또는 빈 문자열인 경우
     */
    public static String hash(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            throw new IllegalArgumentException("rawApiKey는 null 또는 빈 문자열일 수 없습니다.");
        }

        byte[] salt = CryptoProviders.current().randomBytes(SALT_LENGTH);

        byte[] hashBytes = pbkdf2(rawApiKey.toCharArray(), salt, ITERATIONS, KEY_LENGTH);

        return HASH_PREFIX + ":" + ITERATIONS
                + ":" + Base64.getEncoder().encodeToString(salt)
                + ":" + Base64.getEncoder().encodeToString(hashBytes);
    }

    /**
     * 평문 API Key와 저장된 해시를 상수 시간 비교로 검증한다.
     *
     * @param rawApiKey   검증할 평문 API Key
     * @param storedHash  저장된 PBKDF2 해시 문자열 ({@code pbkdf2:{iterations}:{salt}:{hash}} 포맷)
     * @return 일치하면 {@code true}, 불일치 또는 형식 오류 시 {@code false}
     */
    public static boolean verify(String rawApiKey, String storedHash) {
        if (rawApiKey == null || rawApiKey.isBlank()) return false;
        if (storedHash == null || storedHash.isBlank()) return false;

        // 미설정 sentinel 값 검출 — 즉시 거부
        if ("CHANGEME".equals(storedHash) || storedHash.startsWith("CHANGEME")) {
            return false;
        }

        String[] parts = storedHash.split(":");
        if (parts.length != 4 || !HASH_PREFIX.equals(parts[0])) {
            return false;
        }

        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt     = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);

            byte[] actual = pbkdf2(rawApiKey.toCharArray(), salt, iterations, expected.length * 8);

            // 상수 시간 비교 (타이밍 공격 방지)
            return CryptoProviders.current().constantTimeEquals(expected, actual);

        } catch (Exception e) {
            // 파싱 오류 → 검증 실패 (예외 스택 미노출로 열거 공격 방지)
            return false;
        }
    }

    /**
     * 주어진 해시가 PBKDF2 포맷인지 확인한다.
     *
     * @param hash 검사할 해시 문자열
     * @return PBKDF2 형식이면 {@code true}
     */
    public static boolean isPbkdf2Format(String hash) {
        return hash != null && hash.startsWith(HASH_PREFIX + ":");
    }

    // ── 내부 PBKDF2 연산 ──────────────────────────────────────────────────────

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLengthBits) {
        // D2-b: CryptoProvider 경유 (구현체가 비밀번호 배열을 지운다)
        return CryptoProviders.current().pbkdf2HmacSha256(password, salt, iterations, keyLengthBits);
    }
}

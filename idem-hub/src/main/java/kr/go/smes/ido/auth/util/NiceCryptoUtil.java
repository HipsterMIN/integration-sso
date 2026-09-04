package kr.go.smes.ido.auth.util;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * NICE 휴대폰 본인인증 결과 복호화 유틸리티
 *
 * <p>NICE IDO 통합인증 표준 암호화 스펙을 구현:
 * <ol>
 *   <li><b>PBKDF2WithHmacSHA256</b> — Access Token의 ticket + transactionId로 AES 키 파생</li>
 *   <li><b>HMAC-SHA256</b> — 암호화 데이터 무결성 검증</li>
 *   <li><b>AES-256-GCM</b> — 인증 결과 데이터 복호화</li>
 * </ol>
 *
 * <p><b>복호화 전체 프로세스 ({@code NiceAuthService.decryptAndVerify()}):</b>
 * <pre>
 * 1. keyString = deriveKey(ticket, transactionId, iterators)
 *    → Base64URL 인코딩된 512bit(64byte) 키 문자열
 *
 * 2. aesKey   = keyString[0..31].getBytes()   (32바이트 = AES-256)
 *    hmacKey  = keyString[48..79]             (32자 HMAC 키)
 *
 * 3. calculated = hmacSha256Base64Url(encData, hmacKey)
 *    if (calculated != integrityValue) → DataIntegrityException
 *
 * 4. plaintext = aesGcmDecrypt(aesKey, encData)
 *    → JSON 문자열 (name, birthdate, gender, national_info, ci, di, mobile_co, mobile_no)
 * </pre>
 *
 * <p><b>encData 구조 (Base64URL 디코딩 후):</b>
 * <pre>
 * [0..15]  = IV (16바이트 GCM 초기화 벡터)
 * [16..]   = 암호문 + GCM 인증 태그 (128bit)
 * </pre>
 *
 * <p><b>스레드 안전성:</b> 모든 메서드가 static + 내부 상태 없음 → 스레드 안전.
 *
 * <p><b>참고 문서:</b> NICE IDO 통합인증 API 연동 가이드 v3.x 암호화 스펙
 */
public final class NiceCryptoUtil {

    /** AES 키 길이 (바이트, AES-256) */
    private static final int AES_KEY_LENGTH = 32;

    /** HMAC 키 시작 오프셋 (keyString에서 48번째 문자부터) */
    private static final int HMAC_KEY_OFFSET = 48;

    /** HMAC 키 길이 (32자) */
    private static final int HMAC_KEY_LENGTH = 32;

    /** IV 길이 (바이트, GCM) */
    private static final int GCM_IV_LENGTH = 16;

    /** GCM 인증 태그 길이 (bit) */
    private static final int GCM_TAG_LENGTH_BITS = 128;

    /** PBKDF2 키 출력 길이 (bit, 64byte = 512bit) */
    private static final int PBKDF2_KEY_LENGTH_BITS = 512;

    private NiceCryptoUtil() {
        // 인스턴스 생성 금지
    }

    /**
     * PBKDF2WithHmacSHA256으로 AES/HMAC 복합 키 파생
     *
     * <p>NICE 스펙에 따라 Access Token의 ticket을 PBKDF2 패스워드로,
     * transactionId를 솔트로 사용하여 512bit 키를 파생한다.
     * 결과를 Base64 URL-safe(패딩 없음) 인코딩하여 반환.
     *
     * <p><b>결과 문자열 사용처:</b>
     * <ul>
     *   <li>인덱스 0~31  → AES-256 키 (32바이트)</li>
     *   <li>인덱스 48~79 → HMAC-SHA256 키 (32자)</li>
     * </ul>
     *
     * @param ticket        NICE Access Token 발급 시 수신한 ticket 값 (PBKDF2 패스워드)
     * @param transactionId NICE URL 발급 응답의 transaction_id (PBKDF2 솔트)
     * @param iterators     NICE Access Token 발급 시 수신한 반복 횟수
     * @return Base64 URL-safe 인코딩된 파생 키 문자열
     * @throws IllegalStateException PBKDF2 키 생성 오류
     */
    public static String deriveKey(String ticket, String transactionId, int iterators) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    ticket.toCharArray(),
                    transactionId.getBytes(StandardCharsets.UTF_8),
                    iterators,
                    PBKDF2_KEY_LENGTH_BITS
            );
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(skf.generateSecret(spec).getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("[NICE] PBKDF2 키 파생 오류: " + e.getMessage(), e);
        }
    }

    /**
     * HMAC-SHA256으로 데이터 무결성 검증값 계산 (Base64 URL-safe)
     *
     * <p>NICE 스펙에 따라 암호화 데이터(encData)에 대한 HMAC-SHA256 해시를
     * Base64 URL-safe(패딩 없음) 인코딩하여 반환.
     * 계산 결과와 NICE 서버가 반환한 {@code integrity_value}를 비교하여
     * 데이터 무결성을 검증한다.
     *
     * @param data    HMAC을 계산할 데이터 (일반적으로 Base64URL 인코딩된 encData 문자열)
     * @param hmacKey HMAC 키 (deriveKey 결과의 인덱스 48~79)
     * @return Base64 URL-safe 인코딩된 HMAC-SHA256 값
     * @throws IllegalStateException HMAC 생성 오류
     */
    public static String hmacSha256Base64Url(String data, String hmacKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("[NICE] HMAC-SHA256 계산 오류: " + e.getMessage(), e);
        }
    }

    /**
     * AES-256-GCM으로 NICE 인증 결과 데이터 복호화
     *
     * <p>NICE 암호화 데이터 구조:
     * <pre>
     * Base64URL_decode(encData) = IV[16byte] + CipherText + GCM_Tag[16byte]
     * </pre>
     *
     * <p>복호화 순서:
     * <ol>
     *   <li>Base64 URL-safe 디코딩</li>
     *   <li>앞 16바이트를 IV로 분리</li>
     *   <li>AES-256-GCM으로 나머지 복호화</li>
     *   <li>UTF-8 문자열로 변환 (JSON 형태)</li>
     * </ol>
     *
     * @param key                AES-256 키 (32바이트, deriveKey 결과의 앞 32자 UTF-8 바이트)
     * @param encDataBase64Url   Base64 URL-safe 인코딩된 암호화 데이터
     * @return 복호화된 JSON 문자열
     * @throws IllegalStateException AES-GCM 복호화 오류 (키 불일치, 데이터 손상 등)
     */
    public static String aesGcmDecrypt(byte[] key, String encDataBase64Url) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encDataBase64Url);
            byte[] iv = Arrays.copyOfRange(decoded, 0, GCM_IV_LENGTH);
            byte[] cipherText = Arrays.copyOfRange(decoded, GCM_IV_LENGTH, decoded.length);

            SecretKey secretKey = new SecretKeySpec(key, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("[NICE] AES-GCM 복호화 오류: " + e.getMessage(), e);
        }
    }

    /**
     * 키 파생 결과에서 AES 키 바이트 배열 추출 (편의 메서드)
     *
     * @param keyString {@link #deriveKey} 결과 문자열
     * @return AES-256 키 바이트 배열 (32바이트)
     */
    public static byte[] extractAesKey(String keyString) {
        return keyString.substring(0, AES_KEY_LENGTH).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 키 파생 결과에서 HMAC 키 문자열 추출 (편의 메서드)
     *
     * @param keyString {@link #deriveKey} 결과 문자열
     * @return HMAC-SHA256 키 (32자 문자열)
     */
    public static String extractHmacKey(String keyString) {
        return keyString.substring(HMAC_KEY_OFFSET, HMAC_KEY_OFFSET + HMAC_KEY_LENGTH);
    }
}

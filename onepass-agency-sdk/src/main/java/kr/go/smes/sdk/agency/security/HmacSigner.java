package kr.go.smes.sdk.agency.security;

import kr.go.smes.sdk.agency.exception.AgencySdkException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.Charset;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * HMAC-SHA256 서명 생성/검증 유틸리티
 *
 * <p>OnePass Gateway API의 {@code X-Internal-Sig} 헤더 생성에 사용된다.
 * Sprint 17에서 서명 강제화 예정이나, 현재도 SDK에서 미리 생성하여 전송.
 *
 * <h3>서명 규칙 (OnePass 설계서 §17.2)</h3>
 * <pre>
 * 서명 대상 문자열 = "{HTTP_METHOD}\n{PATH}\n{TIMESTAMP_EPOCH_MS}\n{BODY_SHA256}"
 * X-Internal-Sig  = HEX( HMAC-SHA256(sharedSecret, 서명대상) )
 * </pre>
 *
 * <h3>상수시간 비교</h3>
 * <p>{@link #verifySignature(String, String)} 메서드는
 * {@link MessageDigest#isEqual(byte[], byte[])} 을 사용하여
 * 타이밍 공격(Timing Attack)을 방지한다.
 *
 * <p><b>JDK 버전 호환: Java 8+</b> ({@code javax.crypto} 표준 API 사용)
 */
public final class HmacSigner {

    private static final String HMAC_ALGORITHM  = "HmacSHA256";
    private static final String SHA256_ALGORITHM = "SHA-256";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final byte[] secretKeyBytes;

    /**
     * @param sharedSecret OnePass 서버와 사전 합의된 HMAC 공유 비밀키 (UTF-8 문자열)
     */
    public HmacSigner(String sharedSecret) {
        if (sharedSecret == null || sharedSecret.isEmpty()) {
            throw new IllegalArgumentException("sharedSecret must not be blank");
        }
        this.secretKeyBytes = sharedSecret.getBytes(UTF8);
    }

    // 정적 유틸 전용 — 인스턴스화 방지 (외부 접근 불가)
    // sha256Hex()는 static으로 직접 호출 가능

    /**
     * 요청 서명 생성
     *
     * @param httpMethod  HTTP 메서드 대문자 ({@code "POST"}, {@code "PATCH"})
     * @param path        API 경로 ({@code "/api/v1/agency/gateway/inbound/event"})
     * @param timestampMs 현재 시각 epoch milliseconds ({@code System.currentTimeMillis()})
     * @param requestBody 요청 본문 JSON 문자열 (빈 본문은 {@code ""})
     * @return {@code X-Internal-Sig} 헤더 값 (HEX 인코딩된 HMAC-SHA256)
     * @throws AgencySdkException HMAC 알고리즘 초기화 실패 시
     */
    public String sign(String httpMethod, String path, long timestampMs, String requestBody) {
        String bodyHash = sha256Hex(requestBody != null ? requestBody : "");
        String signingString = httpMethod + "\n" + path + "\n" + timestampMs + "\n" + bodyHash;
        return hmacSha256Hex(signingString);
    }

    /**
     * 서명 검증 (상수시간 비교)
     *
     * @param expectedSig 검증할 서명 값 ({@code X-Internal-Sig} 헤더에서 추출)
     * @param actualSig   {@link #sign}으로 직접 계산한 서명 값
     * @return {@code true} 일치 (유효), {@code false} 불일치 (위변조 의심)
     */
    public boolean verifySignature(String expectedSig, String actualSig) {
        if (expectedSig == null || actualSig == null) return false;
        // MessageDigest.isEqual: 상수시간 바이트 비교 → 타이밍 공격 방지
        return MessageDigest.isEqual(
                expectedSig.getBytes(UTF8),
                actualSig.getBytes(UTF8)
        );
    }

    // ── 내부 암호화 헬퍼 ───────────────────────────────────────────────────

    /**
     * HMAC-SHA256 계산 후 HEX 인코딩
     *
     * @param data 서명 대상 문자열
     * @return 소문자 HEX 문자열 (64자)
     */
    private String hmacSha256Hex(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKeyBytes, HMAC_ALGORITHM));
            byte[] rawHmac = mac.doFinal(data.getBytes(UTF8));
            return toHex(rawHmac);
        } catch (NoSuchAlgorithmException e) {
            // HmacSHA256는 모든 JDK 8+ 구현체에서 보장 (JCA 필수 알고리즘)
            throw new AgencySdkException("SDK_HMAC_ERROR",
                    "HmacSHA256 알고리즘 초기화 실패 (JDK 환경 이상)", e);
        } catch (InvalidKeyException e) {
            throw new AgencySdkException("SDK_HMAC_KEY_ERROR",
                    "HMAC 키 유효성 오류: " + e.getMessage(), e);
        }
    }

    /**
     * SHA-256 해시 후 HEX 인코딩 (요청 본문 해시용)
     *
     * @param data 해시 대상 문자열
     * @return 소문자 HEX 문자열 (64자)
     */
    public static String sha256Hex(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA256_ALGORITHM);
            byte[] hash = digest.digest(
                    (data != null ? data : "").getBytes(UTF8));
            return toHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JDK 구현체에서 보장
            throw new AgencySdkException("SDK_SHA256_ERROR",
                    "SHA-256 알고리즘 초기화 실패 (JDK 환경 이상)", e);
        }
    }

    /**
     * 바이트 배열 → 소문자 HEX 문자열 변환
     * <p>Java 17+의 {@code HexFormat}을 사용하지 않아 JDK 8 호환.
     */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }


}

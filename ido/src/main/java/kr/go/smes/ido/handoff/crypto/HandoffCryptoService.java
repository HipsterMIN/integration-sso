package kr.go.smes.ido.handoff.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Handoff Ticket 암호화·서명 서비스
 * 설계서 §16.4 / §24.4.1 참조
 *
 * <p>암호화: AES-256-GCM (AEAD) — encryptedPayload 생성
 * <p>서명:   HMAC-SHA256 — signature 생성
 *
 * <p>운영 시 {@code IDO_HANDOFF_AES_KEY} / {@code IDO_HANDOFF_HMAC_KEY} 환경변수 교체 필수.
 * 두 키 모두 32바이트(256-bit) Base64URL 인코딩 문자열이어야 합니다.
 */
@Slf4j
@Component
public class HandoffCryptoService {

    private static final int GCM_IV_LENGTH  = 12;  // 96-bit IV
    private static final int GCM_TAG_LENGTH = 128; // 128-bit auth tag

    @Value("${ido.ticket.aes-key:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=}")
    private String aesKeyBase64;

    @Value("${ido.ticket.hmac-key:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=}")
    private String hmacKeyBase64;

    /**
     * AES-256-GCM 암호화
     * 출력 형식: Base64URL(IV || ciphertext || GCM-tag) — 단일 연결 문자열
     *
     * @param plaintext 암호화할 평문 (JSON 직렬화된 payload)
     * @param aad       추가 인증 데이터 (ticketId — 바인딩 목적)
     * @return Base64URL 인코딩된 암호문 (IV 포함)
     */
    public String encrypt(String plaintext, String aad) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(normalizeBase64(aesKeyBase64));
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");

            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec paramSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, paramSpec);

            if (aad != null && !aad.isEmpty()) {
                cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            }

            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // IV(12) || cipherText+tag 연결 후 Base64URL 인코딩
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

            return Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
        } catch (Exception e) {
            log.error("[HandoffCrypto] AES-256-GCM 암호화 실패", e);
            throw new RuntimeException("Handoff payload 암호화 실패", e);
        }
    }

    /**
     * HMAC-SHA256 서명 생성
     * 서명 대상: ticketId + "|" + agencyCode + "|" + encryptedPayload
     *
     * @param ticketId          티켓 ID
     * @param agencyCode        기관 코드
     * @param encryptedPayload  암호화된 페이로드
     * @return Base64URL 인코딩된 HMAC-SHA256 서명값
     */
    public String sign(String ticketId, String agencyCode, String encryptedPayload) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(normalizeBase64(hmacKeyBase64));
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "HmacSHA256");

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(keySpec);

            String signingInput = ticketId + "|" + agencyCode + "|" + encryptedPayload;
            byte[] hmacBytes = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));

            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmacBytes);
        } catch (Exception e) {
            log.error("[HandoffCrypto] HMAC-SHA256 서명 실패", e);
            throw new RuntimeException("Handoff signature 생성 실패", e);
        }
    }

    /**
     * HMAC-SHA256 서명 검증
     *
     * @return true if valid
     */
    public boolean verify(String ticketId, String agencyCode,
                          String encryptedPayload, String expectedSignature) {
        try {
            String actual = sign(ticketId, agencyCode, encryptedPayload);
            return MessageDigestUtil.safeEquals(actual, expectedSignature);
        } catch (Exception e) {
            log.error("[HandoffCrypto] 서명 검증 실패", e);
            return false;
        }
    }

    // ── 내부 유틸 ────────────────────────────────────────────────────────────

    /** Base64 padding 정규화 (URL-safe / standard 모두 허용) */
    private String normalizeBase64(String b64) {
        // URL-safe → standard, padding 추가
        String std = b64.replace('-', '+').replace('_', '/');
        int pad = std.length() % 4;
        if (pad == 2) std += "==";
        else if (pad == 3) std += "=";
        return std;
    }

    /**
     * 상수 시간(constant-time) 문자열 비교 — timing-attack 방지
     */
    static final class MessageDigestUtil {
        static boolean safeEquals(String a, String b) {
            if (a == null || b == null) return false;
            byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
            byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
            if (aBytes.length != bBytes.length) return false;
            int diff = 0;
            for (int i = 0; i < aBytes.length; i++) {
                diff |= aBytes[i] ^ bBytes[i];
            }
            return diff == 0;
        }
    }
}

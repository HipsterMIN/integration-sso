package kr.go.smes.qim.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * CI(연계정보) AES-256-GCM 암호화·복호화 구현체
 *
 * <p>키 버전 관리 전략:
 * <pre>
 *   암호화 출력: v1.{base64url(12-byte IV)}.{base64url(ciphertext+16-byte GCM tag)}
 *   복호화:      버전 파싱 → 해당 키 선택 → 복호화
 * </pre>
 *
 * <p>운영 환경변수:
 * <ul>
 *   <li>QIM_CI_AES_KEY_V1 — 32바이트 Base64 키 (필수)</li>
 *   <li>QIM_CI_AES_KEY_V2 — 로테이션 키 (선택)</li>
 *   <li>QIM_CI_CURRENT_KEY_VERSION — 현재 버전 (기본 v1)</li>
 * </ul>
 */
@Slf4j
@Component
public class CiCryptoServiceImpl implements CiCryptoService {

    private static final int    GCM_IV_LENGTH  = 12;   // 96-bit IV
    private static final int    GCM_TAG_LENGTH = 128;  // 128-bit auth tag
    private static final String ALGORITHM      = "AES/GCM/NoPadding";
    private static final String VERSION_PREFIX_PATTERN = "^v\\d+\\..+\\..+$";

    @Value("${qim.crypto.ci.key-v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=}")
    private String aesKeyV1Base64;

    @Value("${qim.crypto.ci.key-v2:}")
    private String aesKeyV2Base64;

    @Value("${qim.crypto.ci.current-version:v1}")
    private String currentVersion;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // ── Public API ────────────────────────────────────────────────────────

    @Override
    public String encrypt(String rawCi) {
        if (rawCi == null || rawCi.isBlank()) {
            throw new IllegalArgumentException("CI 값이 비어있습니다.");
        }
        try {
            byte[] keyBytes = resolveKey(currentVersion);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");

            byte[] iv = new byte[GCM_IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] cipherBytes = cipher.doFinal(rawCi.getBytes(StandardCharsets.UTF_8));

            String ivB64  = Base64.getUrlEncoder().withoutPadding().encodeToString(iv);
            String ctB64  = Base64.getUrlEncoder().withoutPadding().encodeToString(cipherBytes);

            return currentVersion + "." + ivB64 + "." + ctB64;
        } catch (Exception e) {
            log.error("[CiCrypto] AES-256-GCM 암호화 실패", e);
            throw new CiCryptoException("CI 암호화 실패", e);
        }
    }

    @Override
    public String decrypt(String encryptedCi) {
        if (encryptedCi == null || encryptedCi.isBlank()) {
            throw new IllegalArgumentException("암호화된 CI 값이 비어있습니다.");
        }
        if (!isEncrypted(encryptedCi)) {
            log.warn("[CiCrypto] 암호화되지 않은 CI 값 복호화 시도 — 평문 그대로 반환");
            return encryptedCi;
        }
        try {
            String[] parts   = encryptedCi.split("\\.", 3);
            String version   = parts[0];
            byte[] iv        = Base64.getUrlDecoder().decode(parts[1]);
            byte[] cipherBytes = Base64.getUrlDecoder().decode(parts[2]);

            byte[] keyBytes  = resolveKey(version);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] plain = cipher.doFinal(cipherBytes);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[CiCrypto] AES-256-GCM 복호화 실패", e);
            throw new CiCryptoException("CI 복호화 실패", e);
        }
    }

    @Override
    public boolean isEncrypted(String value) {
        return value != null && value.matches(VERSION_PREFIX_PATTERN);
    }

    // ── Private ────────────────────────────────────────────────────────────

    private byte[] resolveKey(String version) {
        Map<String, String> keyMap = buildKeyMap();
        String b64 = keyMap.get(version);
        if (b64 == null || b64.isBlank()) {
            throw new CiCryptoException("CI 암호화 키 버전 '" + version + "'을 찾을 수 없습니다.");
        }
        return Base64.getDecoder().decode(normalizeBase64(b64));
    }

    private Map<String, String> buildKeyMap() {
        var map = new java.util.HashMap<String, String>();
        if (aesKeyV1Base64 != null && !aesKeyV1Base64.isBlank()) map.put("v1", aesKeyV1Base64);
        if (aesKeyV2Base64 != null && !aesKeyV2Base64.isBlank()) map.put("v2", aesKeyV2Base64);
        return map;
    }

    private String normalizeBase64(String b64) {
        String std = b64.replace('-', '+').replace('_', '/');
        int pad = std.length() % 4;
        if (pad == 2) std += "==";
        else if (pad == 3) std += "=";
        return std;
    }

    /** CI 암호화 전용 예외 */
    public static class CiCryptoException extends RuntimeException {
        public CiCryptoException(String msg) { super(msg); }
        public CiCryptoException(String msg, Throwable cause) { super(msg, cause); }
    }
}

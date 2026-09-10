package io.github.hipstermin.idem.registry.crypto;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 *
 * <p>부팅 검증 (Sprint γ-2 / F3.3):
 * <ul>
 *   <li>현재 버전(v1 기본) AES 키가 비어있으면 부팅 차단 (CrashLoopBackOff)</li>
 *   <li>과거 placeholder default("AAAA...=" — 32바이트 0x00) 명시적 거부</li>
 *   <li>Base64 디코드 후 정확히 32바이트(AES-256)인지 검증</li>
 *   <li>로컬·테스트는 {@code qim.crypto.ci.allow-empty-key=true} 로 우회</li>
 * </ul>
 */
@Slf4j
@Component
public class CiCryptoServiceImpl implements CiCryptoService {

    private static final int    GCM_IV_LENGTH  = 12;   // 96-bit IV
    private static final int    GCM_TAG_LENGTH = 128;  // 128-bit auth tag
    private static final int    AES_256_KEY_BYTES = 32; // 256-bit key
    private static final String ALGORITHM      = "AES/GCM/NoPadding";
    private static final String VERSION_PREFIX_PATTERN = "^v\\d+\\..+\\..+$";

    /**
     * 부팅을 차단해야 하는 placeholder 값 (대소문자·공백 무시 비교).
     *
     * <p>특히 {@code "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="} 는
     * γ-1 이전까지 application.yml 에 박혀있던 32바이트 0x00 키의 Base64 인코딩으로,
     * 운영에 그대로 누출되면 CI 암호문이 사실상 단일 평문 키로 보호되는 셈이 된다.
     * 재실수를 영구 차단하기 위해 명시적으로 포함한다.
     */
    private static final Set<String> FORBIDDEN_PLACEHOLDERS = Set.of(
            "change-me",
            "changeme",
            "default",
            "secret",
            "test",
            // 32바이트 0x00 키 (legacy default — γ-2 이전 application.yml line 144)
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa="
    );

    @Value("${qim.crypto.ci.key-v1:}")
    private String aesKeyV1Base64;

    @Value("${qim.crypto.ci.key-v2:}")
    private String aesKeyV2Base64;

    @Value("${qim.crypto.ci.current-version:v1}")
    private String currentVersion;

    /**
     * 로컬·테스트 전용 escape hatch.
     * <p>{@code true} 일 때만 빈 값 / placeholder 가 허용된다. 운영에서는 절대 사용 금지.
     */
    @Value("${qim.crypto.ci.allow-empty-key:false}")
    private boolean allowEmptyKey;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // ── 부팅 검증 (Sprint γ-2 / F3.3) ─────────────────────────────────────────

    /**
     * Spring 컨테이너 부팅 시점에 호출되어 현재 버전 AES 키의 유효성을 강제 검증.
     *
     * <p>검증 실패 시 {@link IllegalStateException} 으로 ApplicationContext 초기화를 중단,
     * 컨테이너는 CrashLoopBackOff 로 전환되어 운영자가 즉시 인지할 수 있도록 한다.
     *
     * <p>검증 항목:
     * <ol>
     *   <li>현재 버전({@link #currentVersion}) 에 해당하는 키가 null/blank 아닐 것</li>
     *   <li>{@link #FORBIDDEN_PLACEHOLDERS} 에 포함되지 않을 것 (대소문자·공백 무시)</li>
     *   <li>Base64 디코드 가능하며, 결과가 정확히 32바이트(AES-256) 일 것</li>
     * </ol>
     *
     * <p>{@code qim.crypto.ci.allow-empty-key=true} 가 명시되면 검증을 건너뛴다
     * (로컬/단위 테스트 한정).
     */
    @PostConstruct
    void validateKeyV1() {
        String version = (currentVersion == null || currentVersion.isBlank()) ? "v1" : currentVersion.trim();
        String keyB64  = "v2".equals(version) ? aesKeyV2Base64 : aesKeyV1Base64;
        String label   = "qim.crypto.ci.key-" + version;

        if (keyB64 == null || keyB64.isBlank()) {
            if (allowEmptyKey) {
                log.warn("[CiCrypto] {} 가 비어있지만 allow-empty-key=true 로 우회 (로컬/테스트 전용). 운영 환경에서는 절대 허용 금지.", label);
                return;
            }
            throw new IllegalStateException(
                    "[CiCrypto] " + label + " 가 설정되지 않았습니다. "
                            + "환경변수 QIM_CI_AES_KEY_" + version.toUpperCase() + " 를 32바이트 Base64 키로 주입하십시오. "
                            + "(예: openssl rand -base64 32). "
                            + "로컬·테스트에서만 qim.crypto.ci.allow-empty-key=true 로 우회 가능합니다.");
        }

        String normalized = keyB64.trim().toLowerCase();
        if (FORBIDDEN_PLACEHOLDERS.contains(normalized)) {
            throw new IllegalStateException(
                    "[CiCrypto] " + label + " 에 placeholder 값('" + keyB64 + "')이 설정되어 있습니다. "
                            + "이 값은 과거 default 또는 더미 키로 운영에 사용해서는 안 됩니다. "
                            + "openssl rand -base64 32 로 생성한 32바이트 무작위 키를 주입하십시오.");
        }

        // Base64 디코드 + 32바이트 검증
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(normalizeBase64(keyB64.trim()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "[CiCrypto] " + label + " 가 유효한 Base64 가 아닙니다. "
                            + "표준 Base64 또는 Base64URL 형식의 32바이트 키를 주입하십시오.", e);
        }

        if (decoded.length != AES_256_KEY_BYTES) {
            throw new IllegalStateException(
                    "[CiCrypto] " + label + " 디코드 결과가 " + decoded.length + " 바이트입니다. "
                            + "AES-256 은 정확히 32바이트 키를 요구합니다. "
                            + "openssl rand -base64 32 로 32바이트 키를 생성하여 주입하십시오.");
        }

        log.info("[CiCrypto] 부팅 검증 통과 — 현재 키 버전={}, 키 길이={}바이트(AES-256)", version, decoded.length);
    }

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

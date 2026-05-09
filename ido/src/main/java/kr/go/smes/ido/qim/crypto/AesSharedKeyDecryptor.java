package kr.go.smes.ido.qim.crypto;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Q-IM 공유키 기반 AES 복호화 유틸
 *
 * <p>Q-IM 명세서 v1.52 §2.2 / §4.1:
 * Q-IM과 SP 간 민감정보(CI 등)는 AES 공유키로 암호화하여 전송.
 * SP(=IdO)는 동일한 공유키로 복호화.
 *
 * <p>설정값:
 * <ul>
 *   <li>{@code ido.qim.aes-shared-key} — Base64 인코딩된 AES 공유키 (Q-IM 관리 콘솔 발급, 32바이트)</li>
 *   <li>{@code ido.qim.aes-transformation} — 암호화 모드 (Q-IM 팀과 합의 후 설정, 기본: AES/CBC/PKCS5Padding)</li>
 *   <li>{@code ido.qim.aes-iv-length} — IV 길이 (기본: 16바이트)</li>
 * </ul>
 *
 * <p>[합의 필요 #1] Q-IM 팀과 아래 항목 협의 완료 후 환경변수로 확정:
 * <ul>
 *   <li>AES 모드: CBC / GCM / ECB 중 선택</li>
 *   <li>패딩: PKCS5Padding / NoPadding</li>
 *   <li>IV 전달 방식: 암호문 앞 첨부(PREPEND) / 별도 필드(SEPARATE)</li>
 * </ul>
 *
 * @see <a href="docs/qim-ido-integration-architecture.md">Q-IM↔IdO 아키텍처 §7.2</a>
 */
@Slf4j
@Component
public class AesSharedKeyDecryptor {

    private static final String ALGORITHM = "AES";

    /** AES 암호화 모드/패딩 (환경변수 QIM_AES_TRANSFORMATION으로 주입, Q-IM 팀 합의 필요) */
    private final String transformation;
    /** IV 바이트 길이 (환경변수 QIM_AES_IV_LENGTH, 기본 16) */
    private final int ivLength;

    private final byte[] sharedKeyBytes;
    private final String rawAesSharedKey;

    /**
     * @param aesSharedKey    Base64 인코딩된 AES 공유키 (Q-IM 관리 콘솔에서 발급, 32바이트)
     * @param transformation  AES 변환 문자열 (Q-IM 팀 합의 필요, 기본: AES/CBC/PKCS5Padding)
     * @param ivLength        IV 길이 (기본: 16바이트)
     */
    public AesSharedKeyDecryptor(
            @Value("${ido.qim.aes-shared-key:CHANGEME_32BYTES_BASE64_PLACEHOLDER=}") String aesSharedKey,
            @Value("${ido.qim.aes-transformation:AES/CBC/PKCS5Padding}") String transformation,
            @Value("${ido.qim.aes-iv-length:16}") int ivLength) {
        this.rawAesSharedKey = aesSharedKey;
        this.transformation  = transformation;
        this.ivLength        = ivLength;
        this.sharedKeyBytes  = Base64.getDecoder().decode(aesSharedKey);
    }

    /**
     * 기동 시 설정값 검증 — 치명적 설정 오류 조기 차단
     *
     * <p>운영 환경에서 CHANGEME 기본값이 설정된 채 기동되는 것을 방지.
     * 키 길이가 32바이트(AES-256)가 아닌 경우도 경고 발생.
     */
    @PostConstruct
    void validateConfiguration() {
        // AES 공유키 검증
        if ("CHANGEME_32BYTES_BASE64_PLACEHOLDER=".equals(rawAesSharedKey)
                || rawAesSharedKey.startsWith("CHANGEME")) {
            log.error("[QIM-CRYPTO][보안경고] QIM_AES_SHARED_KEY가 기본값(CHANGEME)입니다. " +
                      "운영 환경에서는 반드시 Q-IM 관리 콘솔에서 발급된 실제 키를 설정하세요. " +
                      "현재 AES 복호화 기능이 비활성화됩니다.");
        }

        if (sharedKeyBytes.length != 32) {
            log.error("[QIM-CRYPTO][보안경고] AES 공유키 길이 오류: 실제={}바이트, 요구=32바이트(AES-256). " +
                      "Q-IM 팀에서 발급된 올바른 키를 사용하세요.", sharedKeyBytes.length);
        }

        // Transformation 검증
        if (!transformation.startsWith("AES/")) {
            log.error("[QIM-CRYPTO][설정오류] ido.qim.aes-transformation 값이 올바르지 않습니다: {}. " +
                      "AES/CBC/PKCS5Padding 또는 AES/GCM/NoPadding 형식이어야 합니다.", transformation);
        }

        log.info("[QIM-CRYPTO] AES 설정 로드 완료: transformation={} ivLength={}",
                 transformation, ivLength);
    }

    /**
     * Q-IM이 전송한 암호화된 CI(encCi)를 복호화한다.
     *
     * <p>기대 형식: {@code Base64(IV[ivLength bytes] || CipherText)}
     * IV는 암호화 시 랜덤 생성하여 암호문 앞에 붙여 전송하는 방식을 적용.
     *
     * <p>사용 알고리즘은 {@code ido.qim.aes-transformation} 설정을 따른다
     * (Q-IM 팀과 합의 후 환경변수로 확정).
     *
     * @param encryptedValue AES 암호화된 값 (Base64 인코딩)
     * @return 복호화된 평문 문자열
     * @throws QimDecryptionException AES 복호화 실패 시 또는 키 미설정 시
     */
    public String decrypt(String encryptedValue) {
        if (encryptedValue == null || encryptedValue.isBlank()) {
            throw new QimDecryptionException("암호화된 값이 null 또는 빈 문자열입니다.");
        }

        // 키 미설정 보호
        if (sharedKeyBytes.length != 32) {
            throw new QimDecryptionException(
                "AES 공유키가 올바르게 설정되지 않았습니다. Q-IM 팀에서 발급된 32바이트 키를 설정하세요.");
        }

        try {
            byte[] encryptedBytes = Base64.getDecoder().decode(encryptedValue);

            if (encryptedBytes.length < ivLength) {
                throw new QimDecryptionException(
                    "암호화된 데이터가 너무 짧습니다 (IV 포함 최소 " + ivLength + "바이트 필요).");
            }

            // IV 추출 (앞 ivLength 바이트)
            byte[] iv = new byte[ivLength];
            System.arraycopy(encryptedBytes, 0, iv, 0, ivLength);

            // 실제 암호문
            byte[] cipherTextBytes = new byte[encryptedBytes.length - ivLength];
            System.arraycopy(encryptedBytes, ivLength, cipherTextBytes, 0, cipherTextBytes.length);

            SecretKey secretKey = new SecretKeySpec(sharedKeyBytes, ALGORITHM);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            Cipher cipher = Cipher.getInstance(transformation);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);

            byte[] plainBytes = cipher.doFinal(cipherTextBytes);
            return new String(plainBytes, StandardCharsets.UTF_8);

        } catch (QimDecryptionException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[QIM-CRYPTO] AES 복호화 실패 — 공유키 불일치 또는 형식 오류. " +
                     "transformation={} cause={}", transformation, e.getMessage());
            throw new QimDecryptionException(
                "AES 복호화에 실패했습니다. Q-IM 공유키와 암호화 형식(" + transformation + ")을 확인하세요.", e);
        }
    }

    /**
     * 복호화된 CI로부터 identifierHash(SHA-256) 생성
     *
     * @param plainCi 복호화된 CI 평문 (88바이트 정상 CI)
     * @return SHA-256(CI) — Hex 문자열
     */
    public String computeIdentifierHash(String plainCi) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(plainCi.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new QimDecryptionException("identifierHash 생성 실패", e);
        }
    }

    /**
     * 사업자등록번호 기반 식별자 해시 (기업회원용)
     *
     * @param brno 사업자등록번호 (숫자 10자리)
     * @return SHA-256(brno) — Hex 문자열
     */
    public String computeIdentifierHashFromBrno(String brno) {
        if (brno == null || brno.isBlank()) {
            throw new QimDecryptionException("사업자등록번호가 null 또는 빈 문자열입니다.");
        }
        // 하이픈 제거 후 해시
        String normalizedBrno = brno.replaceAll("[^0-9]", "");
        return computeIdentifierHash("BRNO:" + normalizedBrno);
    }

    // ── 내부 예외 클래스 ──────────────────────────────────────────────────────
    public static class QimDecryptionException extends RuntimeException {
        public QimDecryptionException(String message) {
            super(message);
        }
        public QimDecryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

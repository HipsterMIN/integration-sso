package kr.go.smes.ido.qim.crypto;

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
 * Q-IM 명세서 v1.52 §2.2 / §4.1 참조:
 *   - Q-IM과 SP 간 민감정보(CI 등)는 AES 공유키로 암호화하여 전송
 *   - SP(=IdO)는 동일한 공유키로 복호화
 *
 * 알고리즘: AES-256-CBC (Q-IM 팀 확인 필요 ← [합의 필요 #1])
 *   - 정확한 모드/패딩/IV 전달 방식은 Q-IM 팀과 합의 후 확정
 *   - 현재 구현: AES/CBC/PKCS5Padding, IV는 암호문 앞 16바이트로 가정
 *
 * @see <a href="docs/qim-ido-integration-architecture.md">Q-IM↔IdO 아키텍처 §7.2</a>
 */
@Slf4j
@Component
public class AesSharedKeyDecryptor {

    private static final String ALGORITHM = "AES";
    // TODO: Q-IM 팀 확인 후 정확한 transformation 문자열 확정 [합의 필요 #1]
    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final int IV_LENGTH = 16;

    private final byte[] sharedKeyBytes;

    /**
     * @param aesSharedKey Base64 인코딩된 AES 공유키 (Q-IM 관리 콘솔에서 발급)
     */
    public AesSharedKeyDecryptor(
            @Value("${ido.qim.aes-shared-key:CHANGEME_32BYTES_BASE64_PLACEHOLDER=}") String aesSharedKey) {
        this.sharedKeyBytes = Base64.getDecoder().decode(aesSharedKey);
        if (this.sharedKeyBytes.length != 32) {
            log.warn("[QIM-CRYPTO] AES 공유키 길이가 32바이트가 아닙니다. "
                    + "실제 길이={}. Q-IM 팀과 키 형식을 확인하세요.", this.sharedKeyBytes.length);
        }
    }

    /**
     * Q-IM이 전송한 암호화된 CI(encCi)를 복호화한다.
     *
     * <p>기대 형식: Base64(IV[16bytes] || CipherText)
     * IV는 암호화 시 랜덤 생성하여 암호문 앞에 붙여 전송하는 방식을 가정.</p>
     *
     * @param encryptedValue AES 암호화된 값 (Base64 인코딩)
     * @return 복호화된 평문 문자열
     * @throws QimDecryptionException AES 복호화 실패 시
     */
    public String decrypt(String encryptedValue) {
        if (encryptedValue == null || encryptedValue.isBlank()) {
            throw new QimDecryptionException("암호화된 값이 null 또는 빈 문자열입니다.");
        }
        try {
            byte[] encryptedBytes = Base64.getDecoder().decode(encryptedValue);

            if (encryptedBytes.length < IV_LENGTH) {
                throw new QimDecryptionException("암호화된 데이터가 너무 짧습니다 (IV 포함 최소 " + IV_LENGTH + "바이트 필요).");
            }

            // IV 추출 (앞 16바이트)
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(encryptedBytes, 0, iv, 0, IV_LENGTH);

            // 실제 암호문
            byte[] cipherTextBytes = new byte[encryptedBytes.length - IV_LENGTH];
            System.arraycopy(encryptedBytes, IV_LENGTH, cipherTextBytes, 0, cipherTextBytes.length);

            SecretKey secretKey = new SecretKeySpec(sharedKeyBytes, ALGORITHM);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);

            byte[] plainBytes = cipher.doFinal(cipherTextBytes);
            return new String(plainBytes, StandardCharsets.UTF_8);

        } catch (QimDecryptionException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[QIM-CRYPTO] AES 복호화 실패 — 공유키 불일치 또는 형식 오류. cause={}", e.getMessage());
            throw new QimDecryptionException("AES 복호화에 실패했습니다. Q-IM 공유키와 암호화 형식을 확인하세요.", e);
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

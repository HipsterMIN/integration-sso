package kr.go.smes.ido.crypto.kms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * NoOp KMS 클라이언트 — 개발/테스트 환경용 패스스루 구현 (S9-T8)
 *
 * <p><b>동작 방식</b>:
 * <ul>
 *   <li>{@code decrypt(base64)} → Base64 디코딩만 수행 (실제 KMS 호출 없음)</li>
 *   <li>{@code encrypt(bytes)} → Base64 인코딩만 수행</li>
 * </ul>
 *
 * <p><b>적용 환경</b>: Spring Profile {@code !prod} (dev, local, test)
 *
 * <p><b>보안 주의</b>:
 * 이 구현체는 키 재료를 암호화하지 않으므로
 * 운영 환경에서 절대 사용 금지. {@code @Profile("!prod")} 로 제한됨.
 *
 * @see AwsKmsClient
 */
@Slf4j
@Component
@Primary
@Profile("!prod")
public class NoOpKmsClient implements KmsClient {

    @Override
    public byte[] decrypt(String encryptedKeyBase64) {
        log.debug("[KMS-NoOp] 패스스루 복호화 (Base64 디코딩만 수행)");
        try {
            String normalized = encryptedKeyBase64
                    .replace('-', '+').replace('_', '/');
            int pad = normalized.length() % 4;
            if (pad == 2) normalized += "==";
            else if (pad == 3) normalized += "=";
            return Base64.getDecoder().decode(normalized);
        } catch (Exception e) {
            throw new KmsDecryptException("NoOp KMS 복호화 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public String encrypt(byte[] plainKeyBytes) {
        log.debug("[KMS-NoOp] 패스스루 암호화 (Base64 인코딩만 수행)");
        return Base64.getEncoder().encodeToString(plainKeyBytes);
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public String providerName() {
        return "noop";
    }
}

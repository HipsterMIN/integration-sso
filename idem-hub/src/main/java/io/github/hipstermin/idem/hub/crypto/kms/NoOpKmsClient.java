package io.github.hipstermin.idem.hub.crypto.kms;

import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * NoOp KMS 클라이언트 — 개발/테스트 환경용 패스스루 구현 (S9-T8)
 *
 * <p><b>동작 방식</b>:
 * <ul>
 *   <li>{@code decrypt(base64)} → Base64 디코딩만 수행 (실제 KMS 호출 없음)</li>
 *   <li>{@code encrypt(bytes)} → Base64 인코딩만 수행</li>
 * </ul>
 *
 * <p><b>적용 환경</b>: {@code ido.kms.enabled=true AND ido.kms.provider=noop}
 * (provider를 명시적으로 noop으로 설정한 경우만 활성화)
 * 테스트에서는 관련 테스트 코드에서 직접 인스턴스화하여 사용하면 된다.
 * KMS 자체를 비활성화하려면 {@code enabled=false}로
 * 설정하여 {@link LocalKmsClient}를 사용하라.
 *
 * <p><b>보안 주의</b>:
 * 이 구현체는 키 재료를 암호화하지 않으므로
 * 운영 환경에서 절대 사용 금지.
 *
 * @see LocalKmsClient  (KMS Off 모드 — enabled=false, 동일한 Base64 패스스루)
 * @see VaultKmsClient  (운영 표준 — provider=vault)
 */
@Slf4j
@Component
@Primary   // 일반 KMS(NoOp/Local/Nhn/Vault)는 ido.kms.provider 로 상호배타 활성 — 단일 KmsClient 주입의 정본
@ConditionalOnProperty(
    prefix      = "ido.kms",
    name        = {"enabled", "provider"},
    havingValue = "true,noop"
)
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

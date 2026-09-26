package io.github.hipstermin.idem.hub.crypto.kms;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 로컬 마스터 키 KMS ({@code provider=local}, 1.0.1) — 외부 KMS(Vault) 없이 회전된 Handoff 키 재료를 <b>암호화해</b> 저장한다.
 *
 * <p>3차 점검 M6 후속: 설치본(compose·Helm)이 {@code prod} 프로파일로 뜨게 하자 hub 가 기동을 거부했다 — {@link LocalKmsClient}(KMS Off,
 * 평문 저장)는 {@code prod}/{@code stage} 에서 빈 등록이 금지되고, 설치본에는 Vault 가 없기 때문이다. 이 구현은 그 사이를 메운다:
 * 키 재료를 설치본 비밀 {@code IDEM_HUB_KMS_MASTER_KEY}(base64 32바이트)로 AES-256-GCM 봉인한다. 마스터 키는 Handoff v1 키
 * ({@code IDEM_HUB_HANDOFF_AES_KEY} 등)와 같은 신뢰 수준(환경 비밀)이며, Vault 를 붙일 수 있으면 {@code provider=vault} 가 여전히 권장이다.
 *
 * <p>저장 형식: {@code local:v1:} + Base64(iv(12) || ciphertext || tag). AAD 로 형식 문자열을 묶어 다른 용도의 암호문과 섞이지 않게 한다.
 *
 * <p><b>업그레이드 호환</b>: 1.0 설치본(KMS Off)이 남긴 {@code key_material_encrypted} 는 접두 없는 Base64 평문이다. {@code accept-legacy-plaintext=true}
 * (기본)면 그 값을 WARN 과 함께 그대로 디코딩한다 — 회전 뒤 새 재료는 봉인되어 저장되고 옛 행은 유예 기간이 지나면 더 읽히지 않는다.
 * 새 설치본은 false 로 두어 평문 재료가 절대 통하지 않게 할 수 있다.
 */
@Slf4j
@Component
@Primary   // 일반 KMS(NoOp/Local/Nhn/Vault/LocalMasterKey)는 idem.hub.kms.provider 로 상호배타 활성 — 단일 KmsClient 주입의 정본
// 1.0.1: @ConditionalOnProperty(name={"enabled","provider"}, havingValue="true,local") 는 두 속성이 각각 "true,local" 와 같아야 해 절대 참이 되지 않았다
@ConditionalOnExpression("'${idem.hub.kms.enabled:false}' == 'true' && '${idem.hub.kms.provider:}' == 'local'")
public class LocalMasterKeyKmsClient implements KmsClient {

    static final String PREFIX = "local:v1:";
    private static final byte[] AAD = "idem-hub-kms-local:v1".getBytes(StandardCharsets.UTF_8);
    private static final int IV_LENGTH = 12;

    private final byte[] masterKey;
    private final boolean acceptLegacyPlaintext;

    public LocalMasterKeyKmsClient(@Value("${idem.hub.kms.local.master-key:}") String masterKeyBase64,
                                   @Value("${idem.hub.kms.local.accept-legacy-plaintext:true}") boolean acceptLegacyPlaintext) {
        this.masterKey = parseMasterKey(masterKeyBase64);
        this.acceptLegacyPlaintext = acceptLegacyPlaintext;
    }

    /** 부팅 가드 — 마스터 키가 없거나 32바이트가 아니면 기동 거부(D2 fail-secure) */
    static byte[] parseMasterKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException("[KMS-Local] idem.hub.kms.provider=local 인데 마스터 키가 없습니다 — IDEM_HUB_KMS_MASTER_KEY(base64 32바이트, openssl rand -base64 32) 를 설정하세요");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("[KMS-Local] IDEM_HUB_KMS_MASTER_KEY 가 base64 가 아닙니다", e);
        }
        if (key.length != 32) {
            throw new IllegalStateException("[KMS-Local] IDEM_HUB_KMS_MASTER_KEY 는 32바이트여야 합니다 (현재 " + key.length + "바이트)");
        }
        return key;
    }

    @PostConstruct
    void logMode() {
        log.info("[KMS-Local] 로컬 마스터 키 KMS 활성 — 키 재료를 AES-256-GCM 으로 봉인해 저장합니다 (legacy 평문 허용={})", acceptLegacyPlaintext);
    }

    @Override
    public String encrypt(byte[] plainKeyBytes) {
        try {
            byte[] iv = CryptoProviders.current().randomBytes(IV_LENGTH);
            byte[] ct = CryptoProviders.current().aesGcmEncrypt(masterKey, iv, plainKeyBytes, AAD);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (RuntimeException e) {
            throw new KmsEncryptException("로컬 마스터 키 봉인 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] decrypt(String encryptedKeyBase64) {
        if (encryptedKeyBase64 == null || encryptedKeyBase64.isBlank()) {
            throw new KmsDecryptException("빈 키 재료", null);
        }
        if (!encryptedKeyBase64.startsWith(PREFIX)) {
            if (!acceptLegacyPlaintext) {
                throw new KmsDecryptException("봉인되지 않은(1.0 KMS Off) 키 재료 — idem.hub.kms.local.accept-legacy-plaintext=false", null);
            }
            log.warn("[KMS-Local] 봉인되지 않은 키 재료(1.0 설치본 KMS Off 시절)를 읽습니다 — 다음 키 회전부터는 봉인되어 저장됩니다");
            try {
                return Base64.getDecoder().decode(encryptedKeyBase64.trim().replace('-', '+').replace('_', '/'));
            } catch (IllegalArgumentException e) {
                throw new KmsDecryptException("legacy 키 재료가 base64 가 아닙니다", e);
            }
        }
        try {
            byte[] all = Base64.getDecoder().decode(encryptedKeyBase64.substring(PREFIX.length()));
            if (all.length <= IV_LENGTH) throw new KmsDecryptException("키 재료가 너무 짧습니다", null);
            byte[] iv = new byte[IV_LENGTH];
            byte[] ct = new byte[all.length - IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            System.arraycopy(all, IV_LENGTH, ct, 0, ct.length);
            return CryptoProviders.current().aesGcmDecrypt(masterKey, iv, ct, AAD);
        } catch (KmsDecryptException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new KmsDecryptException("로컬 마스터 키 복호화 실패(키가 바뀌었거나 변조): " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isHealthy() {
        return masterKey.length == 32;
    }

    @Override
    public String providerName() {
        return "local";
    }
}

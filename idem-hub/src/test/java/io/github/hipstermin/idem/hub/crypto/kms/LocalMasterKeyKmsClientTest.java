package io.github.hipstermin.idem.hub.crypto.kms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LocalMasterKeyKmsClient — provider=local (1.0.1): 마스터 키 AES-GCM 봉인, legacy 평문 호환, 부팅 가드")
class LocalMasterKeyKmsClientTest {

    static String key32() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    @Test
    void roundTrip_andCiphertextDiffersPerCall() {
        LocalMasterKeyKmsClient c = new LocalMasterKeyKmsClient(key32(), true);
        byte[] dek = new byte[32];
        new SecureRandom().nextBytes(dek);
        String e1 = c.encrypt(dek);
        String e2 = c.encrypt(dek);
        assertThat(e1).startsWith("local:v1:");
        assertThat(e1).isNotEqualTo(e2);   // IV 무작위
        assertThat(c.decrypt(e1)).isEqualTo(dek);
        assertThat(c.decrypt(e2)).isEqualTo(dek);
        assertThat(c.isHealthy()).isTrue();
        assertThat(c.providerName()).isEqualTo("local");
    }

    @Test
    void tamperedOrWrongKey_isRejected() {
        String master = key32();
        LocalMasterKeyKmsClient c = new LocalMasterKeyKmsClient(master, true);
        String sealed = c.encrypt(new byte[] {1, 2, 3});
        byte[] raw = Base64.getDecoder().decode(sealed.substring("local:v1:".length()));
        raw[raw.length - 1] ^= 0x01;
        String tampered = "local:v1:" + Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> c.decrypt(tampered)).isInstanceOf(KmsClient.KmsDecryptException.class);
        LocalMasterKeyKmsClient other = new LocalMasterKeyKmsClient(key32(), true);
        assertThatThrownBy(() -> other.decrypt(sealed)).isInstanceOf(KmsClient.KmsDecryptException.class);
        assertThatThrownBy(() -> c.decrypt("local:v1:AAAA")).isInstanceOf(KmsClient.KmsDecryptException.class);
    }

    @Test
    void legacyPlaintext_acceptedOnlyWhenAllowed() {
        byte[] dek = new byte[32];
        new SecureRandom().nextBytes(dek);
        String legacy = Base64.getEncoder().encodeToString(dek);   // 1.0 KMS Off 가 남긴 형식
        assertThat(new LocalMasterKeyKmsClient(key32(), true).decrypt(legacy)).isEqualTo(dek);
        assertThatThrownBy(() -> new LocalMasterKeyKmsClient(key32(), false).decrypt(legacy))
                .isInstanceOf(KmsClient.KmsDecryptException.class).hasMessageContaining("accept-legacy-plaintext");
    }

    @Test
    void bootGuard_masterKeyRequired32Bytes() {
        assertThatThrownBy(() -> new LocalMasterKeyKmsClient("", true)).isInstanceOf(IllegalStateException.class).hasMessageContaining("IDEM_HUB_KMS_MASTER_KEY");
        assertThatThrownBy(() -> new LocalMasterKeyKmsClient("not-base64!", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LocalMasterKeyKmsClient(Base64.getEncoder().encodeToString(new byte[16]), true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32바이트");
    }
}

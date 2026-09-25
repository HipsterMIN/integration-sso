package io.github.hipstermin.idem.hub.admin.auth;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 관리자 TOTP 비밀 봉인 — AES-256-GCM, 키는 {@code ido.admin.secret-key}({@code IDEM_ADMIN_SECRET_KEY}).
 * 키가 비면 로컬·테스트에 한해 Handoff AES 키에서 HMAC 파생한다({@code ido.admin.allow-derived-secret-key=true} 필요).
 */
@Slf4j
@Component
public class AdminSecretCipher {

    private static final String PREFIX = "v1";
    private static final byte[] AAD = "idem-admin-totp".getBytes(StandardCharsets.UTF_8);

    private final AdminProperties props;
    private final Environment environment;
    private byte[] key;

    public AdminSecretCipher(AdminProperties props, Environment environment) {
        this.props = props;
        this.environment = environment;
    }

    @PostConstruct
    void init() {
        String configured = props.getSecretKey();
        if (configured != null && !configured.isBlank()) {
            key = Base64.getDecoder().decode(configured.trim());
            if (key.length != 32) throw new IllegalStateException("ido.admin.secret-key 는 base64 32바이트여야 합니다 (현재 " + key.length + "바이트)");
            return;
        }
        if (!props.isAllowDerivedSecretKey()) {
            throw new IllegalStateException("IDEM_ADMIN_SECRET_KEY(ido.admin.secret-key) 가 비어 있습니다 — 관리자 TOTP 비밀을 봉인할 수 없습니다. "
                    + "openssl rand -base64 32 로 만들어 주입하십시오. 로컬·테스트에서만 ido.admin.allow-derived-secret-key=true");
        }
        String handoffKey = environment.getProperty("ido.ticket.aes-key", "");
        byte[] base = handoffKey.isBlank() ? "idem-local-admin-secret".getBytes(StandardCharsets.UTF_8) : Base64.getDecoder().decode(handoffKey);
        key = CryptoProviders.current().hmacSha256(base, AAD);
        log.warn("[AdminSecretCipher] ido.admin.secret-key 미설정 — 파생 키 사용 (로컬·테스트 한정, 운영 금지)");
    }

    public String seal(String plain) {
        byte[] iv = CryptoProviders.current().randomBytes(12);
        byte[] ct = CryptoProviders.current().aesGcmEncrypt(key, iv, plain.getBytes(StandardCharsets.UTF_8), AAD);
        return PREFIX + ":" + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ct);
    }

    public String open(String sealed) {
        String[] p = sealed.split(":");
        if (p.length != 3 || !PREFIX.equals(p[0])) throw new IllegalArgumentException("봉인 형식 오류");
        byte[] pt = CryptoProviders.current().aesGcmDecrypt(key, Base64.getDecoder().decode(p[1]), Base64.getDecoder().decode(p[2]), AAD);
        return new String(pt, StandardCharsets.UTF_8);
    }
}

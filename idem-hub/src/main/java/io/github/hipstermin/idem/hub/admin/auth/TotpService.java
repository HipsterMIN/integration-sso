package io.github.hipstermin.idem.hub.admin.auth;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * TOTP (RFC 6238, HMAC-SHA1 · 6자리 · 30초) — 관리자 2단계 인증.
 *
 * <p>HMAC-SHA1 은 인증 앱 호환을 위해 프로토콜이 정한 것 — {@link CryptoProviders} 경계의 {@code hmacSha1} 만 쓴다.
 * 비밀은 20바이트 CSPRNG, 저장은 {@link AdminSecretCipher} 가 봉인한다.
 */
@Component
@RequiredArgsConstructor
public class TotpService {

    static final int DIGITS = 6;
    static final long STEP_SECONDS = 30;

    private final AdminProperties props;

    /** 새 비밀 (base32, 인증 앱에 그대로 입력 가능) */
    public String generateSecret() {
        return Base32.encode(CryptoProviders.current().randomBytes(20));
    }

    public String otpauthUri(String username, String secretBase32) {
        String issuer = props.getMfa().getIssuer();
        String label = URLEncoder.encode(issuer + ":" + username, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + secretBase32 + "&issuer="
                + URLEncoder.encode(issuer, StandardCharsets.UTF_8) + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    public boolean verify(String secretBase32, String code, Instant now) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) return false;
        byte[] key = Base32.decode(secretBase32);
        long step = now.getEpochSecond() / STEP_SECONDS;
        int window = Math.max(0, props.getMfa().getWindow());
        for (long i = -window; i <= window; i++) {
            if (CryptoProviders.current().constantTimeEquals(generate(key, step + i), code)) return true;
        }
        return false;
    }

    public String currentCode(String secretBase32, Instant now) {
        return generate(Base32.decode(secretBase32), now.getEpochSecond() / STEP_SECONDS);
    }

    static String generate(byte[] key, long counter) {
        byte[] h = CryptoProviders.current().hmacSha1(key, ByteBuffer.allocate(8).putLong(counter).array());
        int offset = h[h.length - 1] & 0x0f;
        int binary = ((h[offset] & 0x7f) << 24) | ((h[offset + 1] & 0xff) << 16) | ((h[offset + 2] & 0xff) << 8) | (h[offset + 3] & 0xff);
        int otp = binary % 1_000_000;
        return String.format("%06d", otp);
    }
}

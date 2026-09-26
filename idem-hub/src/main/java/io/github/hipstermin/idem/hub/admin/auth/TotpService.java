package io.github.hipstermin.idem.hub.admin.auth;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
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
        return matchedStep(secretBase32, code, now).isPresent();
    }

    /**
     * 코드가 맞는 시간 스텝(counter). 창(±window) 안에서 처음 맞는 스텝을 돌려주고, 안 맞으면 empty.
     *
     * <p>호출 쪽은 이 스텝을 {@link AdminSessionStore#consumeTotpStep} 로 1회 소비해야 한다 — RFC 6238 §5.2: 같은 OTP 의 두 번째 검증은
     * 받아들이면 안 된다(1.0.1, 3차 점검 M3 TOTP 재사용).
     */
    public OptionalLong matchedStep(String secretBase32, String code, Instant now) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) return OptionalLong.empty();
        byte[] key = Base32.decode(secretBase32);
        long step = now.getEpochSecond() / STEP_SECONDS;
        int window = Math.max(0, props.getMfa().getWindow());
        OptionalLong matched = OptionalLong.empty();
        for (long i = -window; i <= window; i++) {
            // 창 전체를 돈다 — 어느 스텝이 맞았는지로 소요 시간이 달라지지 않게
            if (CryptoProviders.current().constantTimeEquals(generate(key, step + i), code) && matched.isEmpty()) {
                matched = OptionalLong.of(step + i);
            }
        }
        return matched;
    }

    /** 창 안의 스텝이 소비 표시로 남아 있어야 하는 최대 시간 — (window+1) 스텝 뒤엔 어차피 검증이 안 된다 */
    Duration stepConsumptionTtl() {
        return Duration.ofSeconds(STEP_SECONDS * (Math.max(0, props.getMfa().getWindow()) + 2));
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

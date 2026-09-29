package io.github.hipstermin.idem.hub.fe.session;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 1.1: FE 세션 <b>바인드 코드</b> — 세션을 만든 응답이 브라우저에 직접 가지 않는 경로(qsign 모드: gate 콜백 → hub
 * {@code /api/internal/v1/oidc/complete} → gate 302) 에서 브라우저가 hub 쿠키를 받게 하는 1회용 코드.
 *
 * <p>종전에는 hub 가 쿠키를 gate 로의 응답에 실었고 gate 는 그것을 전달하지 않아, qsign 모드(설치본 기본)에서는
 * 브라우저에 FE 세션 쿠키가 <b>한 번도 도달하지 않았다</b>. 이제 hub 는 {@code {public-url}/api/v1/fe-session/bind?code=…}
 * 를 redirectUrl 로 돌려주고, 브라우저가 그 URL 에 오면 코드를 소비해 쿠키를 심고 원래 returnUrl 로 보낸다.
 *
 * <p>키 {@code idem:fe:bind:{code}} → {@code feSessionId|returnUrl}, TTL 60초, 소비 즉시 삭제(GETDEL).
 */
@Component
@RequiredArgsConstructor
public class FeSessionBindCodeStore {

    static final String KEY_PREFIX = "idem:fe:bind:";
    static final Duration TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;

    public record Bound(String feSessionId, String returnUrl) {}

    /** 코드 발급 — 192bit 무작위(CryptoProvider), URL-safe. */
    public String issue(String feSessionId, String returnUrl) {
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(CryptoProviders.current().randomBytes(24));
        redis.opsForValue().set(KEY_PREFIX + code, feSessionId + "|" + (returnUrl == null ? "" : returnUrl), TTL);
        return code;
    }

    /** 1회 소비 — 없거나 만료면 empty. */
    public Optional<Bound> consume(String code) {
        if (code == null || code.isBlank() || code.length() > 64) return Optional.empty();
        String v = redis.opsForValue().getAndDelete(KEY_PREFIX + code);
        if (v == null) return Optional.empty();
        int sep = v.indexOf('|');
        if (sep < 0) return Optional.empty();
        String returnUrl = v.substring(sep + 1);
        return Optional.of(new Bound(v.substring(0, sep), returnUrl.isEmpty() ? null : returnUrl));
    }
}

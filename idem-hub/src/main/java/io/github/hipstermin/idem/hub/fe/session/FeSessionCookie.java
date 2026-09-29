package io.github.hipstermin.idem.hub.fe.session;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import org.springframework.http.ResponseCookie;

/**
 * FE 세션 쿠키 — 이름과 속성의 <b>단일 정의</b> (1.1).
 *
 * <p>종전에는 발급 쪽(Keycloak 콜백·NonOidc 콜백·OidcComplete·fe-session API·KR 전환)이 {@code feSessionId} 로 쓰고
 * Handoff 발급({@code HandoffController})·CAST({@code CrossAgencySsoController}) 는 {@code Fe-Session-Id} 를 읽어
 * 브라우저에서 발급이 항상 {@code E-IDO-107} 로 끝났다(단위·통합 테스트는 쿠키를 직접 심어 드러나지 않았다).
 * 이제 모두 이 상수를 쓴다. 속성: HttpOnly · Secure · SameSite=Lax · Path=/ (hub 호스트 전용).
 */
public final class FeSessionCookie {

    public static final String NAME = "feSessionId";

    private FeSessionCookie() {}

    public static ResponseCookie build(String feSessionId) {
        return ResponseCookie.from(NAME, feSessionId)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .build();
    }

    public static ResponseCookie clear() {
        return ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }

    /** 요청 쿠키에서 값 — 없으면 null. */
    public static String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies)
                .filter(c -> NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
    }
}

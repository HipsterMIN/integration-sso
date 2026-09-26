package io.github.hipstermin.idem.gate.oidcfront;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * S6 OIDC 프런트 설정 — {@code idem.gate.oidc-front.*}.
 *
 * <p>gate 가 Keycloak 앞에 서서 표준 OIDC 엔드포인트를 공개 URL 로 내보낸다. {@link #getIssuer() issuer} 는
 * Keycloak 의 {@code KC_HOSTNAME_URL} + {@code /realms/{realm}} 과 같아야 id_token 의 {@code iss} 가 맞는다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "idem.gate.oidc-front")
public class OidcFrontProperties {

    /** 기관 RP 가 보는 issuer (gate 공개 URL + /realms/{realm}). */
    private String issuer = "http://localhost:8081/realms/idem";

    /** Idem 이 프로비저닝한 client 만 공개 프런트를 지날 수 있다 — hub 의 {@code idem.hub.oidc-rp.client-id-prefix} 와 같아야 한다. */
    private String clientIdPrefix = "idem-svc-";

    /** 토큰 교환 시 판정 결과를 userinfo 보강용으로 캐시하는 최대 시간(초) — 실제 TTL 은 min(expires_in, 이 값). */
    private long decisionCacheTtlSeconds = 3600;

    /** Keycloak 프록시(브라우저 경로 포함) 타임아웃. */
    private int proxyConnectTimeoutMs = 3000;
    private int proxyReadTimeoutMs = 10000;

    /** Back-Channel Logout 토큰에 {@code exp} 가 없을 때 {@code iat} 로부터 받아 주는 최대 시간(초). */
    private long logoutTokenMaxAgeSeconds = 300;

    /**
     * 공개 OIDC 프런트({@code /realms/**}) IP 레이트리밋 (1.0.1, 3차 점검 M17) — Keycloak 로그인 화면·client_id 열거·토큰 교환의 무제한 프록시를 막는다.
     * 브라우저 한 번의 로그인은 auth·로그인 폼 POST·code 교환·userinfo 로 10회 안쪽이다. 정적 자원({@code /resources/**})은 세지 않는다.
     */
    private RateLimit rateLimit = new RateLimit();

    @Getter
    @Setter
    public static class RateLimit {
        private boolean enabled = true;
        /** IP 당 초당 요청 */
        private int perSecond = 20;
        /** IP 당 분당 요청 */
        private int perMinute = 300;
        /** 앞단 리버스 프록시가 있어 {@code X-Forwarded-For} 의 마지막 홉(프록시가 붙인 값)을 클라이언트 IP 로 쓸지. false 면 소켓 주소. */
        private boolean trustForwardedFor = false;
    }

    /** issuer 에서 {@code /realms/…} 를 뗀 공개 베이스 URL — Keycloak 이 돌려주는 절대 URL 을 이 값으로 바꾼다. */
    public String publicBase() {
        int i = issuer.indexOf("/realms/");
        return i > 0 ? issuer.substring(0, i) : issuer;
    }

    public boolean isProvisionedClient(String clientId) {
        return clientId != null && clientId.startsWith(clientIdPrefix) && clientId.length() > clientIdPrefix.length();
    }
}

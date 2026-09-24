package io.github.hipstermin.idem.gate.oidcfront;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * S6 OIDC 프런트 설정 — {@code qsign.oidc-front.*}.
 *
 * <p>gate 가 Keycloak 앞에 서서 표준 OIDC 엔드포인트를 공개 URL 로 내보낸다. {@link #getIssuer() issuer} 는
 * Keycloak 의 {@code KC_HOSTNAME_URL} + {@code /realms/{realm}} 과 같아야 id_token 의 {@code iss} 가 맞는다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "qsign.oidc-front")
public class OidcFrontProperties {

    /** 기관 RP 가 보는 issuer (gate 공개 URL + /realms/{realm}). */
    private String issuer = "http://localhost:8081/realms/onepass";

    /** Idem 이 프로비저닝한 client 만 공개 프런트를 지날 수 있다 — hub 의 {@code ido.oidc-rp.client-id-prefix} 와 같아야 한다. */
    private String clientIdPrefix = "idem-svc-";

    /** 토큰 교환 시 판정 결과를 userinfo 보강용으로 캐시하는 최대 시간(초) — 실제 TTL 은 min(expires_in, 이 값). */
    private long decisionCacheTtlSeconds = 3600;

    /** Keycloak 프록시(브라우저 경로 포함) 타임아웃. */
    private int proxyConnectTimeoutMs = 3000;
    private int proxyReadTimeoutMs = 10000;

    /** issuer 에서 {@code /realms/…} 를 뗀 공개 베이스 URL — Keycloak 이 돌려주는 절대 URL 을 이 값으로 바꾼다. */
    public String publicBase() {
        int i = issuer.indexOf("/realms/");
        return i > 0 ? issuer.substring(0, i) : issuer;
    }

    public boolean isProvisionedClient(String clientId) {
        return clientId != null && clientId.startsWith(clientIdPrefix) && clientId.length() > clientIdPrefix.length();
    }
}

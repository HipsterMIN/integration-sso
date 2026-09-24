package io.github.hipstermin.idem.hub.protocol.oidcrp;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * S6 표준 프로토콜(OIDC_RP) 설정 — {@code ido.oidc-rp.*}.
 *
 * <p>Keycloak 은 설치본 내부 구성요소다. 기관(RP)에는 {@link #getIssuer() issuer}(gate 의 공개 URL 아래
 * {@code /realms/{realm}})만 알려 주고, Keycloak client 는 Idem 이 프로파일에서 프로비저닝한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ido.oidc-rp")
public class OidcRpProperties {

    /** OIDC_RP 프로비저닝 활성 — false 면 OIDC_RP 프로파일 저장이 E-IDO-122 로 거부된다(안전 우선). */
    private boolean enabled = true;

    /** 기관 RP 가 보는 issuer — gate 공개 URL + {@code /realms/{realm}}. Keycloak 의 {@code KC_HOSTNAME_URL} 과 같아야 한다. */
    private String issuer = "http://localhost:8081/realms/onepass";

    /** 프로비저닝되는 Keycloak clientId 접두 — {@code idem-svc-{serviceCode}}. */
    private String clientIdPrefix = "idem-svc-";

    private Provisioner provisioner = new Provisioner();

    /** Keycloak 에 client 를 만들 권한만 가진 서비스 계정({@code realm-management: manage-clients, view-clients}). */
    @Getter
    @Setter
    public static class Provisioner {
        private String clientId = "idem-provisioner";
        private String clientSecret = "";
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 5000;
    }

    public String clientIdFor(String serviceCode) {
        return clientIdPrefix + serviceCode;
    }

    /** {@code idem-svc-AG} → {@code AG}. 접두가 다르면 empty. */
    public java.util.Optional<String> serviceCodeFor(String clientId) {
        if (clientId == null || !clientId.startsWith(clientIdPrefix) || clientId.length() <= clientIdPrefix.length()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(clientId.substring(clientIdPrefix.length()));
    }

    public String discoveryUrl() {
        return issuer + "/.well-known/openid-configuration";
    }
}

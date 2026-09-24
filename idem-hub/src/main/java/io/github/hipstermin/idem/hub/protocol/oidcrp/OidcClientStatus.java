package io.github.hipstermin.idem.hub.protocol.oidcrp;

import java.util.List;

/**
 * 기관 관리자가 보는 OIDC client 상태 — secret 은 회전 응답에서만 한 번 보인다.
 *
 * @param provisioned  Keycloak 에 client 가 있는가
 * @param enabled      client 활성 여부 (프로파일이 OIDC_RP 가 아니게 되면 비활성으로 남긴다)
 * @param redirectUris Keycloak 에 등록된 redirect_uri
 */
public record OidcClientStatus(String serviceCode, String clientId, String issuer, String discoveryUrl,
                               boolean provisioned, boolean enabled, List<String> redirectUris) {

    public OidcClientStatus withEnabled(boolean value) {
        return new OidcClientStatus(serviceCode, clientId, issuer, discoveryUrl, provisioned, value, redirectUris);
    }

    public static OidcClientStatus absent(String serviceCode, String clientId, OidcRpProperties props) {
        return new OidcClientStatus(serviceCode, clientId, props.getIssuer(), props.discoveryUrl(), false, false, List.of());
    }
}

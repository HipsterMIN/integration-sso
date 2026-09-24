package io.github.hipstermin.idem.gate.oidcfront;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 정직한 OIDC Discovery 문서 (RFC 8414) — Idem 이 실제로 지원·강제하는 것만 적는다.
 * 종전 문서는 Keycloak 의 기능 목록(implicit·password·CIBA·HS256 …)을 그대로 내보냈다.
 */
public final class OidcDiscovery {

    private OidcDiscovery() {}

    public static Map<String, Object> build(String issuer) {
        String oc = issuer + "/protocol/openid-connect";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer", issuer);
        m.put("authorization_endpoint", oc + "/auth");
        m.put("token_endpoint", oc + "/token");
        m.put("userinfo_endpoint", oc + "/userinfo");
        m.put("jwks_uri", oc + "/certs");
        m.put("end_session_endpoint", oc + "/logout");
        m.put("revocation_endpoint", oc + "/revoke");
        m.put("introspection_endpoint", oc + "/token/introspect");
        m.put("response_types_supported", List.of("code"));
        m.put("response_modes_supported", List.of("query", "form_post"));
        m.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        m.put("subject_types_supported", List.of("public"));
        m.put("id_token_signing_alg_values_supported", List.of("RS256"));
        m.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic", "client_secret_post"));
        m.put("code_challenge_methods_supported", List.of("S256"));
        m.put("scopes_supported", List.of("openid", "profile", "email"));
        m.put("claims_supported", List.of("sub", "iss", "aud", "exp", "iat", "auth_time", "acr", "sid", "azp", "nonce",
                "identity_provider", "idem_service", "preferred_username", "email", "email_verified", "name", "given_name", "family_name"));
        m.put("claims_parameter_supported", false);
        m.put("request_parameter_supported", false);
        m.put("request_uri_parameter_supported", false);
        m.put("require_request_uri_registration", false);
        m.put("tls_client_certificate_bound_access_tokens", false);
        m.put("backchannel_logout_supported", true);
        m.put("backchannel_logout_session_supported", true);
        m.put("frontchannel_logout_supported", false);
        // Idem 확장 — userinfo 에 실리는 판정 클레임 (Handoff 와 같은 어휘)
        m.put("idem_userinfo_claims", List.of("idem_service", "idem_state", "idem_subject", "idem_subject_scheme",
                "idem_roles", "idem_assigned", "idem_user_id"));
        return m;
    }
}

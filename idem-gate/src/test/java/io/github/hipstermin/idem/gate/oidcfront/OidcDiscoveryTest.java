package io.github.hipstermin.idem.gate.oidcfront;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OidcDiscovery — issuer 아래 gate 엔드포인트만, 실제 지원 기능만")
class OidcDiscoveryTest {

    @Test
    @SuppressWarnings("unchecked")
    void honestDocument() {
        Map<String, Object> d = OidcDiscovery.build("https://sso.example.org/realms/idem");
        assertThat(d).containsEntry("issuer", "https://sso.example.org/realms/idem")
                .containsEntry("authorization_endpoint", "https://sso.example.org/realms/idem/protocol/openid-connect/auth")
                .containsEntry("token_endpoint", "https://sso.example.org/realms/idem/protocol/openid-connect/token")
                .containsEntry("jwks_uri", "https://sso.example.org/realms/idem/protocol/openid-connect/certs");
        assertThat((List<String>) d.get("response_types_supported")).containsExactly("code");
        assertThat((List<String>) d.get("grant_types_supported")).containsExactly("authorization_code", "refresh_token");
        assertThat((List<String>) d.get("code_challenge_methods_supported")).containsExactly("S256");
        assertThat((List<String>) d.get("id_token_signing_alg_values_supported")).containsExactly("RS256");
        assertThat(d).doesNotContainKey("registration_endpoint");
        assertThat(d.values().toString()).doesNotContain("implicit").doesNotContain("password").doesNotContain("ciba").doesNotContain("HS256");
        assertThat((List<String>) d.get("idem_userinfo_claims")).contains("idem_roles", "idem_subject", "idem_state");
    }

    @Test
    void publicBaseAndPrefix() {
        OidcFrontProperties p = new OidcFrontProperties();
        p.setIssuer("https://sso.example.org:8443/realms/idem");
        assertThat(p.publicBase()).isEqualTo("https://sso.example.org:8443");
        assertThat(p.isProvisionedClient("idem-svc-AG")).isTrue();
        assertThat(p.isProvisionedClient("idem-svc-")).isFalse();
        assertThat(p.isProvisionedClient("idem-gate")).isFalse();
        assertThat(p.isProvisionedClient(null)).isFalse();
    }
}

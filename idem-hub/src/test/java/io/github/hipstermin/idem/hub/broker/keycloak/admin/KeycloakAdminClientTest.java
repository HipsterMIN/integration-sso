package io.github.hipstermin.idem.hub.broker.keycloak.admin;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.hipstermin.idem.hub.broker.keycloak.KeycloakProperties;
import io.github.hipstermin.idem.hub.protocol.oidcrp.OidcRpProperties;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

@DisplayName("KeycloakAdminClient — 서비스 계정 토큰 캐시·Location 파싱·실패 전파 (WireMock)")
class KeycloakAdminClientTest {

    static WireMockServer wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    KeycloakAdminClient sut;
    OidcRpProperties props;

    @BeforeAll static void start() { wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        KeycloakProperties kc = new KeycloakProperties();
        kc.setBaseUrl("http://localhost:" + wm.port());
        kc.setRealm("idem");
        props = new OidcRpProperties();
        props.getProvisioner().setClientSecret("prov-secret");
        RestTemplate rt = new KeycloakAdminConfig().keycloakAdminRestTemplate(props);
        sut = new KeycloakAdminClient(rt, kc, props, new ObjectMapper());
        wm.stubFor(post(urlEqualTo("/realms/idem/protocol/openid-connect/token"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"tok-1\",\"expires_in\":300}")));
    }

    @Test
    @DisplayName("토큰은 한 번 받아 재사용하고, client 생성은 Location 마지막 세그먼트를 UUID 로 돌려준다")
    void tokenCachedAndLocationParsed() {
        wm.stubFor(post(urlEqualTo("/admin/realms/idem/clients"))
                .withHeader("Authorization", equalTo("Bearer tok-1"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Location", "http://localhost:" + wm.port() + "/admin/realms/idem/clients/abc-123")));
        wm.stubFor(get(urlPathEqualTo("/admin/realms/idem/clients/abc-123/client-secret"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"type\":\"secret\",\"value\":\"the-secret\"}")));

        String uuid = sut.createClient(Map.of("clientId", "idem-svc-X"));
        assertThat(uuid).isEqualTo("abc-123");
        assertThat(sut.getClientSecret("abc-123")).isEqualTo("the-secret");

        wm.verify(1, postRequestedFor(urlEqualTo("/realms/idem/protocol/openid-connect/token")));
    }

    @Test
    @DisplayName("clientId 조회는 정확 일치만 돌려준다 (Keycloak 의 접두 검색 결과를 거른다)")
    void findClient_exactMatchOnly() {
        wm.stubFor(get(urlPathEqualTo("/admin/realms/idem/clients"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":\"u1\",\"clientId\":\"idem-svc-AG10\"},{\"id\":\"u2\",\"clientId\":\"idem-svc-AG1\"}]")));
        assertThat(sut.findClientByClientId("idem-svc-AG1")).isPresent().get().extracting(m -> m.get("id")).isEqualTo("u2");
        assertThat(sut.findClientByClientId("idem-svc-AG2")).isEmpty();
    }

    @Test
    @DisplayName("Keycloak 4xx/5xx 는 KeycloakAdminException — 조용히 지나가지 않는다")
    void nonSuccess_throws() {
        wm.stubFor(post(urlEqualTo("/admin/realms/idem/clients")).willReturn(aResponse().withStatus(403)));
        assertThatThrownBy(() -> sut.createClient(Map.of()))
                .isInstanceOf(KeycloakAdminException.class).hasMessageContaining("403");
    }

    @Test
    @DisplayName("1.2: 관리 API 401 은 캐시한 토큰을 버리고 조치(kc-admin-api scope)를 알린다 — 다음 호출은 새 토큰으로 간다")
    void unauthorized_dropsCachedTokenAndHints() {
        // Keycloak 26.8.2+: client scope kc-admin-api 가 없는 토큰(tok-1)은 관리 API 가 401 — 운영자가 scope 를 붙인 뒤의 토큰(tok-2)은 200
        wm.stubFor(post(urlEqualTo("/realms/idem/protocol/openid-connect/token")).inScenario("scope").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"tok-1\",\"expires_in\":300}")).willSetStateTo("fixed"));
        wm.stubFor(post(urlEqualTo("/realms/idem/protocol/openid-connect/token")).inScenario("scope").whenScenarioStateIs("fixed")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"tok-2\",\"expires_in\":300}")));
        wm.stubFor(get(urlPathEqualTo("/admin/realms/idem/clients")).withHeader("Authorization", equalTo("Bearer tok-1"))
                .willReturn(aResponse().withStatus(401)));
        wm.stubFor(get(urlPathEqualTo("/admin/realms/idem/clients")).withHeader("Authorization", equalTo("Bearer tok-2"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":\"u1\",\"clientId\":\"idem-svc-AG1\"}]")));

        assertThatThrownBy(() -> sut.findClientByClientId("idem-svc-AG1"))
                .isInstanceOf(KeycloakAdminException.class)
                .hasMessageContaining("401").hasMessageContaining("kc-admin-api").hasMessageContaining("scripts/upgrade/keycloak-realm-1.2.sh");
        // 캐시를 버렸으므로 만료(300초)를 기다리지 않고 새 토큰을 받아 성공한다
        assertThat(sut.findClientByClientId("idem-svc-AG1")).isPresent();
        wm.verify(2, postRequestedFor(urlEqualTo("/realms/idem/protocol/openid-connect/token")));
    }

    @Test
    @DisplayName("1.2: 403 은 조치(protocol mapper 허용 목록)를 알리고 토큰은 버리지 않는다 — 권한 문제에 토큰 재발급을 되풀이하지 않는다")
    void forbidden_hintsMapperAllowlistAndKeepsToken() {
        // Keycloak 26.8.2+: realm 속성 allowedProtocolMapperTypesForClientAdministrators 가 비어 있으면 mapper 를 넣는 client 생성이 403
        wm.stubFor(post(urlEqualTo("/admin/realms/idem/clients")).willReturn(aResponse().withStatus(403)));
        assertThatThrownBy(() -> sut.createClient(Map.of("clientId", "idem-svc-X")))
                .isInstanceOf(KeycloakAdminException.class).hasMessageContaining("403")
                .hasMessageContaining("allowedProtocolMapperTypesForClientAdministrators").hasMessageContaining("scripts/upgrade/keycloak-realm-1.2.sh")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("kc-admin-api"));
        assertThatThrownBy(() -> sut.createClient(Map.of("clientId", "idem-svc-X"))).isInstanceOf(KeycloakAdminException.class);
        wm.verify(1, postRequestedFor(urlEqualTo("/realms/idem/protocol/openid-connect/token")));
    }

    @Test
    @DisplayName("1.2: 5xx 는 안내 없이 상태만 — 토큰도 그대로")
    void serverError_noHint() {
        wm.stubFor(get(urlPathEqualTo("/admin/realms/idem/clients")).willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> sut.findClientByClientId("x")).isInstanceOf(KeycloakAdminException.class).hasMessageContaining("503")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("kc-admin-api").doesNotContain("allowedProtocolMapperTypes"));
        assertThatThrownBy(() -> sut.findClientByClientId("x")).isInstanceOf(KeycloakAdminException.class);
        wm.verify(1, postRequestedFor(urlEqualTo("/realms/idem/protocol/openid-connect/token")));
    }

    @Test
    @DisplayName("프로비저너 비밀이 없으면 HTTP 를 부르지 않고 실패한다")
    void missingSecret_failsWithoutCall() {
        props.getProvisioner().setClientSecret("");
        assertThatThrownBy(() -> sut.findClientByClientId("x"))
                .isInstanceOf(KeycloakAdminException.class).hasMessageContaining("KEYCLOAK_PROVISIONER_CLIENT_SECRET");
        wm.verify(0, postRequestedFor(urlEqualTo("/realms/idem/protocol/openid-connect/token")));
    }
}

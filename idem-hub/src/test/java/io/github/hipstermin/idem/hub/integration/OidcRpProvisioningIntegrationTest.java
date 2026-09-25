package io.github.hipstermin.idem.hub.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * S6 끝-끝: OIDC_RP 프로파일 PUT 이 Keycloak(WireMock) 에 client 를 만들고, 상태 조회·secret 회전이 동작하며,
 * Keycloak 장애 시 저장이 되돌려진다(E-IDO-122, 프로파일 404).
 */
@DisplayName("S6 — OIDC_RP 프로파일 → Keycloak client 프로비저닝 통합 테스트 (Keycloak WireMock)")
class OidcRpProvisioningIntegrationTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void keycloakProps(DynamicPropertyRegistry registry) {
        registry.add("ido.keycloak.base-url", () -> "http://localhost:" + wireMockServer.port());
        registry.add("ido.keycloak.realm", () -> "onepass");
        registry.add("ido.oidc-rp.provisioner.client-secret", () -> "it-provisioner-secret");
        registry.add("ido.oidc-rp.issuer", () -> "https://sso.example.org/realms/onepass");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper om;

    static final String CODE = "TC_S6_OIDC";
    static final String CLIENT_ID = "idem-svc-" + CODE;
    static final String CLIENTS = "/admin/realms/onepass/clients";

    @BeforeEach
    void clean() {
        WireMock.configureFor("localhost", wireMockServer.port());
        wireMockServer.resetAll();
        jdbcTemplate.update("DELETE FROM ido.agency_meta_history WHERE agency_code = ?", CODE);
        jdbcTemplate.update("DELETE FROM ido.agency_meta WHERE agency_code = ?", CODE);
        stubFor(post(urlEqualTo("/realms/onepass/protocol/openid-connect/token"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"prov-tok\",\"expires_in\":300}")));
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private ResponseEntity<String> putProfile(String json) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        withAdmin(h, restTemplate, url(""));
        return restTemplate.exchange(url("/api/v1/admin/services/" + CODE + "/profile"), HttpMethod.PUT, new HttpEntity<>(json, h), String.class);
    }

    private static final String OIDC_PROFILE = """
            {"schemaVersion":1,
             "service":{"code":"TC_S6_OIDC","name":"S6 OIDC 기관","status":"ACTIVE"},
             "protocol":{"type":"OIDC_RP","oidc":{"redirectUris":["https://rp.example.org/login/oauth2/code/idem"],
                                                   "backchannelLogoutUri":"https://rp.example.org/bc"}},
             "policy":{"minAuthLevel":"L1"}}
            """;

    @Test
    @DisplayName("PUT(OIDC_RP) → Keycloak client 생성(PKCE S256·매퍼) → 상태 조회 → secret 회전")
    void provisionStatusRotate() throws Exception {
        // 첫 조회는 없음, 생성 뒤에는 있음
        stubFor(get(urlPathEqualTo(CLIENTS)).inScenario("client").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")));
        stubFor(post(urlEqualTo(CLIENTS)).inScenario("client").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(201).withHeader("Location", "http://kc/admin/realms/onepass/clients/uuid-s6"))
                .willSetStateTo("created"));
        stubFor(get(urlPathEqualTo(CLIENTS)).inScenario("client").whenScenarioStateIs("created")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":\"uuid-s6\",\"clientId\":\"" + CLIENT_ID + "\",\"enabled\":true,"
                                + "\"redirectUris\":[\"https://rp.example.org/login/oauth2/code/idem\"]}]")));
        stubFor(get(urlEqualTo(CLIENTS + "/uuid-s6/protocol-mappers/models"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"name\":\"idem-identity-provider\"},{\"name\":\"idem-service\"}]")));
        stubFor(post(urlEqualTo(CLIENTS + "/uuid-s6/client-secret"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"type\":\"secret\",\"value\":\"rotated-secret\"}")));

        ResponseEntity<String> res = putProfile(OIDC_PROFILE);
        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(200);
        wireMockServer.verify(1, postRequestedFor(urlEqualTo(CLIENTS))
                .withRequestBody(containing("\"pkce.code.challenge.method\":\"S256\""))
                .withRequestBody(containing("\"clientId\":\"" + CLIENT_ID + "\""))
                .withRequestBody(containing("\"fullScopeAllowed\":false"))
                .withRequestBody(containing("idem-identity-provider")));
        assertThat(jdbcTemplate.queryForObject("SELECT integration_type FROM ido.agency_meta WHERE agency_code = ?", String.class, CODE))
                .isEqualTo("OIDC_RP");

        ResponseEntity<String> status = restTemplate.exchange(url("/api/v1/admin/services/" + CODE + "/oidc-client"), HttpMethod.GET, new HttpEntity<>(adminHeaders(restTemplate, url(""))), String.class);
        assertThat(status.getStatusCode().value()).isEqualTo(200);
        JsonNode st = om.readTree(status.getBody());
        assertThat(st.path("provisioned").asBoolean()).isTrue();
        assertThat(st.path("clientId").asText()).isEqualTo(CLIENT_ID);
        assertThat(st.path("issuer").asText()).isEqualTo("https://sso.example.org/realms/onepass");
        assertThat(st.has("clientSecret")).isFalse();

        ResponseEntity<String> rotated = restTemplate.exchange(url("/api/v1/admin/services/" + CODE + "/oidc-client/secret"), HttpMethod.POST, new HttpEntity<>(adminHeaders(restTemplate, url(""))), String.class);
        assertThat(rotated.getStatusCode().value()).isEqualTo(200);
        assertThat(om.readTree(rotated.getBody()).path("clientSecret").asText()).isEqualTo("rotated-secret");

    }

    @Test
    @DisplayName("Keycloak 장애면 503 E-IDO-122 이고 프로파일은 저장되지 않는다(되돌림)")
    void keycloakDown_rollsBack() {
        stubFor(get(urlPathMatching(CLIENTS + ".*")).willReturn(aResponse().withStatus(500)));
        ResponseEntity<String> res = putProfile(OIDC_PROFILE);
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody()).contains("E-IDO-122");
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ido.agency_meta WHERE agency_code = ?", Integer.class, CODE);
        assertThat(count).isZero();
        ResponseEntity<String> get = restTemplate.exchange(url("/api/v1/admin/services/" + CODE + "/profile"), HttpMethod.GET, new HttpEntity<>(adminHeaders(restTemplate, url(""))), String.class);
        assertThat(get.getStatusCode().is2xxSuccessful()).as("되돌려진 프로파일은 조회되지 않아야 한다").isFalse();
    }

    @Test
    @DisplayName("DIRECT 프로파일 PUT 은 Keycloak 을 부르지 않는다")
    void direct_doesNotTouchKeycloak() {
        ResponseEntity<String> res = putProfile("""
                {"schemaVersion":1,"service":{"code":"TC_S6_OIDC","name":"직접 기관"},"protocol":{"type":"DIRECT"},"policy":{"minAuthLevel":"L1"}}
                """);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        wireMockServer.verify(0, postRequestedFor(urlEqualTo("/realms/onepass/protocol/openid-connect/token")));
    }
}

package io.github.hipstermin.idem.hub.broker.keycloak.admin;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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
        kc.setRealm("onepass");
        props = new OidcRpProperties();
        props.getProvisioner().setClientSecret("prov-secret");
        RestTemplate rt = new KeycloakAdminConfig().keycloakAdminRestTemplate(props);
        sut = new KeycloakAdminClient(rt, kc, props, new ObjectMapper());
        wm.stubFor(post(urlEqualTo("/realms/onepass/protocol/openid-connect/token"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"tok-1\",\"expires_in\":300}")));
    }

    @Test
    @DisplayName("토큰은 한 번 받아 재사용하고, client 생성은 Location 마지막 세그먼트를 UUID 로 돌려준다")
    void tokenCachedAndLocationParsed() {
        wm.stubFor(post(urlEqualTo("/admin/realms/onepass/clients"))
                .withHeader("Authorization", equalTo("Bearer tok-1"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Location", "http://localhost:" + wm.port() + "/admin/realms/onepass/clients/abc-123")));
        wm.stubFor(get(urlPathEqualTo("/admin/realms/onepass/clients/abc-123/client-secret"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"type\":\"secret\",\"value\":\"the-secret\"}")));

        String uuid = sut.createClient(Map.of("clientId", "idem-svc-X"));
        assertThat(uuid).isEqualTo("abc-123");
        assertThat(sut.getClientSecret("abc-123")).isEqualTo("the-secret");

        wm.verify(1, postRequestedFor(urlEqualTo("/realms/onepass/protocol/openid-connect/token")));
    }

    @Test
    @DisplayName("clientId 조회는 정확 일치만 돌려준다 (Keycloak 의 접두 검색 결과를 거른다)")
    void findClient_exactMatchOnly() {
        wm.stubFor(get(urlPathEqualTo("/admin/realms/onepass/clients"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":\"u1\",\"clientId\":\"idem-svc-AG10\"},{\"id\":\"u2\",\"clientId\":\"idem-svc-AG1\"}]")));
        assertThat(sut.findClientByClientId("idem-svc-AG1")).isPresent().get().extracting(m -> m.get("id")).isEqualTo("u2");
        assertThat(sut.findClientByClientId("idem-svc-AG2")).isEmpty();
    }

    @Test
    @DisplayName("Keycloak 4xx/5xx 는 KeycloakAdminException — 조용히 지나가지 않는다")
    void nonSuccess_throws() {
        wm.stubFor(post(urlEqualTo("/admin/realms/onepass/clients")).willReturn(aResponse().withStatus(403)));
        assertThatThrownBy(() -> sut.createClient(Map.of()))
                .isInstanceOf(KeycloakAdminException.class).hasMessageContaining("403");
    }

    @Test
    @DisplayName("프로비저너 비밀이 없으면 HTTP 를 부르지 않고 실패한다")
    void missingSecret_failsWithoutCall() {
        props.getProvisioner().setClientSecret("");
        assertThatThrownBy(() -> sut.findClientByClientId("x"))
                .isInstanceOf(KeycloakAdminException.class).hasMessageContaining("KEYCLOAK_PROVISIONER_CLIENT_SECRET");
        wm.verify(0, postRequestedFor(urlEqualTo("/realms/onepass/protocol/openid-connect/token")));
    }
}

package io.github.hipstermin.idem.gate.keycloak;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.hipstermin.idem.gate.metrics.AuthMetrics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

/** S6 PR-2: 종전 구현은 서비스 계정 없는 client·username 조회·비표준 DELETE 로 항상 실패했다. 새 구현의 계약. */
@DisplayName("KeycloakLogoutService — 세션 관리 서비스 계정으로 sid/sub 세션 종료")
class KeycloakLogoutServiceTest {

    static WireMockServer kc = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    KeycloakLogoutService sut;
    AuthMetrics metrics;
    KeycloakProperties props;

    @BeforeAll static void start() { kc.start(); }
    @AfterAll static void stop() { kc.stop(); }

    @BeforeEach
    void setUp() {
        kc.resetAll();
        props = new KeycloakProperties();
        props.setBaseUrl("http://localhost:" + kc.port());
        props.setRealm("idem");
        props.getSessionManager().setClientSecret("sm-secret");
        metrics = mock(AuthMetrics.class);
        sut = new KeycloakLogoutService(props, new RestTemplate(), metrics);
        kc.stubFor(WireMock.post(urlEqualTo("/realms/idem/protocol/openid-connect/token"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"sm-tok\",\"expires_in\":300}")));
    }

    @Test
    @DisplayName("sid 가 있으면 그 세션만 DELETE /sessions/{sid}; 토큰은 idem-session-manager 로 한 번만 받는다")
    void sid_deletesThatSession() {
        kc.stubFor(WireMock.delete(urlEqualTo("/admin/realms/idem/sessions/sid-1")).willReturn(aResponse().withStatus(204)));
        assertThat(sut.revoke("kc-sub", "sid-1", "cid")).isEqualTo(KeycloakLogoutService.Outcome.REVOKED_SESSION);
        assertThat(sut.revoke("kc-sub", "sid-1", "cid")).isEqualTo(KeycloakLogoutService.Outcome.REVOKED_SESSION);
        kc.verify(2, deleteRequestedFor(urlEqualTo("/admin/realms/idem/sessions/sid-1")).withHeader("Authorization", WireMock.equalTo("Bearer sm-tok")));
        kc.verify(1, postRequestedFor(urlEqualTo("/realms/idem/protocol/openid-connect/token"))
                .withRequestBody(WireMock.containing("client_id=idem-session-manager")));
        verify(metrics, never()).incrementSloKeycloakFailure();
    }

    @Test
    @DisplayName("sid 가 없으면 sub(사용자 UUID)로 POST /users/{sub}/logout — username 조회 없음")
    void sub_logsOutUser() {
        kc.stubFor(WireMock.post(urlEqualTo("/admin/realms/idem/users/kc-sub/logout")).willReturn(aResponse().withStatus(204)));
        assertThat(sut.revoke("kc-sub", null, "cid")).isEqualTo(KeycloakLogoutService.Outcome.REVOKED_USER);
        kc.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/admin/realms/idem/users")));
    }

    @Test
    @DisplayName("이미 없는 세션(404)은 목표 상태이므로 성공으로, 5xx 는 실패로, sub·sid 없으면 스킵")
    void notFound_failure_skip() {
        kc.stubFor(WireMock.delete(urlEqualTo("/admin/realms/idem/sessions/gone")).willReturn(aResponse().withStatus(404)));
        assertThat(sut.revoke(null, "gone", "cid")).isEqualTo(KeycloakLogoutService.Outcome.NOT_FOUND);
        kc.stubFor(WireMock.delete(urlEqualTo("/admin/realms/idem/sessions/boom")).willReturn(aResponse().withStatus(500)));
        assertThat(sut.revoke(null, "boom", "cid")).isEqualTo(KeycloakLogoutService.Outcome.FAILED);
        verify(metrics).incrementSloKeycloakFailure();
        assertThat(sut.revoke(null, null, "cid")).isEqualTo(KeycloakLogoutService.Outcome.SKIPPED);
    }

    @Test
    @DisplayName("1.2: 관리 API 401 은 실패로 세고 캐시한 토큰을 버린다 — scope 를 고친 뒤 다음 호출은 새 토큰으로 성공한다")
    void unauthorized_dropsCachedToken() {
        // Keycloak 26.8.2+: client scope kc-admin-api 가 없는 토큰(sm-tok)은 401 — 운영자가 scope 를 붙인 뒤의 토큰(sm-tok-2)은 204
        kc.stubFor(WireMock.post(urlEqualTo("/realms/idem/protocol/openid-connect/token")).inScenario("scope").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"sm-tok\",\"expires_in\":300}")).willSetStateTo("fixed"));
        kc.stubFor(WireMock.post(urlEqualTo("/realms/idem/protocol/openid-connect/token")).inScenario("scope").whenScenarioStateIs("fixed")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"sm-tok-2\",\"expires_in\":300}")));
        kc.stubFor(WireMock.delete(urlEqualTo("/admin/realms/idem/sessions/sid-1")).withHeader("Authorization", WireMock.equalTo("Bearer sm-tok"))
                .willReturn(aResponse().withStatus(401)));
        kc.stubFor(WireMock.delete(urlEqualTo("/admin/realms/idem/sessions/sid-1")).withHeader("Authorization", WireMock.equalTo("Bearer sm-tok-2"))
                .willReturn(aResponse().withStatus(204)));

        assertThat(sut.revoke("kc-sub", "sid-1", "cid")).isEqualTo(KeycloakLogoutService.Outcome.FAILED);
        verify(metrics).incrementSloKeycloakFailure();
        // 캐시를 버렸으므로 만료(300초)를 기다리지 않고 새 토큰으로 간다
        assertThat(sut.revoke("kc-sub", "sid-1", "cid")).isEqualTo(KeycloakLogoutService.Outcome.REVOKED_SESSION);
        kc.verify(2, postRequestedFor(urlEqualTo("/realms/idem/protocol/openid-connect/token")));
    }

    @Test
    @DisplayName("서비스 계정 비밀이 없으면 HTTP 없이 실패로 기록한다")
    void missingSecret() {
        props.getSessionManager().setClientSecret("");
        assertThat(sut.revoke("kc-sub", "sid-1", "cid")).isEqualTo(KeycloakLogoutService.Outcome.FAILED);
        kc.verify(0, postRequestedFor(urlEqualTo("/realms/idem/protocol/openid-connect/token")));
    }
}

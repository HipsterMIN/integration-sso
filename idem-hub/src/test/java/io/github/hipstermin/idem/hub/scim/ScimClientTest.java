package io.github.hipstermin.idem.hub.scim;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

/** 1.1 SCIM 클라이언트 — 표준 부분집합(filter 조회 → POST/PATCH/DELETE), Bearer, 오류 상태 전달. */
class ScimClientTest {

    static final WireMockServer wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    ScimClient client;
    ScimClient.Target target;

    @BeforeAll static void start() { wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        WireMock.configureFor("localhost", wm.port());
        client = new ScimClient(new RestTemplate(new org.springframework.http.client.JdkClientHttpRequestFactory()), new ObjectMapper());
        target = new ScimClient.Target("http://localhost:" + wm.port() + "/scim/v2/", "tok-1", "cid-1");
    }

    private static String list(String... ids) {
        StringBuilder sb = new StringBuilder("{\"totalResults\":" + ids.length + ",\"Resources\":[");
        for (int i = 0; i < ids.length; i++) sb.append(i > 0 ? "," : "").append("{\"id\":\"").append(ids[i]).append("\"}");
        return sb.append("]}").toString();
    }

    @Test
    @DisplayName("ensureUser: 없으면 POST /Users(externalId=userName=기관향 식별자, Bearer), 있으면 PATCH active")
    void ensureUser() {
        WireMock.stubFor(get(urlPathEqualTo("/scim/v2/Users")).withQueryParam("filter", equalTo("externalId eq \"pw-1\""))
                .withHeader("Authorization", equalTo("Bearer tok-1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/scim+json").withBody(list())));
        WireMock.stubFor(post(urlPathEqualTo("/scim/v2/Users"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/scim+json").withBody("{\"id\":\"u-9\"}")));
        assertThat(client.ensureUser(target, "pw-1", true)).isEqualTo("u-9");
        WireMock.verify(postRequestedFor(urlPathEqualTo("/scim/v2/Users"))
                .withRequestBody(containing("\"externalId\":\"pw-1\"")).withRequestBody(containing("\"userName\":\"pw-1\""))
                .withRequestBody(containing("\"active\":true")).withHeader("Content-Type", containing("application/scim+json")));

        wm.resetAll();
        WireMock.stubFor(get(urlPathEqualTo("/scim/v2/Users")).willReturn(aResponse().withStatus(200).withBody(list("u-5"))));
        WireMock.stubFor(patch(urlPathEqualTo("/scim/v2/Users/u-5")).willReturn(aResponse().withStatus(200).withBody("{\"id\":\"u-5\"}")));
        assertThat(client.ensureUser(target, "pw-1", true)).isEqualTo("u-5");
        WireMock.verify(patchRequestedFor(urlPathEqualTo("/scim/v2/Users/u-5")).withRequestBody(containing("\"path\":\"active\"")).withRequestBody(containing("\"value\":true")));
    }

    @Test
    @DisplayName("deactivate/delete: 사용자가 없으면 멱등(false), 있으면 PATCH active=false / DELETE(404 도 멱등)")
    void deactivateAndDelete() {
        WireMock.stubFor(get(urlPathEqualTo("/scim/v2/Users")).willReturn(aResponse().withStatus(200).withBody(list())));
        assertThat(client.deactivateUser(target, "pw-1")).isFalse();
        assertThat(client.deleteUser(target, "pw-1")).isFalse();

        wm.resetAll();
        WireMock.stubFor(get(urlPathEqualTo("/scim/v2/Users")).willReturn(aResponse().withStatus(200).withBody(list("u-5"))));
        WireMock.stubFor(patch(urlPathEqualTo("/scim/v2/Users/u-5")).willReturn(aResponse().withStatus(200)));
        WireMock.stubFor(delete(urlPathEqualTo("/scim/v2/Users/u-5")).willReturn(aResponse().withStatus(404)));
        assertThat(client.deactivateUser(target, "pw-1")).isTrue();
        assertThat(client.deleteUser(target, "pw-1")).isTrue();
        WireMock.verify(patchRequestedFor(urlPathEqualTo("/scim/v2/Users/u-5")).withRequestBody(containing("\"value\":false")));
    }

    @Test
    @DisplayName("addGroupMember: 그룹이 없으면 POST /Groups(displayName=roleCode) 뒤 PATCH members add; remove 는 필터 path")
    void groups() {
        WireMock.stubFor(get(urlPathEqualTo("/scim/v2/Users")).willReturn(aResponse().withStatus(200).withBody(list("u-5"))));
        WireMock.stubFor(patch(urlPathEqualTo("/scim/v2/Users/u-5")).willReturn(aResponse().withStatus(200)));
        WireMock.stubFor(get(urlPathEqualTo("/scim/v2/Groups")).withQueryParam("filter", equalTo("displayName eq \"MANAGER\""))
                .willReturn(aResponse().withStatus(200).withBody(list())));
        WireMock.stubFor(post(urlPathEqualTo("/scim/v2/Groups")).willReturn(aResponse().withStatus(201).withBody("{\"id\":\"g-1\"}")));
        WireMock.stubFor(patch(urlPathEqualTo("/scim/v2/Groups/g-1")).willReturn(aResponse().withStatus(200)));
        client.addGroupMember(target, "MANAGER", "pw-1");
        WireMock.verify(postRequestedFor(urlPathEqualTo("/scim/v2/Groups")).withRequestBody(containing("\"displayName\":\"MANAGER\"")));
        WireMock.verify(patchRequestedFor(urlPathEqualTo("/scim/v2/Groups/g-1")).withRequestBody(containing("\"op\":\"add\"")).withRequestBody(containing("\"value\":\"u-5\"")));

        wm.resetAll();
        WireMock.stubFor(get(urlPathEqualTo("/scim/v2/Users")).willReturn(aResponse().withStatus(200).withBody(list("u-5"))));
        WireMock.stubFor(get(urlPathEqualTo("/scim/v2/Groups")).willReturn(aResponse().withStatus(200).withBody(list("g-1"))));
        WireMock.stubFor(patch(urlPathEqualTo("/scim/v2/Groups/g-1")).willReturn(aResponse().withStatus(200)));
        assertThat(client.removeGroupMember(target, "MANAGER", "pw-1")).isTrue();
        WireMock.verify(patchRequestedFor(urlPathEqualTo("/scim/v2/Groups/g-1")).withRequestBody(containing("\"op\":\"remove\"")).withRequestBody(containing("members[value eq \\\"u-5\\\"]")));
    }

    @Test
    @DisplayName("기관 오류는 ScimException(status) 로 — 401·500 을 릴레이가 구분한다; 연결 실패는 status 0")
    void errors() {
        WireMock.stubFor(get(urlPathEqualTo("/scim/v2/Users")).willReturn(aResponse().withStatus(401)));
        assertThatThrownBy(() -> client.findUserId(target, "x")).isInstanceOf(ScimClient.ScimException.class)
                .satisfies(e -> assertThat(((ScimClient.ScimException) e).status()).isEqualTo(401));
        WireMock.stubFor(get(urlPathEqualTo("/scim/v2/Users")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client.findUserId(target, "x")).satisfies(e -> assertThat(((ScimClient.ScimException) e).status()).isEqualTo(500));
        ScimClient.Target dead = new ScimClient.Target("http://127.0.0.1:1/scim/v2", "t", "c");
        assertThatThrownBy(() -> client.findUserId(dead, "x")).satisfies(e -> assertThat(((ScimClient.ScimException) e).status()).isZero());
    }
}

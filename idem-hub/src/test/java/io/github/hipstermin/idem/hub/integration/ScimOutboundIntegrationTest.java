package io.github.hipstermin.idem.hub.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.hipstermin.idem.hub.scim.ScimOutboxRelay;
import io.github.hipstermin.idem.hub.scim.ScimOutboxService;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import java.util.Map;
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
 * 1.1 SCIM 아웃바운드 끝-끝 — 프로파일 PUT(scim 블록 검증·왕복) → 아웃박스 적재 → 릴레이가 기관 SCIM 서버(WireMock)에
 * filter 조회·POST /Users·Groups 를 호출 → DISPATCHED; 기관 5xx 는 재시도 예약. 자격증명은 개발 오버라이드로 주입한다.
 */
@DisplayName("1.1 SCIM 아웃바운드 — 프로파일 → 아웃박스 → 릴레이 → 기관 SCIM 서버 통합 테스트")
class ScimOutboundIntegrationTest extends IntegrationTestBase {

    private static final String AG = "SCIM_OUT_001";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("idem.hub.scim.allow-private-hosts", () -> "true");   // WireMock 은 localhost
        r.add("idem.hub.provisioning.credential.dev-overrides", () -> "SECRETS_AGENCY_" + AG + "_SCIM_TOKEN=it-scim-token");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ServiceProfileService serviceProfileService;
    @Autowired ScimOutboxService outboxService;
    @Autowired ScimOutboxRelay relay;

    @BeforeEach
    void setUp() {
        wireMockServer.resetAll();
        WireMock.configureFor("localhost", wireMockServer.port());
        jdbcTemplate.update("DELETE FROM idem_hub.scim_outbox WHERE agency_code = ?", AG);
        jdbcTemplate.update("DELETE FROM idem_hub.agency_meta_history WHERE agency_code = ?", AG);
        jdbcTemplate.update("DELETE FROM idem_hub.agency_webhook_config WHERE agency_code = ?", AG);
        jdbcTemplate.update("DELETE FROM idem_hub.agency_meta WHERE agency_code = ?", AG);
    }

    private String base() { return "http://localhost:" + port; }

    private ResponseEntity<String> putProfile(String scimBlock) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        withAdmin(h, restTemplate, base());
        String body = """
                {"schemaVersion":1,"service":{"code":"%s","name":"SCIM 기관"},
                 "protocol":{"type":"DIRECT","endpoints":{"callbackWhitelist":["https://agency.example.org/cb"]}%s},
                 "policy":{"minAuthLevel":"L1"}}
                """.formatted(AG, scimBlock);
        return restTemplate.exchange(base() + "/api/v1/admin/services/" + AG + "/profile", HttpMethod.PUT, new HttpEntity<>(body, h), String.class);
    }

    @Test
    @DisplayName("scim 블록: 스키마 검증(토큰은 참조만) → 저장·GET 왕복 → 아웃박스 적재 → 릴레이가 SCIM 서버에 반영 → 재시도·종결")
    void endToEnd() {
        // 토큰을 프로파일에 넣으려는 시도(credentialRef 형식 위반)는 400
        ResponseEntity<String> bad = putProfile(",\"scim\":{\"enabled\":true,\"baseUrl\":\"http://localhost:" + wireMockServer.port() + "/scim/v2\",\"credentialRef\":\"my-plain-token\"}");
        assertThat(bad.getStatusCode().value()).as(bad.getBody()).isEqualTo(400);

        ResponseEntity<String> ok = putProfile(",\"scim\":{\"enabled\":true,\"baseUrl\":\"http://localhost:" + wireMockServer.port()
                + "/scim/v2\",\"credentialRef\":\"secrets/agency/" + AG + "/scim-token\",\"onUnassign\":\"DEACTIVATE\"}");
        assertThat(ok.getStatusCode().value()).as(ok.getBody()).isEqualTo(200);
        ServiceProfile profile = serviceProfileService.get(AG);
        assertThat(profile.protocol().scim()).isNotNull();
        assertThat(profile.protocol().scim().enabledOrFalse()).isTrue();
        assertThat(profile.protocol().scim().credentialRef()).isEqualTo("secrets/agency/" + AG + "/scim-token");

        // 기관 SCIM 서버: 사용자·그룹 없음 → 생성
        stubFor(get(urlPathEqualTo("/scim/v2/Users")).withHeader("Authorization", equalTo("Bearer it-scim-token"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/scim+json").withBody("{\"totalResults\":0,\"Resources\":[]}")));
        stubFor(post(urlPathEqualTo("/scim/v2/Users")).willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/scim+json").withBody("{\"id\":\"u-1\"}")));
        stubFor(get(urlPathEqualTo("/scim/v2/Groups")).willReturn(aResponse().withStatus(200).withBody("{\"totalResults\":0,\"Resources\":[]}")));
        stubFor(post(urlPathEqualTo("/scim/v2/Groups")).willReturn(aResponse().withStatus(201).withBody("{\"id\":\"g-1\"}")));
        stubFor(patch(urlPathEqualTo("/scim/v2/Groups/g-1")).willReturn(aResponse().withStatus(200)));

        int n = outboxService.onAssignmentChanged(profile, "pw-abc", "ROLE_GRANTED", "MANAGER", "admin", "evt-1", "IDEM_AUTHZ_GRANTED", "cid-1");
        assertThat(n).isEqualTo(2);
        assertThat(relay.relayOnce()).isEqualTo(2);
        Map<String, Object> counts = jdbcTemplate.queryForMap(
                "SELECT SUM(CASE WHEN status='DISPATCHED' THEN 1 ELSE 0 END) d, SUM(CASE WHEN status='PENDING' THEN 1 ELSE 0 END) p FROM idem_hub.scim_outbox WHERE agency_code = ?", AG);
        assertThat(((Number) counts.get("d")).intValue()).isEqualTo(2);
        assertThat(((Number) counts.get("p")).intValue()).isZero();
        WireMock.verify(postRequestedFor(urlPathEqualTo("/scim/v2/Users")));
        WireMock.verify(postRequestedFor(urlPathEqualTo("/scim/v2/Groups")));
        assertThat(relay.relayOnce()).isZero();

        // 기관 5xx → 재시도 예약(PENDING 유지, next_retry_at 미래), 재시도 창 전에는 다시 집지 않는다
        wireMockServer.resetAll();
        stubFor(get(urlPathEqualTo("/scim/v2/Users")).willReturn(aResponse().withStatus(503)));
        assertThat(outboxService.onAssignmentChanged(profile, "pw-abc", "UNASSIGNED", null, "admin", "evt-2", "AUTHZ_UNASSIGNED", "cid-2")).isEqualTo(1);
        assertThat(relay.relayOnce()).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT status, retry_count, last_http_status, next_retry_at FROM idem_hub.scim_outbox WHERE source_event_id = 'evt-2'");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("retry_count")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("last_http_status")).intValue()).isEqualTo(503);
        assertThat(row.get("next_retry_at")).isNotNull();
        assertThat(relay.relayOnce()).isZero();

        // 같은 이벤트 재적재는 멱등(ON CONFLICT)
        assertThat(outboxService.onAssignmentChanged(profile, "pw-abc", "UNASSIGNED", null, "admin", "evt-2", "AUTHZ_UNASSIGNED", "cid-2")).isZero();
    }
}

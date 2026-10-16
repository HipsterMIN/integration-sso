package io.github.hipstermin.idem.hub.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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
 * 시험 항목 C-7 "승인" (G2-14, 2026-10-16 — 수동(콘솔) → 자동).
 *
 * <p>콘솔에서 SYSTEM_ADMIN 이 하는 일을 API 로 그대로 밟는다: 표준 OIDC(OIDC_RP) 서비스를 {@code INACTIVE} 로 등록(승인 전) →
 * gate 가 묻는 RP 로그인 판정({@code POST /api/internal/v1/oidc-rp/access}, 내부 서명)은 거부 {@code E-AGENCY-301} → 변경 사유를 달아
 * {@code ACTIVE} 로 저장(승인) → 같은 판정이 허용(APPROVED), Keycloak client {@code enabled=true} 로 갱신, 감사 {@code TENANT_PROFILE_UPDATED}
 * 와 프로파일 이력의 {@code change_reason} 에 사유가 남는다. Keycloak·registry 는 WireMock.
 */
@DisplayName("C-7 승인 — INACTIVE 등록 → RP 로그인 거부, ACTIVE 저장(사유) → 허용·감사·이력 통합 테스트")
class ServiceApprovalIntegrationTest extends IntegrationTestBase {

    private static final String SIG_SECRET = "integration-test-internal-sig-secret";
    private static final String CODE       = "IT_C7_APPROVE";
    private static final String CLIENT_ID  = "idem-svc-" + CODE;
    private static final String CLIENTS    = "/admin/realms/idem/clients";
    private static final String KC_UUID    = "uuid-c7";
    private static final String USER       = "qim-c7-0001";
    private static final String REASON_ONBOARD = "C-7 onboarding (inactive until approval)";
    private static final String REASON_APPROVE = "C-7 approval: SYSTEM_ADMIN activated the service";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("idem.hub.keycloak.base-url", () -> "http://localhost:" + wireMockServer.port());
        registry.add("idem.hub.keycloak.realm", () -> "idem");
        registry.add("idem.hub.oidc-rp.provisioner.client-secret", () -> "it-provisioner-secret");
        registry.add("idem.hub.oidc-rp.issuer", () -> "https://sso.example.org/realms/idem");
        registry.add("idem.hub.gate.internal-sig-secret", () -> SIG_SECRET);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper om;

    private String url(String p) { return "http://localhost:" + port + p; }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    @BeforeEach
    void setUp() {
        WireMock.configureFor("localhost", wireMockServer.port());
        wireMockServer.resetAll();
        jdbcTemplate.update("DELETE FROM idem_hub.agency_meta_history WHERE agency_code = ?", CODE);
        jdbcTemplate.update("DELETE FROM idem_hub.agency_webhook_config WHERE agency_code = ?", CODE);
        jdbcTemplate.update("DELETE FROM idem_hub.agency_meta WHERE agency_code = ?", CODE);

        // Keycloak admin API: 토큰 → client 없음 → 생성(201) → 조회되면 enabled=false → 갱신(PUT) 뒤 enabled=true
        stubFor(post(urlEqualTo("/realms/idem/protocol/openid-connect/token")).willReturn(json(200, "{\"access_token\":\"prov-tok\",\"expires_in\":300}")));
        stubFor(get(urlPathEqualTo(CLIENTS)).inScenario("c7").whenScenarioStateIs(Scenario.STARTED).willReturn(json(200, "[]")));
        stubFor(post(urlEqualTo(CLIENTS)).inScenario("c7").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(201).withHeader("Location", "http://kc" + CLIENTS + "/" + KC_UUID)).willSetStateTo("created"));
        stubFor(get(urlPathEqualTo(CLIENTS)).inScenario("c7").whenScenarioStateIs("created").willReturn(json(200, clientJson(false))));
        stubFor(put(urlEqualTo(CLIENTS + "/" + KC_UUID)).inScenario("c7").whenScenarioStateIs("created")
                .willReturn(aResponse().withStatus(204)).willSetStateTo("activated"));
        stubFor(get(urlPathEqualTo(CLIENTS)).inScenario("c7").whenScenarioStateIs("activated").willReturn(json(200, clientJson(true))));
        stubFor(get(urlEqualTo(CLIENTS + "/" + KC_UUID + "/protocol-mappers/models"))
                .willReturn(json(200, "[{\"name\":\"idem-identity-provider\"},{\"name\":\"idem-service\"}]")));

        // registry: 소셜 주체 미등록 → 등록 → 사용자 상태·DI
        stubFor(post(urlPathEqualTo("/api/v1/internal/users/find-by-social-sub")).willReturn(json(404, "{\"code\":\"NOT_FOUND\"}")));
        stubFor(post(urlPathEqualTo("/api/v1/internal/users/register-social"))
                .willReturn(json(201, "{\"qimUserId\":\"" + USER + "\",\"status\":\"ACTIVE\",\"isNew\":true}")));
        stubFor(get(urlPathEqualTo("/api/v1/users/" + USER)).willReturn(json(200, "{\"status\":\"ACTIVE\"}")));
        stubFor(get(urlPathEqualTo("/api/v1/internal/users/" + USER))
                .willReturn(json(200, "{\"qimUserId\":\"" + USER + "\",\"status\":\"ACTIVE\",\"nameMasked\":\"홍*동\"}")));
        stubFor(get(urlPathEqualTo("/api/v1/internal/users/" + USER + "/di"))
                .willReturn(json(200, "{\"qimUserId\":\"" + USER + "\",\"agencyCode\":\"" + CODE + "\",\"di\":\"di-c7\",\"isNew\":false,\"scheme\":\"PAIRWISE_HMAC\"}")));
    }

    private static String clientJson(boolean enabled) {
        return "[{\"id\":\"" + KC_UUID + "\",\"clientId\":\"" + CLIENT_ID + "\",\"enabled\":" + enabled + ","
                + "\"redirectUris\":[\"https://rp.example.org/login/oauth2/code/idem\"]}]";
    }

    private static String profile(String status) {
        return "{\"schemaVersion\":1,"
                + "\"service\":{\"code\":\"" + CODE + "\",\"name\":\"C-7 승인 기관\",\"status\":\"" + status + "\"},"
                + "\"protocol\":{\"type\":\"OIDC_RP\",\"oidc\":{\"redirectUris\":[\"https://rp.example.org/login/oauth2/code/idem\"],"
                + "\"backchannelLogoutUri\":\"https://rp.example.org/bc\"}},"
                + "\"policy\":{\"minAuthLevel\":\"L1\"}}";
    }

    private ResponseEntity<String> putProfile(String body, String reason) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        withAdmin(h, rest, url(""));
        h.set("X-Change-Reason", reason);
        return rest.exchange(url("/api/v1/admin/services/" + CODE + "/profile"), HttpMethod.PUT, new HttpEntity<>(body, h), String.class);
    }

    private JsonNode adminGet(String path) throws Exception {
        ResponseEntity<String> r = rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(adminHeaders(rest, url(""))), String.class);
        assertThat(r.getStatusCode().value()).as("GET %s body=%s", path, r.getBody()).isEqualTo(200);
        return om.readTree(r.getBody());
    }

    /** gate 가 토큰 교환 때 묻는 RP 로그인 판정 — gate 와 같은 규격(내부 서명 HMAC("{cid}:{epoch}")) */
    private JsonNode access() throws Exception {
        String cid = "cid-c7-" + UUID.randomUUID().toString().substring(0, 8);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Correlation-Id", cid);
        h.set("X-Internal-Caller", "idem-gate");
        h.set("X-Internal-Sig", CryptoProviders.current().hmacSha256Hex(SIG_SECRET.getBytes(StandardCharsets.UTF_8),
                cid + ":" + (System.currentTimeMillis() / 1000L)));
        String body = "{\"clientId\":\"" + CLIENT_ID + "\",\"sub\":\"kc-sub-c7\",\"identityProvider\":\"kakao\",\"acr\":\"1\",\"correlationId\":\"" + cid + "\"}";
        ResponseEntity<String> r = rest.exchange(url("/api/internal/v1/oidc-rp/access"), HttpMethod.POST, new HttpEntity<>(body, h), String.class);
        assertThat(r.getStatusCode().value()).as("access body=%s", r.getBody()).isEqualTo(200);   // 거부도 200(allowed=false)
        return om.readTree(r.getBody());
    }

    /** 감사는 비동기(@Async) 적재 — 잠깐 기다린다 */
    private JsonNode auditPage(String action, int minTotal) throws Exception {
        JsonNode page = null;
        for (int i = 0; i < 50; i++) {
            page = adminGet("/api/v1/admin/audit?agencyCode=" + CODE + "&action=" + action + "&size=5");
            if (page.path("total").asLong() >= minTotal) return page;
            Thread.sleep(100);
        }
        return page;
    }

    @Test
    @DisplayName("INACTIVE 등록 → RP 로그인 판정 거부(E-AGENCY-301); 사유를 달아 ACTIVE 저장 → 허용(APPROVED)·Keycloak client enabled·감사·이력 사유")
    void approvalActivatesService() throws Exception {
        // ① 승인 전: INACTIVE 로 등록 — Keycloak client 는 enabled=false 로 만들어진다
        ResponseEntity<String> created = putProfile(profile("INACTIVE"), REASON_ONBOARD);
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(200);
        verify(1, postRequestedFor(urlEqualTo(CLIENTS)).withRequestBody(containing("\"clientId\":\"" + CLIENT_ID + "\"")).withRequestBody(containing("\"enabled\":false")));
        assertThat(adminGet("/api/v1/admin/services/" + CODE + "/profile").path("service").path("status").asText()).isEqualTo("INACTIVE");

        // ② gate 의 판정 요청은 거부 — registry 등록까지 가지 않는다
        JsonNode denied = access();
        assertThat(denied.path("allowed").asBoolean()).isFalse();
        assertThat(denied.path("denyCode").asText()).isEqualTo("E-AGENCY-301");
        assertThat(denied.path("serviceCode").asText()).isEqualTo(CODE);
        verify(0, postRequestedFor(urlPathEqualTo("/api/v1/internal/users/register-social")));

        // ③ 승인: SYSTEM_ADMIN 이 사유를 달아 ACTIVE 저장 — Keycloak client 갱신(enabled=true)
        ResponseEntity<String> approved = putProfile(profile("ACTIVE"), REASON_APPROVE);
        assertThat(approved.getStatusCode().value()).as(approved.getBody()).isEqualTo(200);
        verify(1, putRequestedFor(urlEqualTo(CLIENTS + "/" + KC_UUID)).withRequestBody(containing("\"enabled\":true")));
        assertThat(adminGet("/api/v1/admin/services/" + CODE + "/profile").path("service").path("status").asText()).isEqualTo("ACTIVE");
        JsonNode client = adminGet("/api/v1/admin/services/" + CODE + "/oidc-client");
        assertThat(client.path("provisioned").asBoolean()).isTrue();
        assertThat(client.path("enabled").asBoolean()).isTrue();

        // ④ 같은 판정이 허용 — registry 등록·상태·DI 를 거쳐 APPROVED
        JsonNode allowed = access();
        assertThat(allowed.path("allowed").asBoolean()).as(allowed.toString()).isTrue();
        assertThat(allowed.path("serviceCode").asText()).isEqualTo(CODE);
        assertThat(allowed.path("qimUserId").asText()).isEqualTo(USER);
        assertThat(allowed.path("state").asText()).isEqualTo("APPROVED");
        verify(1, postRequestedFor(urlPathEqualTo("/api/v1/internal/users/register-social")));

        // ⑤ 감사: 생성·갱신이 SYSTEM 범주로 남는다(행위자 = 관리자 계정, 자원 = 서비스)
        JsonNode updated = auditPage("TENANT_PROFILE_UPDATED", 1);
        assertThat(updated.path("total").asLong()).as(updated.toString()).isGreaterThanOrEqualTo(1);
        JsonNode row = updated.path("items").get(0);
        assertThat(row.path("actorId").asText()).isEqualTo(AdminTestSupport.USERNAME);
        assertThat(row.path("resourceType").asText()).isEqualTo("TENANT_PROFILE");
        assertThat(row.path("resourceId").asText()).isEqualTo(CODE);
        assertThat(row.path("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(auditPage("TENANT_PROFILE_CREATED", 1).path("total").asLong()).isGreaterThanOrEqualTo(1);

        // ⑥ 이력: 변경 사유가 원문 그대로 — 최신 행이 승인 사유, 그 앞이 등록 사유
        assertThat(jdbcTemplate.queryForList("SELECT change_reason FROM idem_hub.agency_meta_history WHERE agency_code = ? ORDER BY changed_at DESC", String.class, CODE))
                .containsExactly(REASON_APPROVE, REASON_ONBOARD);
    }
}

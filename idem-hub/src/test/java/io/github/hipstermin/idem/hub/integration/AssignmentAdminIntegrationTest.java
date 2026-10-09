package io.github.hipstermin.idem.hub.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import io.github.hipstermin.idem.hub.infrastructure.jpa.repository.AgencyMetaJpaRepository;
import java.util.List;
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
 * 1.1.1 G1-3 — 관리 콘솔 할당 관리(hub 관리 API → authz WireMock) + 기관 목록 페이징·검색. 시험 항목 B-16·B-17.
 */
@DisplayName("1.1.1 G1-3 할당 관리 API · 기관 목록 페이징 통합 테스트 (authz WireMock)")
class AssignmentAdminIntegrationTest extends IntegrationTestBase {

    private static final String AG = "ASSIGN_ADMIN_001";

    @DynamicPropertySource
    static void authzProps(DynamicPropertyRegistry registry) {
        registry.add("idem.hub.authz.enabled", () -> "true");
        registry.add("idem.hub.authz.allow-empty-api-key", () -> "true");
        registry.add("idem.hub.authz.base-url", () -> "http://localhost:" + wireMockServer.port());
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AgencyMetaJpaRepository agencyMetaJpaRepository;

    @BeforeEach
    void setUp() {
        wireMockServer.resetAll();
        WireMock.configureFor("localhost", wireMockServer.port());
        jdbcTemplate.update("DELETE FROM idem_hub.agency_webhook_config WHERE agency_code = ?", AG);
        jdbcTemplate.update("DELETE FROM idem_hub.agency_meta_history WHERE agency_code = ?", AG);
        agencyMetaJpaRepository.findById(AG).ifPresent(agencyMetaJpaRepository::delete);
        agencyMetaJpaRepository.save(AgencyMetaJpaEntity.builder().agencyCode(AG).officialName("할당 관리 기관").minAuthLevel("L1").policyVersion("1.0")
                .apiKeyHash("h").integrationType(IntegrationType.DIRECT).active(true).build());
    }

    private String base() { return "http://localhost:" + port; }

    private HttpHeaders admin() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return withAdmin(h, restTemplate, base());
    }

    @Test
    @DisplayName("목록(페이지·총계) → 할당 → 역할 부여 → 회수 → 해제, authz 409 는 409 E-IDO-128, 감사 ADMIN/ASSIGNMENT_* · ROLE_*; 기관 목록 q·size")
    void assignmentLifecycle() {
        stubFor(get(urlPathEqualTo("/api/v1/internal/authz/agencies/" + AG + "/assignments"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withHeader("X-Total-Count", "1").withHeader("X-Has-Next", "false")
                        .withBody("[{\"qimUserId\":\"u-1\",\"agencyCode\":\"" + AG + "\",\"status\":\"ACTIVE\",\"source\":\"CONSOLE\",\"grantedBy\":\"admin\"}]")));
        stubFor(post(urlPathEqualTo("/api/v1/internal/authz/assignments")).withRequestBody(containing("\"qimUserId\":\"u-1\""))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"qimUserId\":\"u-1\",\"agencyCode\":\"" + AG + "\",\"status\":\"ACTIVE\",\"source\":\"CONSOLE\"}")));
        stubFor(post(urlPathEqualTo("/api/v1/internal/authz/assignments")).withRequestBody(containing("\"qimUserId\":\"u-dup\""))
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/json").withBody("{\"error\":\"E-AUTHZ-409-DUP\",\"message\":\"이미 할당\"}")));
        stubFor(get(urlPathEqualTo("/api/v1/internal/authz/roles")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("[{\"agencyCode\":\"" + AG + "\",\"roleCode\":\"MANAGER\",\"name\":\"관리자\",\"assignable\":true}]")));
        stubFor(post(urlPathEqualTo("/api/v1/internal/authz/grants")).willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"r-1\",\"qimUserId\":\"u-1\",\"agencyCode\":\"" + AG + "\",\"roleCode\":\"MANAGER\",\"status\":\"ACTIVE\",\"source\":\"CONSOLE\"}")));
        stubFor(delete(urlPathEqualTo("/api/v1/internal/authz/grants")).willReturn(aResponse().withStatus(204)));
        stubFor(delete(urlPathEqualTo("/api/v1/internal/authz/assignments")).willReturn(aResponse().withStatus(204)));

        String svc = base() + "/api/v1/admin/services/" + AG;
        ResponseEntity<Map> list = restTemplate.exchange(svc + "/assignments?page=0&size=50", HttpMethod.GET, new HttpEntity<>(admin()), Map.class);
        assertThat(list.getStatusCode().value()).as(String.valueOf(list.getBody())).isEqualTo(200);
        assertThat(list.getBody()).containsEntry("total", 1).containsEntry("hasNext", false);
        assertThat((List<?>) list.getBody().get("items")).hasSize(1);

        ResponseEntity<Map> asg = restTemplate.exchange(svc + "/assignments", HttpMethod.POST, new HttpEntity<>("{\"qimUserId\":\"u-1\",\"reason\":\"onboard\"}", admin()), Map.class);
        assertThat(asg.getStatusCode().value()).as(String.valueOf(asg.getBody())).isEqualTo(201);
        assertThat(asg.getBody()).containsEntry("status", "ACTIVE");
        WireMock.verify(postRequestedFor(urlPathEqualTo("/api/v1/internal/authz/assignments")).withRequestBody(containing("\"source\":\"CONSOLE\"")).withRequestBody(containing("\"grantedBy\":\"")));

        ResponseEntity<String> dup = restTemplate.exchange(svc + "/assignments", HttpMethod.POST, new HttpEntity<>("{\"qimUserId\":\"u-dup\"}", admin()), String.class);
        assertThat(dup.getStatusCode().value()).isEqualTo(409);
        assertThat(dup.getBody()).contains("E-IDO-128").contains("거부");   // authz 코드(E-AUTHZ-409-DUP)는 hub 로그에만 — 응답 계약(code·message·correlationId)은 그대로

        ResponseEntity<List> roles = restTemplate.exchange(svc + "/roles", HttpMethod.GET, new HttpEntity<>(admin()), List.class);
        assertThat(roles.getStatusCode().value()).isEqualTo(200);
        assertThat(roles.getBody()).hasSize(1);
        ResponseEntity<Map> grant = restTemplate.exchange(svc + "/assignments/u-1/roles", HttpMethod.POST, new HttpEntity<>("{\"roleCode\":\"MANAGER\"}", admin()), Map.class);
        assertThat(grant.getStatusCode().value()).as(String.valueOf(grant.getBody())).isEqualTo(201);
        assertThat(restTemplate.exchange(svc + "/assignments/u-1/roles/MANAGER?reason=done", HttpMethod.DELETE, new HttpEntity<>(admin()), String.class).getStatusCode().value()).isEqualTo(204);
        assertThat(restTemplate.exchange(svc + "/assignments/u-1?reason=offboard", HttpMethod.DELETE, new HttpEntity<>(admin()), String.class).getStatusCode().value()).isEqualTo(204);
        WireMock.verify(deleteRequestedFor(urlPathEqualTo("/api/v1/internal/authz/assignments")).withQueryParam("qimUserId", equalTo("u-1")).withQueryParam("reason", equalTo("offboard")));

        // 감사(비동기)
        Integer n = 0;
        for (int i = 0; i < 50 && n < 4; i++) {
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idem_hub.audit_log WHERE agency_code = ? AND event_category = 'ADMIN' AND actor_type = 'ADMIN' "
                    + "AND event_action IN ('ASSIGNMENT_GRANTED','ROLE_GRANTED','ROLE_REVOKED','ASSIGNMENT_REVOKED')", Integer.class, AG);
        }
        assertThat(n).isEqualTo(4);

        // 없는 서비스 404, 기관 목록 검색·페이지
        ResponseEntity<String> none = restTemplate.exchange(base() + "/api/v1/admin/services/NOPE_SVC/assignments", HttpMethod.GET, new HttpEntity<>(admin()), String.class);
        assertThat(none.getStatusCode().value()).isEqualTo(404);
        assertThat(none.getBody()).contains("E-AGENCY-307");
        ResponseEntity<Map> page = restTemplate.exchange(base() + "/api/v1/admin/agencies?q=assign_admin&size=10", HttpMethod.GET, new HttpEntity<>(admin()), Map.class);
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(page.getBody()).containsEntry("size", 10).containsEntry("page", 0);
        assertThat(((Number) page.getBody().get("total")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(((List<Map<String, Object>>) page.getBody().get("items")).stream().map(m -> m.get("agencyCode"))).contains(AG);
    }
}

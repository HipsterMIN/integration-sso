package io.github.hipstermin.idem.hub.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import io.github.hipstermin.idem.hub.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import io.github.hipstermin.idem.hub.infrastructure.jpa.repository.AgencyMetaJpaRepository;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * S8-b 할당 정책 끝-끝: 프로파일 {@code policy.assignment.required=true} + idem-authz(WireMock) 응답에 따라
 * 발급이 거부(E-IDO-120)되거나, 셀프 가입 허용이면 GUEST 로 verify 되고, 할당되면 APPROVED + roles 가 실린다.
 *
 * <p>authz 는 WireMock 으로 대신한다 — 이 클래스만 {@code ido.q-authz.enabled=true} 로 켠다.
 */
@DisplayName("S8-b 할당 정책 — Handoff issue/verify 통합 테스트 (authz WireMock)")
class AssignmentIntegrationTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void authzProps(DynamicPropertyRegistry registry) {
        registry.add("ido.q-authz.enabled", () -> "true");
        registry.add("ido.q-authz.allow-empty-api-key", () -> "true");
        registry.add("ido.q-authz.base-url", () -> "http://localhost:" + wireMockServer.port());
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired AgencyMetaJpaRepository agencyMetaJpaRepository;
    @Autowired FeSessionService feSessionService;

    private static final String AGENCY_CODE = "ASGN_TEST_001";
    private static final String AGENCY_KEY  = "asgn-test-agency-key";
    private String baseUrl;
    private String qimUserId;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        qimUserId = UUID.randomUUID().toString();
        wireMockServer.resetAll();
        WireMock.configureFor("localhost", wireMockServer.port());
        // registry: 사용자 상태·DI
        stubFor(get(urlPathMatching("/api/v1/users/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"status\":\"ACTIVE\"}")));
        // WireMock 은 나중에 등록한 스텁이 우선 — 넓은 패턴을 먼저, DI 를 나중에
        stubFor(get(urlPathMatching("/api/v1/internal/users/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"qimUserId\":\"" + qimUserId + "\",\"status\":\"ACTIVE\"}")));
        stubFor(get(urlPathMatching("/api/v1/internal/users/.*/di"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"qimUserId\":\"" + qimUserId + "\",\"agencyCode\":\"" + AGENCY_CODE + "\",\"di\":\"DI-ASGN-1\",\"scheme\":\"PAIRWISE_HMAC\"}")));
        agencyMetaJpaRepository.findById(AGENCY_CODE).ifPresent(agencyMetaJpaRepository::delete);
        agencyMetaJpaRepository.save(AgencyMetaJpaEntity.builder()
                .agencyCode(AGENCY_CODE).officialName("할당 테스트 기관").minAuthLevel("L1").policyVersion("1.0")
                .apiKeyHash(CryptoProviders.current().sha256Hex(AGENCY_KEY))
                .callbackWhitelist("[\"https://agency.example.com/callback\"]")
                .allowedAttributes("[\"name_masked\"]")
                .integrationType(IntegrationType.DIRECT).active(true).build());
    }

    private void putProfile(boolean required, boolean selfSignup) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        withAdmin(h, restTemplate, baseUrl);
        ResponseEntity<String> res = restTemplate.exchange(
                baseUrl + "/api/v1/admin/services/" + AGENCY_CODE + "/profile", HttpMethod.PUT,
                new HttpEntity<>("""
                        {"schemaVersion":1,"service":{"code":"%s","name":"할당 테스트 기관"},
                         "protocol":{"type":"DIRECT","endpoints":{"callbackWhitelist":["https://agency.example.com/callback"]}},
                         "identity":{"attributes":["name_masked"]},
                         "policy":{"minAuthLevel":"L1","assignment":{"required":%s,"selfSignup":%s}}}
                        """.formatted(AGENCY_CODE, required, selfSignup), h), String.class);
        assertThat(res.getStatusCode().value()).as("profile put body=%s", res.getBody()).isEqualTo(200);
    }

    private void stubAccess(boolean assigned, String rolesJson) {
        stubFor(get(urlPathMatching("/api/v1/internal/authz/users/.*/access"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"qimUserId\":\"" + qimUserId + "\",\"agencyCode\":\"" + AGENCY_CODE + "\",\"assigned\":" + assigned
                                + ",\"assignmentSource\":" + (assigned ? "\"CONSOLE\"" : "null") + ",\"roles\":" + rolesJson + "}")));
    }

    @Test
    @DisplayName("할당 필수 + 미할당 + 셀프 가입 없음 → issue 403 E-IDO-120")
    void required_unassigned_issueDenied() {
        putProfile(true, false);
        stubAccess(false, "[]");
        ResponseEntity<String> res = issue();
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(403);
        assertThat(res.getBody()).contains("E-IDO-120");
    }

    @Test
    @DisplayName("할당 필수 + 미할당 + 셀프 가입 허용 → issue 200, verify 상태 GUEST 이되 주체 식별자는 실린다")
    void required_unassigned_selfSignup_guest() throws Exception {
        putProfile(true, true);
        stubAccess(false, "[]");
        ResponseEntity<String> issued = issue();
        assertThat(issued.getStatusCode().value()).as("body=%s", issued.getBody()).isEqualTo(200);
        JsonNode payload = verify(objectMapper.readTree(issued.getBody()).get("ticketId").asText());
        assertThat(payload.get("state").asText()).isEqualTo("GUEST");
        assertThat(payload.at("/subject/assigned").asBoolean()).isFalse();
        assertThat(payload.at("/subject/agencySubjectId").asText()).isEqualTo("DI-ASGN-1");
    }

    @Test
    @DisplayName("할당 필수 + 할당됨 → verify APPROVED, roles 와 subject.assigned=true 가 응답에 실린다")
    void required_assigned_approvedWithRoles() throws Exception {
        putProfile(true, false);
        stubAccess(true, "[\"MANAGER\",\"REVIEWER\"]");
        ResponseEntity<String> issued = issue();
        assertThat(issued.getStatusCode().value()).as("body=%s", issued.getBody()).isEqualTo(200);
        JsonNode payload = verify(objectMapper.readTree(issued.getBody()).get("ticketId").asText());
        assertThat(payload.get("state").asText()).isEqualTo("APPROVED");
        assertThat(payload.at("/subject/assigned").asBoolean()).isTrue();
        assertThat(payload.get("roles").isArray()).isTrue();
        assertThat(payload.get("roles").toString()).contains("MANAGER").contains("REVIEWER");
    }

    @Test
    @DisplayName("authz 장애(5xx) → issue 503 E-IDO-117 (미할당·역할 없음으로 위장하지 않는다)")
    void authzDown_issueDenied503() {
        putProfile(true, true);
        stubFor(get(urlPathMatching("/api/v1/internal/authz/users/.*/access")).willReturn(aResponse().withStatus(500)));
        ResponseEntity<String> res = issue();
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(503);
        assertThat(res.getBody()).contains("E-IDO-117");
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private ResponseEntity<String> issue() {
        FeSession session = feSessionService.create(qimUserId, UUID.randomUUID().toString(), "L2", null);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", UUID.randomUUID().toString());
        headers.set("X-Agency-Code", AGENCY_CODE);
        headers.set("X-Agency-Key", AGENCY_KEY);
        headers.add(HttpHeaders.COOKIE, "Fe-Session-Id=" + session.getFeSessionId());
        String body = """
                {"agencyCode":"%s","authResultId":"%s","authLevel":"L2","providerCode":"MOCK",
                 "callbackUrl":"https://agency.example.com/callback"}
                """.formatted(AGENCY_CODE, UUID.randomUUID());
        return restTemplate.exchange(baseUrl + "/api/v1/handoff/issue", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode verify(String ticketId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", UUID.randomUUID().toString());
        headers.set("X-Agency-Code", AGENCY_CODE);
        headers.set("X-Agency-Key", AGENCY_KEY);
        ResponseEntity<String> res = restTemplate.exchange(baseUrl + "/api/v1/handoff/verify", HttpMethod.POST,
                new HttpEntity<>("{\"ticketId\":\"" + ticketId + "\"}", headers), String.class);
        assertThat(res.getStatusCode().value()).as("verify body=%s", res.getBody()).isEqualTo(200);
        return objectMapper.readTree(res.getBody());
    }
}

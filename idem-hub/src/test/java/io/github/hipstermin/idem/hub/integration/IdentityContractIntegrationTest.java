package io.github.hipstermin.idem.hub.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
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

/**
 * S4 범용화 완료 기준 — <b>CI 없는 스킴(EMAIL)</b> 으로 Mock 인증 → registry 등록 → Handoff 발급·검증이 통과하고,
 * 기관 프로파일 {@code identity.*} 가 {@code subject.agencySubjectId} 와 {@code attributes} 를 결정한다.
 * registry(idem-registry) 는 WireMock 으로 대신한다 — hub 가 보내는 계약(register-subject · /subject · /di) 을 검증한다.
 */
@DisplayName("S4 — 식별자·속성 계약 통합 테스트 (EMAIL 스킴 · 기본 PAIRWISE · GUEST · required)")
class IdentityContractIntegrationTest extends IntegrationTestBase {

    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired FeSessionService feSessionService;

    private static final String AGENCY_KEY = "s4-agency-key";
    private static final List<String> CODES = List.of("TC_S4_EMAIL", "TC_S4_PAIRWISE", "TC_S4_REQ");
    private static final String USER_EMAIL = "qim-s4-email-0001";
    private static final String USER_EXT   = "qim-s4-ext-0001";

    @BeforeEach
    void setUp() {
        for (String code : CODES) {
            jdbcTemplate.update("DELETE FROM ido.agency_meta_history WHERE agency_code = ?", code);
            jdbcTemplate.update("DELETE FROM ido.agency_webhook_config WHERE agency_code = ?", code);
            jdbcTemplate.update("DELETE FROM ido.agency_meta WHERE agency_code = ?", code);
        }
        wireMockServer.resetAll();
        WireMock.configureFor("localhost", wireMockServer.port());

        // registry(WireMock) — 상태 조회(정책 USER_STATUS) 는 항상 ACTIVE
        stubFor(get(urlPathMatching("/api/v1/users/.*"))
                .willReturn(wm(200, "{\"status\":\"ACTIVE\"}")));
        // register-subject: EMAIL 스킴이면 USER_EMAIL, 아니면 USER_EXT
        stubFor(WireMock.post(urlPathEqualTo("/api/v1/internal/users/register-subject"))
                .withRequestBody(matchingJsonPath("$.scheme", equalTo("EMAIL")))
                .willReturn(wm(201, "{\"qimUserId\":\"" + USER_EMAIL + "\",\"status\":\"ACTIVE\",\"isNew\":true}")));
        stubFor(WireMock.post(urlPathEqualTo("/api/v1/internal/users/register-subject"))
                .withRequestBody(matchingJsonPath("$.scheme", equalTo("EXTERNAL_SUB")))
                .willReturn(wm(201, "{\"qimUserId\":\"" + USER_EXT + "\",\"status\":\"ACTIVE\",\"isNew\":true}")));
        // 프로필 (PROFILE 속성)
        stubFor(get(urlPathEqualTo("/api/v1/internal/users/" + USER_EMAIL))
                .willReturn(wm(200, "{\"qimUserId\":\"" + USER_EMAIL + "\",\"status\":\"ACTIVE\",\"nameMasked\":\"김*스트\","
                        + "\"birthYear\":1985,\"gender\":\"UNKNOWN\",\"subjectScheme\":\"EMAIL\"}")));
        stubFor(get(urlPathEqualTo("/api/v1/internal/users/" + USER_EXT))
                .willReturn(wm(200, "{\"qimUserId\":\"" + USER_EXT + "\",\"status\":\"ACTIVE\",\"nameMasked\":\"이*부\",\"subjectScheme\":\"EXTERNAL_SUB\"}")));
        // 주체 키 — EMAIL 사용자만 EMAIL 키가 있고, EXTERNAL_SUB 사용자는 404(스킴 불일치)
        stubFor(get(urlPathEqualTo("/api/v1/internal/users/" + USER_EMAIL + "/subject"))
                .withQueryParam("scheme", equalTo("EMAIL"))
                .willReturn(wm(200, "{\"qimUserId\":\"" + USER_EMAIL + "\",\"scheme\":\"EMAIL\",\"subjectKey\":\"alice@example.org\"}")));
        stubFor(get(urlPathEqualTo("/api/v1/internal/users/" + USER_EXT + "/subject"))
                .willReturn(aResponse().withStatus(404)));
        // 기관별 DI (PAIRWISE_HMAC 기본 스킴)
        stubFor(get(urlPathMatching("/api/v1/internal/users/[^/]+/di"))
                .willReturn(wm(200, "{\"qimUserId\":\"x\",\"agencyCode\":\"TC_S4_PAIRWISE\",\"di\":\"di-pairwise-abc\",\"isNew\":false,\"scheme\":\"PAIRWISE_HMAC\"}")));
    }

    // ── 시나리오 1: EMAIL 스킴 끝-끝 ───────────────────────────────────────

    @Test
    @DisplayName("EMAIL 스킴: Mock 인증(email) → register-subject(scheme=EMAIL) → Handoff verify 가 이메일을 agencySubjectId 로, 프로파일 속성을 매핑해 돌려준다")
    void emailScheme_endToEnd() throws Exception {
        String code = "TC_S4_EMAIL";
        putProfile(code, """
                {"schemaVersion":1,
                 "tenant":{"code":"%s","name":"S4 이메일 기관"},
                 "protocol":{"type":"DIRECT","endpoints":{"callbackWhitelist":["https://tenant.example.org/cb"]}},
                 "identity":{"subjectScheme":"EMAIL",
                             "attributes":["name_masked",{"name":"email","masking":"NONE"},"birth_year","qimUserId","gender"],
                             "attributeMapping":{"name_masked":"userNm","email":"mail"}},
                 "policy":{"minAuthLevel":"L1"}}
                """.formatted(code));

        // 1) Mock 본인인증 — CI 없이 email 만으로
        JsonNode start = callJson("/api/v1/auth/providers/MOCK/initiate",
                "{\"params\":{\"email\":\"Alice@Example.org\",\"name\":\"김테스트\",\"birthDate\":\"19850505\"}}", null);
        String txId = start.at("/txId").asText();
        assertThat(txId).startsWith("mock-");

        ResponseEntity<String> completeRes = call("/api/v1/auth/providers/MOCK/complete",
                "{\"txId\":\"" + txId + "\"}", null);
        assertThat(completeRes.getStatusCode().value()).as("body=%s", completeRes.getBody()).isEqualTo(200);
        JsonNode complete = objectMapper.readTree(completeRes.getBody());
        assertThat(complete.at("/identity/subjectScheme").asText()).isEqualTo("EMAIL");
        assertThat(complete.at("/identity/subjectKey").asText()).isEqualTo("Alice@Example.org");
        assertThat(complete.at("/registration/qimUserId").asText()).isEqualTo(USER_EMAIL);
        assertThat(complete.at("/registration/newUser").asBoolean()).isTrue();
        // hub → registry 등록 계약: 스킴·정규화 해시·PII 원문(registry 가 마스킹)
        verify(postRequestedFor(urlPathEqualTo("/api/v1/internal/users/register-subject"))
                .withRequestBody(matchingJsonPath("$.scheme", equalTo("EMAIL")))
                .withRequestBody(matchingJsonPath("$.subjectKey", equalTo("Alice@Example.org")))
                .withRequestBody(matchingJsonPath("$.identifierHash",
                        equalTo(io.github.hipstermin.idem.common.identity.SubjectScheme.EMAIL.identifierHash("alice@example.org"))))
                .withRequestBody(matchingJsonPath("$.rawName", equalTo("김테스트")))
                .withRequestBody(matchingJsonPath("$.birthYear", equalTo("1985"))));

        // 2) Handoff 발급 (FE 세션 = 등록된 사용자)
        String ticketId = issue(code, USER_EMAIL);

        // 3) 기관이 verify
        JsonNode payload = okJson(call("/api/v1/handoff/verify", "{\"ticketId\":\"" + ticketId + "\"}", agencyHeaders(code)));
        assertThat(payload.at("/state").asText()).isEqualTo("APPROVED");
        assertThat(payload.at("/subject/agencySubjectId").asText()).isEqualTo("alice@example.org");
        assertThat(payload.at("/subject/subjectScheme").asText()).isEqualTo("EMAIL");
        assertThat(payload.at("/subject/qimUserId").asText()).isEqualTo(USER_EMAIL);
        JsonNode attrs = payload.get("attributes");
        assertThat(attrs.get("userNm").asText()).isEqualTo("김*스트");            // attributeMapping
        assertThat(attrs.get("mail").asText()).isEqualTo("alice@example.org");  // masking NONE + mapping
        assertThat(attrs.get("birth_year").asInt()).isEqualTo(1985);           // 정규 이름
        assertThat(attrs.get("qimUserId").asText()).isEqualTo(USER_EMAIL);      // 별칭 선언 → 별칭 키 유지
        assertThat(attrs.get("gender").asText()).isEqualTo("UNKNOWN");
        assertThat(attrs.fieldNames()).toIterable().containsExactlyInAnyOrder("userNm", "mail", "birth_year", "qimUserId", "gender");
        // EMAIL 스킴 기관은 DI 를 만들지 않는다
        verify(0, getRequestedFor(urlPathMatching("/api/v1/internal/users/[^/]+/di")));
    }

    // ── 시나리오 2: 기본 스킴(PAIRWISE_HMAC) 은 종전 DI 경로, 속성 미선언이면 빈 맵 ─

    @Test
    @DisplayName("subjectScheme 미지정 기관은 PAIRWISE_HMAC(registry DI) 로 해석되고, 속성을 선언하지 않으면 attributes 는 비어 있다")
    void defaultScheme_usesDi_noAttributes() throws Exception {
        String code = "TC_S4_PAIRWISE";
        putProfile(code, """
                {"schemaVersion":1,
                 "tenant":{"code":"%s","name":"S4 기본 기관"},
                 "protocol":{"type":"DIRECT","endpoints":{"callbackWhitelist":["https://tenant.example.org/cb"]}},
                 "policy":{"minAuthLevel":"L1"}}
                """.formatted(code));
        String ticketId = issue(code, USER_EMAIL);

        JsonNode payload = okJson(call("/api/v1/handoff/verify", "{\"ticketId\":\"" + ticketId + "\"}", agencyHeaders(code)));
        assertThat(payload.at("/state").asText()).isEqualTo("APPROVED");
        assertThat(payload.at("/subject/agencySubjectId").asText()).isEqualTo("di-pairwise-abc");
        assertThat(payload.at("/subject/subjectScheme").asText()).isEqualTo("PAIRWISE_HMAC");
        assertThat(payload.get("attributes").size()).isZero();
        verify(getRequestedFor(urlPathEqualTo("/api/v1/internal/users/" + USER_EMAIL + "/di"))
                .withQueryParam("agencyCode", equalTo(code)));
    }

    // ── 시나리오 3: 스킴 불일치 → GUEST, required 속성 결핍 → 422 ─────────────

    @Test
    @DisplayName("EMAIL 기관에 EXTERNAL_SUB 로 등록된 사용자가 오면 GUEST(agencySubjectId 없음); required 속성을 못 채우면 422 E-IDO-114 이고 티켓은 소비되지 않는다")
    void schemeMismatch_guest_andRequiredMissing_rejects() throws Exception {
        // EXTERNAL_SUB 사용자 등록 (email 없이)
        JsonNode start = callJson("/api/v1/auth/providers/MOCK/initiate", "{\"params\":{\"name\":\"이외부\"}}", null);
        JsonNode complete = callJson("/api/v1/auth/providers/MOCK/complete", "{\"txId\":\"" + start.at("/txId").asText() + "\"}", null);
        assertThat(complete.at("/identity/subjectScheme").asText()).isEqualTo("EXTERNAL_SUB");
        assertThat(complete.at("/registration/qimUserId").asText()).isEqualTo(USER_EXT);

        // GUEST — email 속성은 값이 없으니 생략, name_masked 는 프로필에서
        String code = "TC_S4_EMAIL";
        putProfile(code, """
                {"schemaVersion":1,
                 "tenant":{"code":"%s","name":"S4 이메일 기관"},
                 "protocol":{"type":"DIRECT","endpoints":{"callbackWhitelist":["https://tenant.example.org/cb"]}},
                 "identity":{"subjectScheme":"EMAIL","attributes":["name_masked","email"]},
                 "policy":{"minAuthLevel":"L1"}}
                """.formatted(code));
        JsonNode guest = okJson(call("/api/v1/handoff/verify", "{\"ticketId\":\"" + issue(code, USER_EXT) + "\"}", agencyHeaders(code)));
        assertThat(guest.at("/state").asText()).isEqualTo("GUEST");
        assertThat(guest.at("/subject/agencySubjectId").isMissingNode() || guest.at("/subject/agencySubjectId").isNull()).isTrue();
        assertThat(guest.get("attributes").get("name_masked").asText()).isEqualTo("이*부");
        assertThat(guest.get("attributes").has("email")).isFalse();

        // required — 같은 사용자, email 을 필수로 요구하는 기관
        String req = "TC_S4_REQ";
        putProfile(req, """
                {"schemaVersion":1,
                 "tenant":{"code":"%s","name":"S4 필수속성 기관"},
                 "protocol":{"type":"DIRECT","endpoints":{"callbackWhitelist":["https://tenant.example.org/cb"]}},
                 "identity":{"attributes":[{"name":"email","required":true}]},
                 "policy":{"minAuthLevel":"L1"}}
                """.formatted(req));
        String ticketId = issue(req, USER_EXT);
        ResponseEntity<String> rejected = call("/api/v1/handoff/verify", "{\"ticketId\":\"" + ticketId + "\"}", agencyHeaders(req));
        assertThat(rejected.getStatusCode().value()).as("body=%s", rejected.getBody()).isEqualTo(422);
        assertThat(objectMapper.readTree(rejected.getBody()).at("/code").asText()).isEqualTo("E-IDO-114");
        // 티켓은 ISSUED 그대로 — 기관이 프로파일을 고치면 재시도 가능 (consume 전에 거부되었으므로)
        ResponseEntity<String> again = call("/api/v1/handoff/verify", "{\"ticketId\":\"" + ticketId + "\"}", agencyHeaders(req));
        assertThat(again.getStatusCode().value()).isEqualTo(422);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void putProfile(String code, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Admin-Id", "s4-admin");
        ResponseEntity<String> res = restTemplate.exchange(url("/api/v1/admin/tenants/" + code + "/profile"),
                HttpMethod.PUT, new HttpEntity<>(body, h), String.class);
        assertThat(res.getStatusCode().value()).as("profile PUT body=%s", res.getBody()).isEqualTo(200);
        // API 키는 프로파일 밖(rotate-key 경로) — 테스트는 해시를 직접 심는다
        jdbcTemplate.update("UPDATE ido.agency_meta SET api_key_hash = ? WHERE agency_code = ?", sha256Hex(AGENCY_KEY), code);
    }

    /** FE 세션(등록된 사용자) + 기관 키로 Handoff 발급 → ticketId */
    private String issue(String code, String qimUserId) throws Exception {
        FeSession session = feSessionService.create(qimUserId, UUID.randomUUID().toString(), "L1", null);
        HttpHeaders h = agencyHeaders(code);
        h.add(HttpHeaders.COOKIE, "Fe-Session-Id=" + session.getFeSessionId());
        String body = """
                {"agencyCode":"%s","authResultId":"%s","authLevel":"L1","providerCode":"MOCK","callbackUrl":"https://tenant.example.org/cb"}
                """.formatted(code, UUID.randomUUID());
        ResponseEntity<String> res = call("/api/v1/handoff/issue", body, h);
        assertThat(res.getStatusCode().value()).as("issue body=%s", res.getBody()).isEqualTo(200);
        return objectMapper.readTree(res.getBody()).get("ticketId").asText();
    }

    private HttpHeaders agencyHeaders(String code) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Agency-Code", code);
        h.set("X-Agency-Key", AGENCY_KEY);
        return h;
    }

    private ResponseEntity<String> call(String path, String body, HttpHeaders headers) {
        HttpHeaders h = headers != null ? headers : new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Correlation-Id", UUID.randomUUID().toString());
        return restTemplate.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    private JsonNode callJson(String path, String body, HttpHeaders headers) throws Exception {
        return okJson(call(path, body, headers));
    }

    private JsonNode okJson(ResponseEntity<String> res) throws Exception {
        assertThat(res.getStatusCode().is2xxSuccessful()).as("status=%s body=%s", res.getStatusCode(), res.getBody()).isTrue();
        return objectMapper.readTree(res.getBody());
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder wm(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static String sha256Hex(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

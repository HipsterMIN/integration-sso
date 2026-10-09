package io.github.hipstermin.idem.hub.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import io.github.hipstermin.idem.hub.webhook.WebhookDispatcherService;
import io.github.hipstermin.idem.hub.webhook.WebhookSigningSecrets;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * 1.1.1 G1-4 — 웹훅 서명 비밀: 관리 API 로 기관(엔드포인트) 등록 → 회전(KMS 봉인·원문 1회) → 상태(지문) → 실제 발송의 X-Webhook-Signature 가
 * 그 비밀로 검증된다 → 1.0.x 원문 행(signing_secret_hash 에 원문)은 봉인기로 옮겨진다. 시험 항목 B-15.
 */
@DisplayName("1.1.1 G1-4 웹훅 서명 비밀 — 회전·봉인·발송 서명·1.0.x 행 봉인 통합 테스트")
class WebhookSecretIntegrationTest extends IntegrationTestBase {

    private static final String AG = "WEBHOOK_SECRET_001";
    private static final String LEGACY = "WEBHOOK_LEGACY_001";

    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired WebhookDispatcherService dispatcher;
    @Autowired WebhookSigningSecrets signingSecrets;

    @BeforeEach
    void setUp() {
        wireMockServer.resetAll();
        WireMock.configureFor("localhost", wireMockServer.port());
        for (String code : List.of(AG, LEGACY)) {
            jdbcTemplate.update("DELETE FROM idem_hub.webhook_dispatch_outbox WHERE agency_code = ?", code);
            jdbcTemplate.update("DELETE FROM idem_hub.agency_webhook_config WHERE agency_code = ?", code);
            jdbcTemplate.update("DELETE FROM idem_hub.agency_meta_history WHERE agency_code = ?", code);
            jdbcTemplate.update("DELETE FROM idem_hub.agency_meta WHERE agency_code = ?", code);
        }
    }

    private String base() { return "http://localhost:" + port; }

    private HttpHeaders admin() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return withAdmin(h, restTemplate, base());
    }

    @Test
    @DisplayName("등록(웹훅 엔드포인트) → 상태(비밀 없음) → 회전 → 봉인 저장·지문 → 발송 서명 검증 → 1.0.x 원문 행 봉인")
    void rotateSealAndSign() throws Exception {
        // ① 관리 API 로 기관 등록 — 종전에는 이 INSERT 가 NOT NULL 제약에 걸려 웹훅 설정이 조용히 빠졌다
        String hook = "http://localhost:" + wireMockServer.port() + "/hook";
        String body = """
                {"agencyCode":"%s","officialName":"웹훅 기관","minAuthLevel":"L1","integrationType":"DIRECT",
                 "callbackWhitelist":["https://agency.example.org/cb"],"webhookEndpoint":"%s","webhookEnabled":true}
                """.formatted(AG, hook);
        ResponseEntity<String> reg = restTemplate.exchange(base() + "/api/v1/admin/agencies", HttpMethod.POST, new HttpEntity<>(body, admin()), String.class);
        assertThat(reg.getStatusCode().value()).as(reg.getBody()).isIn(200, 201);

        ResponseEntity<Map> st0 = restTemplate.exchange(base() + "/api/v1/admin/agencies/" + AG + "/webhook", HttpMethod.GET, new HttpEntity<>(admin()), Map.class);
        assertThat(st0.getStatusCode().value()).isEqualTo(200);
        assertThat(st0.getBody()).containsEntry("configured", true).containsEntry("hasSecret", false).containsEntry("webhookEnabled", true).containsEntry("endpointUrl", hook);

        // ② 회전 — 원문은 이 응답에만, DB 에는 봉인값 + SHA-256
        ResponseEntity<Map> rot = restTemplate.exchange(base() + "/api/v1/admin/agencies/" + AG + "/webhook/rotate-secret", HttpMethod.POST, new HttpEntity<>(admin()), Map.class);
        assertThat(rot.getStatusCode().value()).as(String.valueOf(rot.getBody())).isEqualTo(200);
        String secret = (String) rot.getBody().get("signingSecret");
        assertThat(secret).hasSizeGreaterThanOrEqualTo(32);
        String hash = CryptoProviders.current().sha256Hex(secret);
        assertThat(rot.getBody()).containsEntry("fingerprint", hash.substring(0, 8));
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT signing_secret_sealed, signing_secret_hash, secret_rotated_at FROM idem_hub.agency_webhook_config WHERE agency_code = ?", AG);
        assertThat((String) row.get("signing_secret_sealed")).isNotBlank().isNotEqualTo(secret);
        assertThat(row.get("signing_secret_hash")).isEqualTo(hash);
        assertThat(row.get("secret_rotated_at")).isNotNull();

        ResponseEntity<Map> st1 = restTemplate.exchange(base() + "/api/v1/admin/agencies/" + AG + "/webhook", HttpMethod.GET, new HttpEntity<>(admin()), Map.class);
        assertThat(st1.getBody()).containsEntry("hasSecret", true).containsEntry("sealed", true).containsEntry("fingerprint", hash.substring(0, 8));

        // ③ 실제 발송: 아웃박스 → 릴레이 → WireMock. X-Webhook-Signature = sha256=HMAC(ts + "." + body, 회전된 비밀)
        stubFor(post(urlPathEqualTo("/hook")).willReturn(aResponse().withStatus(200)));
        int n = dispatcher.enqueueForAssignmentChanged(AG, "pw-subject-1", "ASSIGNED", null, Instant.now(), UUID.randomUUID().toString(), "cid-webhook-1");   // source_event_id 는 VARCHAR(36)
        assertThat(n).isEqualTo(1);
        List<LoggedRequest> reqs = List.of();
        for (int i = 0; i < 100 && reqs.isEmpty(); i++) {
            Thread.sleep(100);
            reqs = wireMockServer.findAll(postRequestedFor(urlPathEqualTo("/hook")));
        }
        assertThat(reqs).as("릴레이가 10초 안에 발송").isNotEmpty();
        LoggedRequest req = reqs.get(0);
        String ts = req.getHeader("X-Webhook-Timestamp");
        String payload = req.getBodyAsString();
        String expected = "sha256=" + CryptoProviders.current().hmacSha256Hex(secret.getBytes(StandardCharsets.UTF_8), ts + "." + payload);
        assertThat(req.getHeader("X-Webhook-Signature")).isEqualTo(expected);
        assertThat(payload.replace(" ", "")).as("jsonb 렌더링은 공백이 들어간다").contains("\"eventType\":\"ASSIGNMENT_CHANGED\"").contains("\"agencySubjectId\":\"pw-subject-1\"");

        // ④ 1.0.x 행: signing_secret_hash 에 원문이 있는 행 → 봉인기가 봉인값 + 해시로 바꾼다, 발송용 원문은 그대로 복원된다
        jdbcTemplate.update("INSERT INTO idem_hub.agency_meta (agency_code, official_name, min_auth_level, policy_version, api_key_hash, active, webhook_enabled) VALUES (?, '구 기관', 'L1', '1.0', 'h', TRUE, TRUE)", LEGACY);
        jdbcTemplate.update("INSERT INTO idem_hub.agency_webhook_config (agency_code, endpoint_url, signing_secret_hash, active) VALUES (?, ?, 'legacy-raw-secret', TRUE)", LEGACY, hook);
        signingSecrets.sealLegacyRows();
        Map<String, Object> legacy = jdbcTemplate.queryForMap("SELECT signing_secret_sealed, signing_secret_hash FROM idem_hub.agency_webhook_config WHERE agency_code = ?", LEGACY);
        assertThat((String) legacy.get("signing_secret_sealed")).isNotBlank().isNotEqualTo("legacy-raw-secret");
        assertThat(legacy.get("signing_secret_hash")).isEqualTo(CryptoProviders.current().sha256Hex("legacy-raw-secret"));
        assertThat(signingSecrets.resolve((String) legacy.get("signing_secret_sealed"), (String) legacy.get("signing_secret_hash"))).isEqualTo("legacy-raw-secret");

        // ⑤ 엔드포인트가 없는 기관의 회전은 404 E-IDO-126
        jdbcTemplate.update("DELETE FROM idem_hub.agency_webhook_config WHERE agency_code = ?", LEGACY);
        ResponseEntity<String> none = restTemplate.exchange(base() + "/api/v1/admin/agencies/" + LEGACY + "/webhook/rotate-secret", HttpMethod.POST, new HttpEntity<>(admin()), String.class);
        assertThat(none.getStatusCode().value()).isEqualTo(404);
        assertThat(none.getBody()).contains("E-IDO-126");
    }
}

package io.github.hipstermin.idem.registry.kr.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.registry.QImApplication;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * 시험 항목 E-6 "KR 기업회원 이관" (G2-15, 2026-10-16 — 리허설(수동) → 자동).
 *
 * <p>일회성 이관 도구 {@code scripts/kr-member-import/import_members.py} 가 registry 에 하는 호출을 <b>같은 순서·같은 본문·같은 헤더</b>로
 * KR 에디션 registry(실제 PostgreSQL, 코어 V1 + KR V1000 마이그레이션, Kafka 없음)에 보낸다 — 개인회원 {@code register-subject}(201 신규),
 * 기업회원 {@code register-subject} → {@code biz-members/convert}(201) → {@code biz_member} 행, 같은 CSV 재실행은 {@code register-subject} 200
 * (같은 qimUserId, existing) → {@code convert} 409 {@code E-IM-216} → {@code GET biz-members/{id}} 200 = 도구의 {@code exists} 판정,
 * {@code --verify} 의 {@code GET /api/v1/internal/users/{id}}, 내부 API 키 없는 호출은 401. CSV 검사·본문 조립은
 * {@code scripts/kr-member-import/test_import_members.py}(Python unittest) 가 본다. 구 플랫폼 CSV 반출·파기는 리허설 몫(README).
 *
 * <p>Docker 가 없으면({@code DOCKER_UNAVAILABLE=true}) 건너뛴다 — CI 는 단위 테스트만.
 */
@SpringBootTest(classes = QImApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisabledIfEnvironmentVariable(named = "DOCKER_UNAVAILABLE", matches = "true")
@DisplayName("E-6 KR 기업회원 이관 — 이관 도구의 API 순서를 KR registry 에 그대로 (실제 PostgreSQL)")
class KrMemberImportIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("idem").withUsername("idem").withPassword("idem")
                    .waitingFor(Wait.forListeningPort());

    static {
        POSTGRES.start();
        Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop));
    }

    private static final String API_KEY  = "it-import-internal-api-key";
    private static final String PROVIDER = "LEGACY_SMES";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=idem_registry");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");                 // 코어 V1~ + KR V1000~ (KrRegistryEditionConfig)
        registry.add("idem.messaging.kafka.enabled", () -> "false");         // D1-b: Kafka 없는 설치 — DisabledKafkaTemplate
        registry.add("idem.registry.outbox.relay-enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("idem.registry.security.internal-api-key", () -> API_KEY);
        // 테스트 전용 키 (실제 키 아님): CI AES-256 32바이트 Base64, DI HMAC 비밀
        registry.add("idem.registry.crypto.ci.key-v1", () -> "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=");
        registry.add("idem.registry.crypto.di.secret", () -> "integration-test-di-secret-0123456789");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper om;

    private String url(String p) { return "http://localhost:" + port + p; }

    /** 도구의 Registry._call 과 같은 헤더 */
    private HttpHeaders headers(String apiKey, String cid) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        if (apiKey != null) h.set("X-Internal-Api-Key", apiKey);
        h.set("X-Correlation-Id", cid);
        return h;
    }

    private ResponseEntity<String> call(HttpMethod m, String path, String body, String apiKey, String cid) {
        return rest.exchange(url(path), m, new HttpEntity<>(body, headers(apiKey, cid)), String.class);
    }

    private JsonNode json(ResponseEntity<String> r) throws Exception { return om.readTree(r.getBody()); }

    /** 도구의 build_register_body — CSV 한 행에서 만드는 본문 */
    private static String registerBody(String scheme, String subjectKey, String name, String mobile, Integer birthYear, String cid) {
        StringBuilder b = new StringBuilder("{\"scheme\":\"").append(scheme).append("\",\"subjectKey\":\"").append(subjectKey)
                .append("\",\"providerCode\":\"").append(PROVIDER).append("\",\"authLevel\":\"L2\",\"tenantCode\":\"DEFAULT\",\"correlationId\":\"").append(cid).append("\"");
        if (name != null) b.append(",\"rawName\":\"").append(name).append("\"");
        if (mobile != null) b.append(",\"rawMobile\":\"").append(mobile).append("\"");
        if (birthYear != null) b.append(",\"birthYear\":").append(birthYear);
        return b.append("}").toString();
    }

    private static String convertBody(String qimUserId, String bizRegNo, String cid) {
        return "{\"qimUserId\":\"" + qimUserId + "\",\"bizRegNo\":\"" + bizRegNo + "\",\"companyName\":\"주식회사 이관시험\","
                + "\"repName\":\"홍길동\",\"bizType\":\"소프트웨어 개발\",\"correlationId\":\"" + cid + "\"}";
    }

    /** 도구의 is_new 판정: 본문에 isNew 가 있으면 그 값, 없으면 201 이면 신규 */
    private static boolean isNew(ResponseEntity<String> r, JsonNode body) {
        return body.has("isNew") ? body.path("isNew").asBoolean() : r.getStatusCode().value() == 201;
    }

    @Test
    @DisplayName("개인·기업회원 CSV 1회 이관 → biz_member 생성; 같은 CSV 재실행 → existing + convert 409 → GET 200 = exists; 검증·API 키")
    void importTwice_isIdempotent() throws Exception {
        String run   = UUID.randomUUID().toString().substring(0, 8);
        String ci    = "ci-import-" + run + "-0001";                                  // CI 원문(시험용 값)
        String email = "import-" + run + "@example.org";
        String bizNo = String.format("%03d-%02d-%05d", ThreadLocalRandom.current().nextInt(100, 1000),
                ThreadLocalRandom.current().nextInt(10, 100), ThreadLocalRandom.current().nextInt(10000, 100000));

        // ── 1회차: INDIVIDUAL(CI) → 201 신규 ────────────────────────────────────────
        ResponseEntity<String> r1 = call(HttpMethod.POST, "/api/v1/internal/users/register-subject",
                registerBody("CI", ci, "홍길동", "01012345678", 1980, "kr-import-" + run + "-1"), API_KEY, "kr-import-" + run + "-1");
        assertThat(r1.getStatusCode().value()).as(r1.getBody()).isEqualTo(201);
        JsonNode b1 = json(r1);
        String user1 = b1.path("qimUserId").asText();
        assertThat(user1).isNotBlank();
        assertThat(isNew(r1, b1)).isTrue();

        // ── 1회차: BIZ(EMAIL) → 201 신규 → convert 201 → biz_member 행 ───────────────
        ResponseEntity<String> r2 = call(HttpMethod.POST, "/api/v1/internal/users/register-subject",
                registerBody("EMAIL", email, "김대표", null, null, "kr-import-" + run + "-2"), API_KEY, "kr-import-" + run + "-2");
        assertThat(r2.getStatusCode().value()).as(r2.getBody()).isEqualTo(201);
        String user2 = json(r2).path("qimUserId").asText();
        assertThat(user2).isNotBlank().isNotEqualTo(user1);
        ResponseEntity<String> c1 = call(HttpMethod.POST, "/api/v1/internal/biz-members/convert", convertBody(user2, bizNo, "kr-import-" + run + "-2"), API_KEY, "kr-import-" + run + "-2");
        assertThat(c1.getStatusCode().value()).as(c1.getBody()).isEqualTo(201);
        assertThat(json(c1).path("qimUserId").asText()).isEqualTo(user2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idem_registry.biz_member WHERE qim_user_id = ?", Integer.class, user2)).isEqualTo(1);

        // ── 2회차(같은 CSV): register-subject 는 200 + 같은 qimUserId(existing) ──────────
        ResponseEntity<String> r1b = call(HttpMethod.POST, "/api/v1/internal/users/register-subject",
                registerBody("CI", ci, "홍길동", "01012345678", 1980, "kr-import-" + run + "-3"), API_KEY, "kr-import-" + run + "-3");
        assertThat(r1b.getStatusCode().value()).as(r1b.getBody()).isEqualTo(200);
        assertThat(json(r1b).path("qimUserId").asText()).isEqualTo(user1);
        assertThat(isNew(r1b, json(r1b))).isFalse();
        ResponseEntity<String> r2b = call(HttpMethod.POST, "/api/v1/internal/users/register-subject",
                registerBody("EMAIL", email, "김대표", null, null, "kr-import-" + run + "-4"), API_KEY, "kr-import-" + run + "-4");
        assertThat(r2b.getStatusCode().value()).as(r2b.getBody()).isEqualTo(200);
        assertThat(json(r2b).path("qimUserId").asText()).isEqualTo(user2);

        // ── 2회차 convert: 409 E-IM-216 → 도구는 GET biz-members/{id} 200 이면 "exists" ──
        ResponseEntity<String> c2 = call(HttpMethod.POST, "/api/v1/internal/biz-members/convert", convertBody(user2, bizNo, "kr-import-" + run + "-4"), API_KEY, "kr-import-" + run + "-4");
        assertThat(c2.getStatusCode().value()).as(c2.getBody()).isEqualTo(409);
        assertThat(c2.getBody()).contains("E-IM-216");
        ResponseEntity<String> g = call(HttpMethod.GET, "/api/v1/internal/biz-members/" + user2, null, API_KEY, "kr-import-" + run + "-4");
        assertThat(g.getStatusCode().value()).as(g.getBody()).isEqualTo(200);
        assertThat(json(g).path("qimUserId").asText()).isEqualTo(user2);
        assertThat(json(g).path("companyName").asText()).isEqualTo("주식회사 이관시험");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idem_registry.biz_member WHERE qim_user_id = ?", Integer.class, user2)).isEqualTo(1);
        // 개인회원에게는 기업회원 정보가 없다(404) — 도구는 convert 가 409 일 때만 GET 으로 exists 를 가린다
        assertThat(call(HttpMethod.GET, "/api/v1/internal/biz-members/" + user1, null, API_KEY, "kr-import-" + run + "-5").getStatusCode().value()).isEqualTo(404);

        // ── --verify: 매핑의 qimUserId 를 다시 조회 ────────────────────────────────────
        for (String id : new String[] {user1, user2}) {
            ResponseEntity<String> v = call(HttpMethod.GET, "/api/v1/internal/users/" + id, null, API_KEY, "kr-import-" + run + "-6");
            assertThat(v.getStatusCode().value()).as(v.getBody()).isEqualTo(200);
            assertThat(json(v).path("qimUserId").asText()).isEqualTo(id);
            assertThat(v.getBody()).as("CI 원문은 밖으로 나가지 않는다").doesNotContain(ci);
        }

        // ── 내부 API 키 없음·불일치 → 401 (도구는 IDEM_REGISTRY_INTERNAL_API_KEY 가 필수) ──
        assertThat(call(HttpMethod.POST, "/api/v1/internal/users/register-subject", registerBody("CI", ci, null, null, null, "x"), null, "x").getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, "/api/v1/internal/biz-members/convert", convertBody(user2, bizNo, "x"), "wrong-key", "x").getStatusCode().value()).isEqualTo(401);
    }
}

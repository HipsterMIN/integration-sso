package io.github.hipstermin.idem.hub.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.hub.domain.AgencyMeta;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.infrastructure.AgencyMetaRepository;
import io.github.hipstermin.idem.hub.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import io.github.hipstermin.idem.hub.infrastructure.jpa.repository.AgencyMetaJpaRepository;
import java.util.List;
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
 * S2 범용화 — Service Profile 로 기관을 온보딩하고, 프로파일과 낱개 컬럼이 항상 일치하는지 검증한다.
 */
@DisplayName("S2 — Service Profile Admin API 통합 테스트")
class ServiceProfileIntegrationTest extends IntegrationTestBase {

    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;
    @Autowired AgencyMetaRepository agencyMetaRepository;
    @Autowired AgencyMetaJpaRepository jpaRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    private static final List<String> CODES = List.of("TC_S2_ONBOARD", "TC_S2_BAD", "TC_S2_LEGACY", "TC_S3_SIM", "TC_S4B_SVC");

    /** 공유 DB(로컬 PostgreSQL 재사용 포함)에서도 결정적이도록 대상 기관과 이력을 비운다. */
    @BeforeEach
    void cleanTenants() {
        for (String code : CODES) {
            jdbcTemplate.update("DELETE FROM ido.agency_meta_history WHERE agency_code = ?", code);
            jdbcTemplate.update("DELETE FROM ido.agency_webhook_config WHERE agency_code = ?", code);
            jdbcTemplate.update("DELETE FROM ido.agency_meta WHERE agency_code = ?", code);
        }
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private ResponseEntity<String> put(String code, String json) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Admin-Id", "s2-admin");
        h.set("X-Change-Reason", "S2 통합 테스트");
        return restTemplate.exchange(url("/api/v1/admin/services/" + code + "/profile"), HttpMethod.PUT,
                new HttpEntity<>(json, h), String.class);
    }

    private ResponseEntity<String> get(String code) {
        return restTemplate.getForEntity(url("/api/v1/admin/services/" + code + "/profile"), String.class);
    }

    private JsonNode json(ResponseEntity<String> res) throws Exception { return objectMapper.readTree(res.getBody()); }

    @Test
    @DisplayName("프로파일 PUT 만으로 기관이 생성되고, 컬럼 투영·이력·재조회가 일치한다")
    void putProfile_onboardsTenant() throws Exception {
        String code = "TC_S2_ONBOARD";
        String body = """
                {"schemaVersion":1,
                 "service":{"code":"%s","name":"S2 온보딩 기관"},
                 "protocol":{"type":"BRIDGE","endpoints":{"bridge":"https://bridge.example.org/push","callbackWhitelist":["https://tenant.example.org/cb"]}},
                 "identity":{"attributes":["name_masked"],"attributeMapping":{"name_masked":"userNm"}},
                 "policy":{"minAuthLevel":"L2","policyVersion":"1.2","session":{"idleMinutes":20}},
                 "limits":{"daily":12345},
                 "ui":{"brandName":"기관 S2"}}
                """.formatted(code);

        ResponseEntity<String> res = put(code, body);
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        assertThat(json(res).at("/service/code").asText()).isEqualTo(code);

        // 컬럼 투영 — 기존 런타임 경로(AgencyMetaRepository)가 같은 값을 본다
        AgencyMeta meta = agencyMetaRepository.findByCode(code).orElseThrow();
        assertThat(meta.getIntegrationType()).isEqualTo(IntegrationType.BRIDGE);
        assertThat(meta.getBridgeEndpoint()).isEqualTo("https://bridge.example.org/push");
        assertThat(meta.getMinAuthLevel()).isEqualTo(AuthResult.AuthLevel.L2);
        assertThat(meta.getPolicyVersion()).isEqualTo("1.2");
        assertThat(meta.getCallbackWhitelist()).containsExactly("https://tenant.example.org/cb");
        assertThat(meta.getAllowedAttributes()).containsExactly("name_masked");
        assertThat(meta.isActive()).isTrue();

        AgencyMetaJpaEntity row = jpaRepository.findById(code).orElseThrow();
        assertThat(row.getDailyLookupLimit()).isEqualTo(12345);
        assertThat(row.getProfileSchemaVersion()).isEqualTo(1);

        // 이력 1건
        Integer history = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ido.agency_meta_history WHERE agency_code = ?", Integer.class, code);
        assertThat(history).isEqualTo(1);

        // GET — 프로파일 전용 항목(ui·session·mapping)이 그대로 돌아온다
        JsonNode fetched = json(get(code));
        assertThat(fetched.at("/ui/brandName").asText()).isEqualTo("기관 S2");
        assertThat(fetched.at("/policy/session/idleMinutes").asInt()).isEqualTo(20);
        assertThat(fetched.at("/identity/attributeMapping/name_masked").asText()).isEqualTo("userNm");
    }

    @Test
    @DisplayName("스키마 위반·코드 불일치는 400 E-IDO-113 이고 아무것도 저장하지 않는다")
    void putProfile_invalid_rejected() throws Exception {
        String code = "TC_S2_BAD";
        ResponseEntity<String> missingPolicy = put(code,
                "{\"schemaVersion\":1,\"service\":{\"code\":\"" + code + "\",\"name\":\"x\"},\"protocol\":{\"type\":\"DIRECT\"}}");
        assertThat(missingPolicy.getStatusCode().value()).isEqualTo(400);
        assertThat(missingPolicy.getBody()).contains("E-IDO-113").contains("policy");

        ResponseEntity<String> mismatch = put(code,
                "{\"schemaVersion\":1,\"service\":{\"code\":\"OTHER\",\"name\":\"x\"},\"protocol\":{\"type\":\"DIRECT\"},\"policy\":{\"minAuthLevel\":\"L1\"}}");
        assertThat(mismatch.getStatusCode().value()).isEqualTo(400);
        assertThat(mismatch.getBody()).contains("E-IDO-113");

        assertThat(jpaRepository.findById(code)).isEmpty();
    }

    @Test
    @DisplayName("레거시 경로로 만든 기관은 컬럼에서 합성돼 조회되고, 도메인 저장소로 고쳐도 프로파일 전용 항목은 남는다")
    void legacyRows_synthesizeAndStayInSync() throws Exception {
        String code = "TC_S2_LEGACY";
        jpaRepository.save(AgencyMetaJpaEntity.builder()
                .agencyCode(code).officialName("레거시 기관").minAuthLevel("L1").policyVersion("1.0")
                .integrationType(IntegrationType.DIRECT).active(true).build());

        // 프로파일 컬럼 없이 저장된 행 → 컬럼 합성
        JsonNode synthesized = json(get(code));
        assertThat(synthesized.at("/protocol/type").asText()).isEqualTo("DIRECT");
        assertThat(synthesized.at("/service/name").asText()).isEqualTo("레거시 기관");

        // 프로파일로 ui 를 채운 뒤
        ResponseEntity<String> res = put(code, """
                {"schemaVersion":1,"service":{"code":"%s","name":"레거시 기관"},
                 "protocol":{"type":"DIRECT"},"policy":{"minAuthLevel":"L1"},"ui":{"brandName":"보존돼야 함"}}
                """.formatted(code));
        assertThat(res.getStatusCode().value()).isEqualTo(200);

        // 도메인 저장소(레거시 쓰기 경로)로 이름·수준을 바꿔도
        agencyMetaRepository.save(AgencyMeta.builder()
                .agencyCode(code).officialName("도메인 경로로 변경").minAuthLevel(AuthResult.AuthLevel.L3)
                .policyVersion("1.0").integrationType(IntegrationType.DIRECT).callbackWhitelist(List.of())
                .active(true).build());

        JsonNode after = json(get(code));
        assertThat(after.at("/service/name").asText()).isEqualTo("도메인 경로로 변경");
        assertThat(after.at("/policy/minAuthLevel").asText()).isEqualTo("L3");
        assertThat(after.at("/ui/brandName").asText()).isEqualTo("보존돼야 함");
        // PostgreSQL 이 돌려주는 jsonb 원문은 공백이 정규화되므로 파싱해서 비교
        JsonNode stored = objectMapper.readTree(jpaRepository.findById(code).orElseThrow().getProfile());
        assertThat(stored.at("/ui/brandName").asText()).isEqualTo("보존돼야 함");
    }

    @Test
    @DisplayName("S3: 정책 시뮬레이션 — 프로파일 규칙(최소 수준·허용 제공자)이 요청별로 어떻게 판정하는지 전부 보여준다")
    void policySimulation_reportsEveryRule() throws Exception {
        String code = "TC_S3_SIM";
        assertThat(put(code, """
                {"schemaVersion":1,"service":{"code":"%s","name":"S3 시뮬레이션 기관"},
                 "protocol":{"type":"DIRECT"},
                 "policy":{"minAuthLevel":"L2","allowedProviders":["NICE"]}}
                """.formatted(code)).getStatusCode().value()).isEqualTo(200);

        HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> denied = restTemplate.exchange(url("/api/v1/admin/services/" + code + "/policy/simulate"),
                HttpMethod.POST, new HttpEntity<>("{\"authLevel\":\"LOW\",\"providerCode\":\"MOCK\",\"userStatus\":\"ACTIVE\"}", h), String.class);
        assertThat(denied.getStatusCode().value()).as("body=%s", denied.getBody()).isEqualTo(200);
        JsonNode d = json(denied);
        assertThat(d.at("/allowed").asBoolean()).isFalse();
        // 첫 거부에서 멈추지 않고 전부 평가 — MIN_AUTH_LEVEL·ALLOWED_PROVIDERS 둘 다 DENY, USER_STATUS 는 ALLOW, ASSIGNMENT(S8-b) 는 정책 미적용 ALLOW
        assertThat(d.at("/decisions")).hasSize(5);
        assertThat(d.at("/decisions/4/rule").asText()).isEqualTo("ASSIGNMENT");
        assertThat(d.at("/decisions/4/outcome").asText()).isEqualTo("ALLOW");
        assertThat(d.at("/decisions/1/rule").asText()).isEqualTo("MIN_AUTH_LEVEL");
        assertThat(d.at("/decisions/1/outcome").asText()).isEqualTo("DENY");
        assertThat(d.at("/decisions/2/rule").asText()).isEqualTo("ALLOWED_PROVIDERS");
        assertThat(d.at("/decisions/2/outcome").asText()).isEqualTo("DENY");
        assertThat(d.at("/decisions/3/rule").asText()).isEqualTo("USER_STATUS");
        assertThat(d.at("/decisions/3/outcome").asText()).isEqualTo("ALLOW");

        ResponseEntity<String> allowed = restTemplate.exchange(url("/api/v1/admin/services/" + code + "/policy/simulate"),
                HttpMethod.POST, new HttpEntity<>("{\"authLevel\":\"L2\",\"providerCode\":\"nice\"}", h), String.class);
        JsonNode a = json(allowed);
        assertThat(a.at("/allowed").asBoolean()).isTrue();
        assertThat(a.at("/decisions/3/outcome").asText()).isEqualTo("SKIP"); // 사용자 상태 미지정
    }

    @Test
    @DisplayName("S4b: Service 는 Tenant(Realm) 에 속한다 — 기본 DEFAULT, Tenant Admin API 로 만든 Tenant 지정, 미등록 Tenant 는 400")
    void serviceBelongsToTenant() throws Exception {
        // Tenant 목록에 V22 시드 DEFAULT 가 있다
        ResponseEntity<String> list = restTemplate.getForEntity(url("/api/v1/admin/tenants"), String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(json(list).findValuesAsText("code")).contains("DEFAULT");

        // 새 Tenant 생성
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Admin-Id", "s4b-admin");
        ResponseEntity<String> tenantRes = restTemplate.exchange(url("/api/v1/admin/tenants/TC_S4B_TENANT"), HttpMethod.PUT,
                new HttpEntity<>("{\"name\":\"S4b 테넌트\",\"status\":\"ACTIVE\"}", h), String.class);
        assertThat(tenantRes.getStatusCode().value()).as("body=%s", tenantRes.getBody()).isEqualTo(200);
        assertThat(json(tenantRes).at("/code").asText()).isEqualTo("TC_S4B_TENANT");

        // service.tenant 를 지정한 프로파일 → 컬럼·JSONB 모두 반영, 루트 블록은 service (tenant 블록 없음)
        String code = "TC_S4B_SVC";
        ResponseEntity<String> res = put(code, """
                {"schemaVersion":1,
                 "service":{"code":"%s","name":"S4b 서비스","tenant":"TC_S4B_TENANT"},
                 "protocol":{"type":"DIRECT"},
                 "policy":{"minAuthLevel":"L1"}}
                """.formatted(code));
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(200);
        assertThat(json(res).at("/service/tenant").asText()).isEqualTo("TC_S4B_TENANT");
        assertThat(jdbcTemplate.queryForObject("SELECT tenant_code FROM ido.agency_meta WHERE agency_code = ?", String.class, code))
                .isEqualTo("TC_S4B_TENANT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT jsonb_exists(profile, 'tenant') OR NOT jsonb_exists(profile, 'service') FROM ido.agency_meta WHERE agency_code = ?", Boolean.class, code))
                .isFalse();

        // tenant 생략 → DEFAULT
        ResponseEntity<String> def = put(code, """
                {"schemaVersion":1,"service":{"code":"%s","name":"S4b 서비스"},"protocol":{"type":"DIRECT"},"policy":{"minAuthLevel":"L1"}}
                """.formatted(code));
        assertThat(json(def).at("/service/tenant").asText()).isEqualTo("DEFAULT");

        // 미등록 Tenant → 400 E-IDO-113
        ResponseEntity<String> bad = put(code, """
                {"schemaVersion":1,"service":{"code":"%s","name":"x","tenant":"NO_SUCH_TENANT"},"protocol":{"type":"DIRECT"},"policy":{"minAuthLevel":"L1"}}
                """.formatted(code));
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        assertThat(json(bad).at("/code").asText()).isEqualTo("E-IDO-113");
        assertThat(json(bad).at("/message").asText()).contains("NO_SUCH_TENANT");

        // V22 이관 검증: 종전 형식(tenant 블록)으로 저장된 행도 service 로 읽힌다 — 마이그레이션 SQL 을 그대로 재적용
        jdbcTemplate.update("UPDATE ido.agency_meta SET profile = (profile - 'service') || jsonb_build_object('tenant', profile->'service') WHERE agency_code = ?", code);
        jdbcTemplate.update("""
                UPDATE ido.agency_meta
                   SET profile = (profile - 'tenant') || jsonb_build_object('service', (profile -> 'tenant') || jsonb_build_object('tenant', tenant_code))
                 WHERE profile IS NOT NULL AND jsonb_exists(profile, 'tenant') AND NOT jsonb_exists(profile, 'service') AND agency_code = ?
                """, code);
        assertThat(json(get(code)).at("/service/code").asText()).isEqualTo(code);
    }

    @Test
    @DisplayName("스키마 엔드포인트는 v1 스키마 원문을 돌려준다")
    void schemaEndpoint() {
        ResponseEntity<String> res = restTemplate.getForEntity(url("/api/v1/admin/services/profile-schema"), String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).contains("Idem Service Profile v1");
    }
}

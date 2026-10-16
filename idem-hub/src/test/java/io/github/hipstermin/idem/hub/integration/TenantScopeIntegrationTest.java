package io.github.hipstermin.idem.hub.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * 시험 항목 B-8 "테넌트 범위" (G2-13, 2026-10-16 — 3차 적대적 점검 A7 의 실측을 코드로).
 *
 * <p>글로벌 SYSTEM_ADMIN 이 Tenant T2 와 Service 둘(DEFAULT·T2)을 만들고 T2 의 POLICY_ADMIN 을 만든다. 그 관리자로 로그인(임시 비밀번호 →
 * 변경)한 뒤: DEFAULT Service 의 프로파일·기관 조회는 403, 목록에서 빠지고, 자기 Service 는 200, 본문 {@code service.tenant=DEFAULT} 위장은 403.
 */
@DisplayName("S7 테넌트 범위 통합 — Tenant 관리자는 자기 Tenant 의 Service 만 본다 (B-8)")
class TenantScopeIntegrationTest extends IntegrationTestBase {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;

    private static final String TENANT  = "IT_B8_T2";
    private static final String SVC_DEF = "IT_B8_SVC_DEF";
    private static final String SVC_T2  = "IT_B8_SVC_T2";
    private static final String USER    = "it-b8-padmin";
    private static final String NEW_PW  = "Tenant-Admin-2026!";

    private String url(String p) { return "http://localhost:" + port + p; }

    private ResponseEntity<String> call(HttpMethod m, String path, String body, HttpHeaders h) {
        return rest.exchange(url(path), m, new HttpEntity<>(body, h), String.class);
    }

    private HttpHeaders json(HttpHeaders base) {
        HttpHeaders h = new HttpHeaders(); if (base != null) h.addAll(base);
        h.setContentType(MediaType.APPLICATION_JSON); h.set(AdminTestSupport.CSRF, "it");
        return h;
    }

    private JsonNode read(String s) throws Exception { return mapper.readTree(s); }

    private static String profile(String code, String tenant) {
        return "{\"schemaVersion\":1,\"service\":{\"code\":\"" + code + "\",\"name\":\"B8 " + code + "\""
                + (tenant == null ? "" : ",\"tenant\":\"" + tenant + "\"") + "},\"protocol\":{\"type\":\"DIRECT\"},\"policy\":{\"minAuthLevel\":\"L1\"}}";
    }

    @Test
    @DisplayName("T2 의 POLICY_ADMIN: DEFAULT Service 조회 403·목록 제외, 자기 Service 200, service.tenant 위장 403")
    void tenantAdminSeesOnlyOwnTenant() throws Exception {
        HttpHeaders admin = json(adminHeaders(rest, url("")));

        // ── 준비(글로벌 관리자): Tenant T2, Service 둘, T2 의 POLICY_ADMIN ───────────────
        assertThat(call(HttpMethod.PUT, "/api/v1/admin/tenants/" + TENANT, "{\"name\":\"B8 테넌트\",\"status\":\"ACTIVE\"}", admin)
                .getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> def = call(HttpMethod.PUT, "/api/v1/admin/services/" + SVC_DEF + "/profile", profile(SVC_DEF, null), admin);
        assertThat(def.getStatusCode().value()).as("body=%s", def.getBody()).isEqualTo(200);
        ResponseEntity<String> t2 = call(HttpMethod.PUT, "/api/v1/admin/services/" + SVC_T2 + "/profile", profile(SVC_T2, TENANT), admin);
        assertThat(t2.getStatusCode().value()).as("body=%s", t2.getBody()).isEqualTo(200);
        assertThat(read(t2.getBody()).at("/service/tenant").asText()).isEqualTo(TENANT);

        ResponseEntity<String> created = call(HttpMethod.POST, "/api/v1/admin/admins",
                "{\"username\":\"" + USER + "\",\"displayName\":\"B8\",\"role\":\"POLICY_ADMIN\",\"tenantCode\":\"" + TENANT + "\"}", admin);
        assertThat(created.getStatusCode().value()).as("body=%s", created.getBody()).isEqualTo(201);
        String temp = read(created.getBody()).path("temporaryPassword").asText();

        // ── T2 관리자 로그인 → 임시 비밀번호 변경 ────────────────────────────────────
        String sid = AdminTestSupport.login(rest, url(""), USER, temp);
        HttpHeaders tenantAdmin = new HttpHeaders();
        tenantAdmin.set(HttpHeaders.COOKIE, "idemAdminSid=" + sid);
        tenantAdmin.set(AdminTestSupport.CSRF, "it");
        ResponseEntity<String> changed = call(HttpMethod.POST, "/api/v1/admin/auth/password",
                "{\"currentPassword\":\"" + temp + "\",\"newPassword\":\"" + NEW_PW + "\"}", json(tenantAdmin));
        assertThat(changed.getStatusCode().value()).as("body=%s", changed.getBody()).isEqualTo(204);

        // ── 범위 밖(DEFAULT) 은 403, 안(T2) 은 200 ──────────────────────────────────
        ResponseEntity<String> deniedProfile = call(HttpMethod.GET, "/api/v1/admin/services/" + SVC_DEF + "/profile", null, tenantAdmin);
        assertThat(deniedProfile.getStatusCode().value()).as("body=%s", deniedProfile.getBody()).isEqualTo(403);
        assertThat(read(deniedProfile.getBody()).path("code").asText()).startsWith("E-IDO-13");
        assertThat(call(HttpMethod.GET, "/api/v1/admin/agencies/" + SVC_DEF, null, tenantAdmin).getStatusCode().value()).isEqualTo(403);

        ResponseEntity<String> own = call(HttpMethod.GET, "/api/v1/admin/services/" + SVC_T2 + "/profile", null, tenantAdmin);
        assertThat(own.getStatusCode().value()).as("body=%s", own.getBody()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/agencies/" + SVC_T2, null, tenantAdmin).getStatusCode().value()).isEqualTo(200);

        // ── 목록은 DB 에서 걸러진다 ────────────────────────────────────────────────────
        ResponseEntity<String> list = call(HttpMethod.GET, "/api/v1/admin/agencies?size=200", null, tenantAdmin);
        assertThat(list.getStatusCode().value()).as("body=%s", list.getBody()).isEqualTo(200);
        assertThat(list.getBody()).contains(SVC_T2).doesNotContain(SVC_DEF);
        ResponseEntity<String> all = call(HttpMethod.GET, "/api/v1/admin/agencies?size=200", null, admin);
        assertThat(all.getBody()).contains(SVC_T2).contains(SVC_DEF);

        // ── 본문 service.tenant 위장(DEFAULT 로 옮기기) 은 403, 다른 Tenant 의 Service 수정도 403 ─
        ResponseEntity<String> spoof = call(HttpMethod.PUT, "/api/v1/admin/services/" + SVC_T2 + "/profile", profile(SVC_T2, "DEFAULT"), json(tenantAdmin));
        assertThat(spoof.getStatusCode().value()).as("body=%s", spoof.getBody()).isEqualTo(403);
        ResponseEntity<String> foreign = call(HttpMethod.PUT, "/api/v1/admin/services/" + SVC_DEF + "/profile", profile(SVC_DEF, null), json(tenantAdmin));
        assertThat(foreign.getStatusCode().value()).as("body=%s", foreign.getBody()).isEqualTo(403);
        // 자기 Tenant 로 자기 Service 를 고치는 것은 된다
        ResponseEntity<String> ok = call(HttpMethod.PUT, "/api/v1/admin/services/" + SVC_T2 + "/profile", profile(SVC_T2, TENANT), json(tenantAdmin));
        assertThat(ok.getStatusCode().value()).as("body=%s", ok.getBody()).isEqualTo(200);
    }
}

package io.github.hipstermin.idem.hub.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.hub.admin.auth.AdminProperties;
import io.github.hipstermin.idem.hub.admin.auth.TotpService;
import java.time.Instant;
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
 * S7 끝-끝: 부트스트랩 관리자 → 로그인·TOTP 등록 → 보호 API → 역할·CSRF·잠금 → 감사 검색 → 관리자 생성(임시 비밀번호·변경 강제).
 */
@DisplayName("S7 관리자 인증 통합 — 무인증 관리 엔드포인트 0")
class AdminAuthIntegrationTest extends IntegrationTestBase {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;

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

    @Test
    @DisplayName("무인증·세션 없음은 401, CSRF 헤더 없는 쓰기는 403, 로그인 뒤에는 통과")
    void unauthenticatedIsRejected_thenLoginWorks() throws Exception {
        assertThat(call(HttpMethod.GET, "/api/v1/admin/services/profile-schema", null, new HttpHeaders()).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/tenants", null, new HttpHeaders()).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/actuator/features", null, new HttpHeaders()).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/actuator/health", null, new HttpHeaders()).getStatusCode().value()).isEqualTo(200);
        assertThat(call(HttpMethod.DELETE, "/api/v1/handoff/t-1?revokeReason=x", null, new HttpHeaders()).getStatusCode().value()).isIn(401, 403);
        // 종전 X-Admin-Id 헤더는 아무 효력이 없다
        HttpHeaders legacy = json(null); legacy.set("X-Admin-Id", "SYSTEM");
        assertThat(call(HttpMethod.PUT, "/api/v1/admin/services/X/profile", "{}", legacy).getStatusCode().value()).isEqualTo(401);

        HttpHeaders admin = adminHeaders(rest, url(""));
        ResponseEntity<String> me = call(HttpMethod.GET, "/api/v1/admin/auth/me", null, admin);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(read(me.getBody()).path("username").asText()).isEqualTo("admin");
        assertThat(read(me.getBody()).path("role").asText()).isEqualTo("SYSTEM_ADMIN");
        assertThat(call(HttpMethod.GET, "/api/v1/admin/services/profile-schema", null, admin).getStatusCode().value()).isEqualTo(200);
        // 테스트 클래스패스의 application.yml 은 actuator 노출을 health 로 좁힌다 — 인증 뒤엔 필터가 막지 않는다는 것만 본다
        assertThat(call(HttpMethod.GET, "/actuator/features", null, admin).getStatusCode().value()).isNotIn(401, 403);

        // 쓰기는 CSRF 헤더 없이 403
        HttpHeaders noCsrf = new HttpHeaders(); noCsrf.set(HttpHeaders.COOKIE, admin.getFirst(HttpHeaders.COOKIE)); noCsrf.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> denied = call(HttpMethod.PUT, "/api/v1/admin/tenants/IT_T1", "{\"name\":\"t\"}", noCsrf);
        assertThat(denied.getStatusCode().value()).isEqualTo(403);
        assertThat(read(denied.getBody()).path("code").asText()).isEqualTo("E-IDO-131");
    }

    @Test
    @DisplayName("관리자 생성 → 임시 비밀번호로 로그인하면 비밀번호 변경 전엔 다른 API 가 403(E-IDO-137), 변경 후 AUDITOR 는 쓰기 403·읽기 200, 감사에 남는다")
    void createAuditor_passwordChangeRequired_roleEnforced_audited() throws Exception {
        HttpHeaders admin = json(adminHeaders(rest, url("")));
        ResponseEntity<String> created = call(HttpMethod.POST, "/api/v1/admin/admins",
                "{\"username\":\"it-auditor\",\"displayName\":\"IT\",\"role\":\"AUDITOR\"}", admin);
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        String temp = read(created.getBody()).path("temporaryPassword").asText();
        String adminId = read(created.getBody()).path("admin").path("adminId").asText();

        String sid = AdminTestSupport.login(rest, url(""), "it-auditor", temp);
        HttpHeaders auditor = new HttpHeaders(); auditor.set(HttpHeaders.COOKIE, "idemAdminSid=" + sid); auditor.set(AdminTestSupport.CSRF, "it");
        ResponseEntity<String> blocked = call(HttpMethod.GET, "/api/v1/admin/services/profile-schema", null, auditor);
        assertThat(blocked.getStatusCode().value()).isEqualTo(403);
        assertThat(read(blocked.getBody()).path("code").asText()).isEqualTo("E-IDO-137");

        HttpHeaders auditorJson = json(auditor);
        ResponseEntity<String> changed = call(HttpMethod.POST, "/api/v1/admin/auth/password",
                "{\"currentPassword\":\"" + temp + "\",\"newPassword\":\"Auditor-Pass-2026!\"}", auditorJson);
        assertThat(changed.getStatusCode().value()).as(changed.getBody()).isEqualTo(204);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/services/profile-schema", null, auditor).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> write = call(HttpMethod.PUT, "/api/v1/admin/tenants/IT_T2", "{\"name\":\"t\"}", auditorJson);
        assertThat(write.getStatusCode().value()).isEqualTo(403);
        assertThat(read(write.getBody()).path("code").asText()).isEqualTo("E-IDO-131");
        assertThat(call(HttpMethod.GET, "/api/v1/admin/admins", null, auditor).getStatusCode().value()).isEqualTo(403);

        // 감사 검색: 생성·로그인·접근 거부가 남았다 (비동기 발행 → 잠시 대기)
        Thread.sleep(800);
        ResponseEntity<String> audit = call(HttpMethod.GET, "/api/v1/admin/audit?category=ADMIN&size=100", null, admin);
        assertThat(audit.getStatusCode().value()).as(audit.getBody()).isEqualTo(200);
        String body = audit.getBody();
        assertThat(body).contains("ADMIN_CREATED").contains("ADMIN_LOGIN_SUCCESS").contains("ADMIN_ACCESS_DENIED").contains("ADMIN_PASSWORD_CHANGED");
        assertThat(read(body).path("total").asLong()).isGreaterThanOrEqualTo(4);

        // 잠금: 틀린 비밀번호 5회 → 423, 시스템관리자가 unlock
        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> bad = call(HttpMethod.POST, "/api/v1/admin/auth/login",
                    "{\"username\":\"it-auditor\",\"password\":\"wrong-" + i + "\"}", json(null));
            assertThat(bad.getStatusCode().value()).isEqualTo(401);
        }
        ResponseEntity<String> locked = call(HttpMethod.POST, "/api/v1/admin/auth/login",
                "{\"username\":\"it-auditor\",\"password\":\"Auditor-Pass-2026!\"}", json(null));
        assertThat(locked.getStatusCode().value()).isEqualTo(423);
        assertThat(read(locked.getBody()).path("code").asText()).isEqualTo("E-IDO-133");
        assertThat(call(HttpMethod.POST, "/api/v1/admin/admins/" + adminId + "/unlock", null, admin).getStatusCode().value()).isEqualTo(200);
        String sid2 = AdminTestSupport.login(rest, url(""), "it-auditor", "Auditor-Pass-2026!");
        assertThat(sid2).isNotBlank();
        // concurrent=1: 이전 세션은 끝났다
        assertThat(call(HttpMethod.GET, "/api/v1/admin/auth/me", null, auditor).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("TOTP 코드가 틀리면 세션이 나가지 않고, 대기 토큰은 1회용이다")
    void wrongTotpRejected() throws Exception {
        ResponseEntity<String> login = call(HttpMethod.POST, "/api/v1/admin/auth/login",
                "{\"username\":\"admin\",\"password\":\"" + AdminTestSupport.PASSWORD + "\"}", json(null));
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        JsonNode b = read(login.getBody());
        String token = b.path("mfaToken").asText();
        String secret = b.path("status").asText().equals("MFA_ENROLL_REQUIRED") ? b.path("secret").asText() : AdminTestSupport.totpSecret("admin");
        assertThat(secret).isNotNull();
        String good = new TotpService(new AdminProperties()).currentCode(secret, Instant.now());
        String bad = good.equals("000000") ? "111111" : "000000";
        ResponseEntity<String> wrong = call(HttpMethod.POST, "/api/v1/admin/auth/mfa", "{\"mfaToken\":\"" + token + "\",\"code\":\"" + bad + "\"}", json(null));
        assertThat(wrong.getStatusCode().value()).isEqualTo(401);
        assertThat(wrong.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNull();
        // 토큰은 소비됐다 — 같은 토큰으로 맞는 코드를 내도 거부
        ResponseEntity<String> replay = call(HttpMethod.POST, "/api/v1/admin/auth/mfa", "{\"mfaToken\":\"" + token + "\",\"code\":\"" + good + "\"}", json(null));
        assertThat(replay.getStatusCode().value()).isEqualTo(401);
    }
}

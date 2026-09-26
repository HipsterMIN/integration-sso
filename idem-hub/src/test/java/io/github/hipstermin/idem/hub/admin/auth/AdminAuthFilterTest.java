package io.github.hipstermin.idem.hub.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** S7: 보호 경로·세션·CSRF 헤더·역할·비밀번호 변경 강제. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminAuthFilter")
class AdminAuthFilterTest {

    @Mock AdminAuthService authService;
    @Mock AdminAuditor auditor;
    AdminProperties props = new AdminProperties();
    AdminAuthFilter sut;

    final AdminSessionStore.AdminSession session = new AdminSessionStore.AdminSession("sid-1", "a1", "sys", AdminRole.SYSTEM_ADMIN, null,
            false, Instant.now(), Instant.now(), "127.0.0.1");
    final AdminSessionStore.AdminSession auditorSession = new AdminSessionStore.AdminSession("sid-2", "a2", "aud", AdminRole.AUDITOR, null,
            false, Instant.now(), Instant.now(), "127.0.0.1");
    final AdminSessionStore.AdminSession mustChange = new AdminSessionStore.AdminSession("sid-3", "a3", "new", AdminRole.POLICY_ADMIN, null,
            true, Instant.now(), Instant.now(), "127.0.0.1");

    @BeforeEach
    void setUp() {
        sut = new AdminAuthFilter(authService, new AdminAuthorization(), props, auditor, com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules().build());
        given(authService.resolve("sid-1")).willReturn(Optional.of(session));
        given(authService.resolve("sid-2")).willReturn(Optional.of(auditorSession));
        given(authService.resolve("sid-3")).willReturn(Optional.of(mustChange));
        given(authService.resolve(any())).willAnswer(inv -> {
            String sid = inv.getArgument(0);
            if ("sid-1".equals(sid)) return Optional.of(session);
            if ("sid-2".equals(sid)) return Optional.of(auditorSession);
            if ("sid-3".equals(sid)) return Optional.of(mustChange);
            return Optional.empty();
        });
    }

    private MockHttpServletResponse run(String method, String path, String sid, boolean csrf) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setRequestURI(path);
        if (sid != null) req.setCookies(new Cookie("idemAdminSid", sid));
        if (csrf) req.addHeader("X-Requested-With", "test");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        sut.doFilter(req, res, chain);
        if (chain.getRequest() != null) res.setHeader("X-Chain", "passed");
        return res;
    }

    @Test
    void unprotectedPathsPassThrough() throws Exception {
        assertThat(sut.shouldNotFilter(new MockHttpServletRequest("POST", "/api/v1/handoff/issue") {{ setRequestURI("/api/v1/handoff/issue"); }})).isTrue();
        assertThat(sut.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/auth/providers") {{ setRequestURI("/api/v1/auth/providers"); }})).isTrue();
        assertThat(sut.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/admin/services/A/profile") {{ setRequestURI("/api/v1/admin/services/A/profile"); }})).isFalse();
        assertThat(sut.shouldNotFilter(new MockHttpServletRequest("DELETE", "/api/v1/handoff/t1") {{ setRequestURI("/api/v1/handoff/t1"); }})).isFalse();
        assertThat(sut.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health") {{ setRequestURI("/actuator/health"); }})).isFalse();
    }

    @Test
    void healthAndLoginArePublic_butLoginNeedsCsrfHeader() throws Exception {
        assertThat(run("GET", "/actuator/health", null, false).getHeader("X-Chain")).isEqualTo("passed");
        assertThat(run("GET", "/actuator/prometheus", null, false).getHeader("X-Chain")).isEqualTo("passed");
        assertThat(run("POST", "/api/v1/admin/auth/login", null, true).getHeader("X-Chain")).isEqualTo("passed");
        MockHttpServletResponse noCsrf = run("POST", "/api/v1/admin/auth/login", null, false);
        assertThat(noCsrf.getStatus()).isEqualTo(403);
        assertThat(noCsrf.getContentAsString()).contains("E-IDO-131").contains("X-Requested-With");
    }

    @Test
    void noSession401_badSession401() throws Exception {
        MockHttpServletResponse r = run("GET", "/api/v1/admin/services/A/profile", null, false);
        assertThat(r.getStatus()).isEqualTo(401);
        assertThat(r.getContentAsString()).contains("E-IDO-130");
        assertThat(run("GET", "/api/v1/admin/services/A/profile", "nope", false).getStatus()).isEqualTo(401);
        assertThat(run("GET", "/actuator/features", null, false).getStatus()).isEqualTo(401);
    }

    @Test
    void validSessionPasses_andPrincipalAttributeSet() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/services/A/profile");
        req.setRequestURI("/api/v1/admin/services/A/profile");
        req.setCookies(new Cookie("idemAdminSid", "sid-1"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        sut.doFilter(req, res, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(req.getAttribute(AdminAuthFilter.ATTR_PRINCIPAL)).isInstanceOf(AdminPrincipal.class);
        assertThat(((AdminPrincipal) req.getAttribute(AdminAuthFilter.ATTR_PRINCIPAL)).username()).isEqualTo("sys");
    }

    @Test
    void mutatingWithoutCsrf403_evenWithSession() throws Exception {
        assertThat(run("PUT", "/api/v1/admin/services/A/profile", "sid-1", false).getStatus()).isEqualTo(403);
        assertThat(run("PUT", "/api/v1/admin/services/A/profile", "sid-1", true).getHeader("X-Chain")).isEqualTo("passed");
    }

    @Test
    void roleDenied403_andAudited() throws Exception {
        MockHttpServletResponse r = run("PUT", "/api/v1/admin/services/A/profile", "sid-2", true);
        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(r.getContentAsString()).contains("E-IDO-131");
        verify(auditor).failure(eq("ADMIN_ACCESS_DENIED"), eq("aud"), eq("API"), eq("PUT /api/v1/admin/services/A/profile"), anyString(), anyString());
        assertThat(run("GET", "/api/v1/admin/services/A/profile", "sid-2", false).getHeader("X-Chain")).isEqualTo("passed");
    }

    @Test
    void mustChangePasswordBlocksEverythingButAuth() throws Exception {
        MockHttpServletResponse r = run("GET", "/api/v1/admin/services/A/profile", "sid-3", false);
        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(r.getContentAsString()).contains("E-IDO-137");
        assertThat(run("POST", "/api/v1/admin/auth/password", "sid-3", true).getHeader("X-Chain")).isEqualTo("passed");
        assertThat(run("GET", "/api/v1/admin/auth/me", "sid-3", false).getHeader("X-Chain")).isEqualTo("passed");
    }

    @Test
    @DisplayName("1.0.1 (3차 점검 H1): 경로 파라미터·퍼센트 인코딩·점 세그먼트·중복 슬래시로 위장한 보호 경로는 세션이 있어도 403, 감사에 남는다")
    void disguisedProtectedPathsRejected() throws Exception {
        for (String raw : new String[] {"/api/v1/admin;x/admins", "/api/v1/admin;jsessionid=1/admins", "/api/v1/%61dmin/admins",
                "/actuator;x/flyway", "/api/v1/admin//admins", "/api/v1/admin/./admins", "/api/v1/auth/../admin/admins", "/api/v1/admin/admins/"}) {
            assertThat(sut.shouldNotFilter(new MockHttpServletRequest("GET", raw) {{ setRequestURI(raw); }})).as(raw).isFalse();
            MockHttpServletResponse anonymous = run("GET", raw, null, false);
            assertThat(anonymous.getStatus()).as(raw).isEqualTo(403);
            assertThat(anonymous.getContentAsString()).contains("E-IDO-131");
            assertThat(anonymous.getHeader("X-Chain")).isNull();
            MockHttpServletResponse withSession = run("GET", raw, "sid-1", false);
            assertThat(withSession.getStatus()).as(raw).isEqualTo(403);
            assertThat(withSession.getHeader("X-Chain")).isNull();
        }
        verify(auditor, org.mockito.Mockito.atLeast(8)).failure(eq("ADMIN_ACCESS_DENIED"), eq("anonymous"), eq("API"), anyString(), anyString(), eq("non-canonical path"));
        // 루트 탈출은 정규화가 안 된다 — 역시 거부
        assertThat(run("GET", "/../api/v1/admin/admins", "sid-1", false).getStatus()).isEqualTo(403);
        // 정규형은 종전과 같다
        assertThat(run("GET", "/api/v1/admin/admins", "sid-1", false).getHeader("X-Chain")).isEqualTo("passed");
    }
}

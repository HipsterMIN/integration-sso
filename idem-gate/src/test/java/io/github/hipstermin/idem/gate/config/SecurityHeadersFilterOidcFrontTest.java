package io.github.hipstermin.idem.gate.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * S6 실 Keycloak 끝-끝에서 발견: gate 의 CSP({@code form-action 'none'})가 프록시된 Keycloak 로그인 화면에 덧씌워져
 * 브라우저가 로그인 폼 제출을 거부했다. 프록시 경로는 필터를 아예 지나지 않아야 한다.
 */
@DisplayName("SecurityHeadersFilter — OIDC 프런트(/realms, /resources) 는 gate 보안 헤더를 덧씌우지 않는다")
class SecurityHeadersFilterOidcFrontTest {

    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    private MockHttpServletResponse run(String uri) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void proxiedKeycloakPaths_skipFilter() throws Exception {
        assertThat(run("/realms/onepass/protocol/openid-connect/auth").getHeader("Content-Security-Policy")).isNull();
        assertThat(run("/realms/onepass/login-actions/authenticate").getHeader("X-Frame-Options")).isNull();
        assertThat(run("/resources/abc/login/keycloak/css/login.css").getHeader("Content-Security-Policy")).isNull();
    }

    @Test
    void gateOwnPaths_stillGetHeaders() throws Exception {
        MockHttpServletResponse res = run("/api/v1/auth/x");
        assertThat(res.getHeader("Content-Security-Policy")).contains("default-src 'none'");
        assertThat(res.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(run("/.well-known/openid-configuration").getHeader("Content-Security-Policy")).isNotNull();
    }
}

package io.github.hipstermin.idem.hub.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("SecurityHeadersFilter — CSP form-action 은 코어 로그인 프런트 경로에서만 'self'")
class SecurityHeadersFilterTest {

    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    private String csp(String method, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getHeader("Content-Security-Policy");
    }

    @Test
    @DisplayName("/api/v1/handoff/login/** 는 form-action 'self' (동의 화면의 form POST), 나머지는 종전 'none'")
    void formActionScopedToLoginFront() throws Exception {
        assertThat(csp("POST", "/api/v1/handoff/login/consent")).contains("default-src 'none'").contains("form-action 'self'").doesNotContain("form-action 'none'");
        assertThat(csp("GET", "/api/v1/handoff/login")).contains("form-action 'self'");
        assertThat(csp("POST", "/api/v1/handoff/verify")).contains("form-action 'none'").doesNotContain("'self'; base-uri");
        assertThat(csp("GET", "/api/v1/handoff/logins")).as("/login 자체 또는 /login/ 아래만 — 비슷한 이름의 다른 경로는 아니다").contains("form-action 'none'");
    }

    @Test
    @DisplayName("allowFormActionOrigin — 동의 화면의 form-action 'self' 에 기관 콜백 출처를 더한다 (Chromium 은 form POST 뒤 302 대상에도 form-action 을 적용)")
    void allowFormActionOrigin() {
        String csp = "default-src 'none'; connect-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'";
        assertThat(SecurityHeadersFilter.allowFormActionOrigin(csp, "https://portal.example.go.kr/idem/callback?x=1"))
                .isEqualTo("default-src 'none'; connect-src 'self'; frame-ancestors 'none'; form-action 'self' https://portal.example.go.kr; base-uri 'none'");
        assertThat(SecurityHeadersFilter.allowFormActionOrigin(csp, "http://localhost:8080/cb")).contains("form-action 'self' http://localhost:8080;");
        assertThat(SecurityHeadersFilter.allowFormActionOrigin(csp, "HTTPS://Agency.Example.org/cb")).contains("form-action 'self' https://agency.example.org;");
        // 이미 있으면 그대로, 바꿀 것이 없으면 null
        String done = SecurityHeadersFilter.allowFormActionOrigin(csp, "https://a.example.org/cb");
        assertThat(SecurityHeadersFilter.allowFormActionOrigin(done, "https://a.example.org/other")).isEqualTo(done);
        assertThat(SecurityHeadersFilter.allowFormActionOrigin(null, "https://a.example.org/cb")).isNull();
        assertThat(SecurityHeadersFilter.allowFormActionOrigin(csp, null)).isNull();
        assertThat(SecurityHeadersFilter.allowFormActionOrigin("default-src 'none'; form-action 'none'", "https://a.example.org/cb")).isNull();
        assertThat(SecurityHeadersFilter.allowFormActionOrigin(csp, "javascript:alert(1)")).isNull();
        assertThat(SecurityHeadersFilter.allowFormActionOrigin(csp, "not a url")).isNull();
    }

    @Test
    void isLoginFrontRequest() {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/admin/services");
        r.setRequestURI("/api/v1/admin/services");
        assertThat(SecurityHeadersFilter.isLoginFrontRequest(r)).isFalse();
        r.setRequestURI("/api/v1/handoff/login/start");
        assertThat(SecurityHeadersFilter.isLoginFrontRequest(r)).isTrue();
        r.setRequestURI(null);
        assertThat(SecurityHeadersFilter.isLoginFrontRequest(r)).isFalse();
    }
}

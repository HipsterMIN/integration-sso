package io.github.hipstermin.idem.gate.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Q-Sign 보안 HTTP 응답 헤더 필터 (CSP 포함)
 *
 * <p>모든 HTTP 응답에 보안 헤더를 추가하여 주요 웹 취약점을 방어한다.
 * Q-Sign은 Keycloak Redirect URI를 수신하는 서버이므로
 * 일부 정책이 IdO와 다를 수 있다 (redirect 허용 등).
 *
 * <p><b>적용 헤더</b>:
 * <ul>
 *   <li>Content-Security-Policy: XSS 방어</li>
 *   <li>X-Content-Type-Options: MIME 스니핑 방지</li>
 *   <li>X-Frame-Options: Clickjacking 방어</li>
 *   <li>Strict-Transport-Security: HTTPS 강제</li>
 *   <li>Referrer-Policy: 외부 Referer 노출 제한</li>
 *   <li>Permissions-Policy: 불필요 기능 비활성화</li>
 *   <li>Cache-Control: API 응답 캐시 비활성화</li>
 * </ul>
 */
@Component
@Order(1)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Value("${qsign.csp.report-uri:}")
    private String cspReportUri;

    @Value("${qsign.csp.allowed-origin:}")
    private String allowedOrigin;

    /** Keycloak 베이스 URL (CSP connect-src에 추가) */
    @Value("${qsign.keycloak.base-url:}")
    private String keycloakBaseUrl;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        response.setHeader("Content-Security-Policy", buildCspDirective());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Strict-Transport-Security",
                "max-age=31536000; includeSubDomains");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy",
                "camera=(), microphone=(), geolocation=(), payment=(), usb=()");

        if (!isHealthCheckRequest(request)) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");
        }

        filterChain.doFilter(request, response);
    }

    private String buildCspDirective() {
        StringBuilder csp = new StringBuilder();
        csp.append("default-src 'none'");

        // connect-src: API 자기 자신 + 허용 Origin + Keycloak (Token Endpoint 호출)
        csp.append("; connect-src 'self'");
        if (allowedOrigin != null && !allowedOrigin.isBlank()) {
            csp.append(" ").append(allowedOrigin.trim());
        }
        if (keycloakBaseUrl != null && !keycloakBaseUrl.isBlank()) {
            csp.append(" ").append(keycloakBaseUrl.trim());
        }

        csp.append("; frame-ancestors 'none'");
        csp.append("; form-action 'none'");
        csp.append("; base-uri 'none'");

        if (cspReportUri != null && !cspReportUri.isBlank()) {
            csp.append("; report-uri ").append(cspReportUri.trim());
        }

        return csp.toString();
    }

    private boolean isHealthCheckRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && (
                path.equals("/actuator/health") ||
                path.equals("/health") ||
                path.startsWith("/actuator/")
        );
    }
}

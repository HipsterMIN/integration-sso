package io.github.hipstermin.idem.hub.admin.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.error.ErrorResponse;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 관리 API 보호 (S7) — 세션 쿠키 → {@link AdminPrincipal}, CSRF 헤더, 역할 매트릭스, 비밀번호 변경 강제.
 *
 * <p>보호 경로: {@code /api/v1/admin/**}(로그인·2단계 제외) · {@code DELETE /api/v1/handoff/*} · {@code /actuator/**}
 * (health·info·prometheus 제외). 내부 API({@code /api/internal/**})는 종전대로 내부 서명·키가 지킨다.
 *
 * <p>CSRF: 쿠키 세션이므로 상태를 바꾸는 요청은 {@code X-Requested-With} 헤더를 요구한다 — 브라우저는 교차 출처에서 이 헤더를
 * 사전 검사 없이 붙일 수 없고, 쿠키는 SameSite=Strict 다. 로그인에도 적용한다(로그인 CSRF).
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class AdminAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_PRINCIPAL = "idem.adminPrincipal";
    public static final String CSRF_HEADER = "X-Requested-With";

    private static final List<String> PROTECTED = List.of("/api/v1/admin/**", "/actuator/**");
    private static final List<String> PUBLIC = List.of(
            "/api/v1/admin/auth/login", "/api/v1/admin/auth/mfa",
            "/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus");
    private static final List<String> PASSWORD_CHANGE_ALLOWED = List.of("/api/v1/admin/auth/**");

    private final AdminAuthService authService;
    private final AdminAuthorization authorization;
    private final AdminProperties props;
    private final AdminAuditor auditor;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher matcher = new AntPathMatcher();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("DELETE".equals(request.getMethod()) && matcher.match("/api/v1/handoff/*", path)) return false;
        return PROTECTED.stream().noneMatch(p -> matcher.match(p, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        boolean mutating = !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));

        if (mutating && request.getHeader(CSRF_HEADER) == null) {
            // 세션이 있는 요청의 CSRF 거부도 감사에 남긴다 (브라우저 강제 요청 시도의 흔적)
            String actor = authService.resolve(sessionId(request)).map(AdminSessionStore.AdminSession::username).orElse("anonymous");
            auditor.failure("ADMIN_ACCESS_DENIED", actor, "API", method + " " + path, clientIp(request), "missing " + CSRF_HEADER);
            reject(response, PlatformErrorCode.ADMIN_FORBIDDEN, "X-Requested-With 헤더가 필요합니다");
            return;
        }
        if (PUBLIC.stream().anyMatch(p -> matcher.match(p, path))) {
            chain.doFilter(request, response);
            return;
        }
        Optional<AdminSessionStore.AdminSession> session = authService.resolve(sessionId(request));
        if (session.isEmpty()) {
            reject(response, PlatformErrorCode.ADMIN_UNAUTHENTICATED, null);
            return;
        }
        AdminPrincipal principal = session.get().principal();
        if (principal.mustChangePassword() && PASSWORD_CHANGE_ALLOWED.stream().noneMatch(p -> matcher.match(p, path))) {
            reject(response, PlatformErrorCode.ADMIN_PASSWORD_CHANGE_REQUIRED, null);
            return;
        }
        if (!authorization.allowed(principal, method, path)) {
            auditor.failure("ADMIN_ACCESS_DENIED", principal.username(), "API", method + " " + path, clientIp(request), "role=" + principal.role());
            reject(response, PlatformErrorCode.ADMIN_FORBIDDEN, null);
            return;
        }
        request.setAttribute(ATTR_PRINCIPAL, principal);
        chain.doFilter(request, response);
    }

    private String sessionId(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) if (props.getCookie().getName().equals(c.getName())) return c.getValue();
        return null;
    }

    public static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private void reject(HttpServletResponse response, PlatformErrorCode code, String detail) throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ErrorResponse body = ErrorResponse.of(code, CorrelationIdHolder.get());
        String json = objectMapper.writeValueAsString(body);
        if (detail != null) {
            json = json.replaceFirst("\\}\\s*$", ",\"detail\":" + objectMapper.writeValueAsString(detail) + "}");
        }
        response.getWriter().write(json);
    }
}

package io.github.hipstermin.idem.hub.admin.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 인증 API (S7).
 * <pre>
 *   POST /api/v1/admin/auth/login     {username,password} → {status: OK|MFA_REQUIRED|MFA_ENROLL_REQUIRED, mfaToken?, secret?, otpauthUri?, admin?}
 *   POST /api/v1/admin/auth/mfa       {mfaToken, code}     → {admin}  + 세션 쿠키
 *   POST /api/v1/admin/auth/logout
 *   GET  /api/v1/admin/auth/me
 *   POST /api/v1/admin/auth/password  {currentPassword, newPassword}
 * </pre>
 * 모든 쓰기 요청에 {@code X-Requested-With} 헤더 필수 ({@link AdminAuthFilter}).
 */
@RestController
@Validated
@RequestMapping("/api/v1/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService authService;
    private final AdminProperties props;

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record MfaRequest(@NotBlank String mfaToken, @NotBlank String code) {}
    public record PasswordChangeRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest req, HttpServletRequest http) {
        AdminAuthService.LoginResult r = authService.login(req.username(), req.password(), AdminAuthFilter.clientIp(http));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", r.status().name());
        if (r.mfaToken() != null) body.put("mfaToken", r.mfaToken());
        if (r.secret() != null) { body.put("secret", r.secret()); body.put("otpauthUri", r.otpauthUri()); }
        if (r.session() != null) {
            body.put("admin", view(r.session().principal()));
            return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(r.session().sessionId()).toString()).body(body);
        }
        return ResponseEntity.ok(body);
    }

    @PostMapping("/mfa")
    public ResponseEntity<Map<String, Object>> mfa(@RequestBody MfaRequest req, HttpServletRequest http) {
        AdminSessionStore.AdminSession s = authService.verifyMfa(req.mfaToken(), req.code(), AdminAuthFilter.clientIp(http));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(s.sessionId()).toString())
                .body(Map.of("status", "OK", "admin", view(s.principal())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(AdminPrincipal admin, HttpServletRequest http) {
        authService.logout(admin, AdminAuthFilter.clientIp(http));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expiredCookie().toString()).build();
    }

    @GetMapping("/me")
    public Map<String, Object> me(AdminPrincipal admin) {
        return view(admin);
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@RequestBody PasswordChangeRequest req, AdminPrincipal admin, HttpServletRequest http) {
        authService.changePassword(admin, req.currentPassword(), req.newPassword(), AdminAuthFilter.clientIp(http));
        return ResponseEntity.noContent().build();
    }

    private static Map<String, Object> view(AdminPrincipal p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("adminId", p.adminId());
        m.put("username", p.username());
        m.put("role", p.role().name());
        m.put("tenantCode", p.tenantCode());
        m.put("mustChangePassword", p.mustChangePassword());
        return m;
    }

    private ResponseCookie cookie(String sid) {
        return ResponseCookie.from(props.getCookie().getName(), sid)
                .httpOnly(true).secure(props.getCookie().isSecure()).sameSite(props.getCookie().getSameSite()).path("/").build();
    }

    private ResponseCookie expiredCookie() {
        return ResponseCookie.from(props.getCookie().getName(), "")
                .httpOnly(true).secure(props.getCookie().isSecure()).sameSite(props.getCookie().getSameSite()).path("/").maxAge(0).build();
    }
}

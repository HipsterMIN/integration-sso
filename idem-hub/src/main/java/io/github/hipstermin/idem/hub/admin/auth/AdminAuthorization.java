package io.github.hipstermin.idem.hub.admin.auth;

import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

/**
 * 관리 API 인가 매트릭스 (docs/admin-auth.md 표와 같아야 한다).
 * <pre>
 *   경로                              GET      쓰기
 *   /api/v1/admin/auth/**             전 역할   전 역할 (자기 로그아웃·비밀번호)
 *   /api/v1/admin/admins/**           SYSTEM   SYSTEM (글로벌 범위만)
 *   /api/v1/admin/tenants/**          전 역할   SYSTEM (글로벌 범위만)
 *   /api/v1/admin/audit/**            전 역할   —
 *   /api/v1/admin/** (그 외)           전 역할   SYSTEM·POLICY
 *   DELETE /api/v1/handoff/{id}       —        SYSTEM·POLICY
 *   /actuator/** (health·info·prometheus 제외)  SYSTEM   SYSTEM
 * </pre>
 */
@Component
public class AdminAuthorization {

    private final AntPathMatcher matcher = new AntPathMatcher();

    public boolean allowed(AdminPrincipal p, String method, String path) {
        boolean read = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
        if (matcher.match("/api/v1/admin/auth/**", path)) return true;
        if (matcher.match("/api/v1/admin/admins/**", path)) {
            return p.role() == AdminRole.SYSTEM_ADMIN && p.isGlobal();
        }
        if (matcher.match("/api/v1/admin/tenants/**", path)) {
            return read || (p.role() == AdminRole.SYSTEM_ADMIN && p.isGlobal());
        }
        if (matcher.match("/api/v1/admin/audit/**", path)) return read;
        if (matcher.match("/actuator/**", path)) return p.role() == AdminRole.SYSTEM_ADMIN;
        if (read) return true;
        return p.role() == AdminRole.SYSTEM_ADMIN || p.role() == AdminRole.POLICY_ADMIN;
    }
}

package io.github.hipstermin.idem.hub.admin.auth;

/**
 * 인증된 관리자 (요청 속성 {@link AdminAuthFilter#ATTR_PRINCIPAL}, 컨트롤러 인자로 주입).
 *
 * @param tenantCode null 이면 전체(글로벌) 범위, 아니면 그 Tenant 의 Service 만 본다
 * @param mustChangePassword true 면 비밀번호 변경 전까지 {@code /api/v1/admin/auth/**} 밖은 거부(E-IDO-137)
 */
public record AdminPrincipal(String adminId, String username, AdminRole role, String tenantCode,
                             String sessionId, boolean mustChangePassword) {

    public boolean isGlobal() { return tenantCode == null || tenantCode.isBlank(); }
}

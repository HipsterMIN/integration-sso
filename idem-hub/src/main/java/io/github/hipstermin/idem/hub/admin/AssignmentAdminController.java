package io.github.hipstermin.idem.hub.admin;

import io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal;
import io.github.hipstermin.idem.hub.infrastructure.QAuthzClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.1.1 G1-3 — 할당 관리 API ({@code /api/v1/admin/services/{code}/assignments·roles}). 인가는 "그 외" 규칙(GET 전 역할, 쓰기 SYSTEM·POLICY)과
 * 테넌트 범위. 상태는 idem-authz 가 가진다 — authz 의 거부는 {@code E-IDO-127}(없음)·{@code 128}(중복·부여 불가)·{@code 129}(그 밖), 장애는 {@code E-IDO-117}.
 * <ul>
 *   <li>{@code GET  /assignments?page&size} — 할당 목록(items·page·size·total·hasNext)</li>
 *   <li>{@code POST /assignments} {qimUserId, expiresAt?, reason?} — 직접 할당(source=CONSOLE), 감사 {@code ADMIN/ASSIGNMENT_GRANTED}</li>
 *   <li>{@code DELETE /assignments/{qimUserId}?reason} — 할당 해제, 감사 {@code ASSIGNMENT_REVOKED}</li>
 *   <li>{@code GET /roles} · {@code POST /roles} {roleCode, name, description?} — 역할(그룹) 카탈로그, 감사 {@code ROLE_CREATED}</li>
 *   <li>{@code GET /assignments/{qimUserId}/roles} · {@code POST …/roles} {roleCode, expiresAt?, reason?} · {@code DELETE …/roles/{roleCode}?reason} — 역할 부여·회수, 감사 {@code ROLE_GRANTED·ROLE_REVOKED}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/services/{code}")
@RequiredArgsConstructor
public class AssignmentAdminController {

    private final AssignmentAdminService service;

    public record AssignBody(String qimUserId, Instant expiresAt, String reason) {}
    public record RoleBody(String roleCode, String name, String description) {}
    public record GrantBody(String roleCode, Instant expiresAt, String reason) {}

    @GetMapping("/assignments")
    public AssignmentAdminService.Page list(@PathVariable String code, @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size, AdminPrincipal admin,
                                            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return service.list(code, page, size, admin, cid(correlationId));
    }

    @PostMapping(value = "/assignments", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public QAuthzClient.AssignmentRecord assign(@PathVariable String code, @RequestBody AssignBody body, AdminPrincipal admin,
                                                @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return service.assign(code, body.qimUserId(), body.expiresAt(), body.reason(), admin, cid(correlationId));
    }

    @DeleteMapping("/assignments/{qimUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unassign(@PathVariable String code, @PathVariable String qimUserId, @RequestParam(required = false) String reason, AdminPrincipal admin,
                         @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        service.unassign(code, qimUserId, reason, admin, cid(correlationId));
    }

    @GetMapping("/roles")
    public List<QAuthzClient.RoleRecord> roles(@PathVariable String code, AdminPrincipal admin,
                                               @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return service.roles(code, admin, cid(correlationId));
    }

    @PostMapping(value = "/roles", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public QAuthzClient.RoleRecord createRole(@PathVariable String code, @RequestBody RoleBody body, AdminPrincipal admin,
                                              @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return service.createRole(code, body.roleCode(), body.name(), body.description(), admin, cid(correlationId));
    }

    @GetMapping("/assignments/{qimUserId}/roles")
    public List<QAuthzClient.UserRoleRecord> userRoles(@PathVariable String code, @PathVariable String qimUserId, AdminPrincipal admin,
                                                       @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return service.userRoles(code, qimUserId, admin, cid(correlationId));
    }

    @PostMapping(value = "/assignments/{qimUserId}/roles", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public QAuthzClient.UserRoleRecord grantRole(@PathVariable String code, @PathVariable String qimUserId, @RequestBody GrantBody body, AdminPrincipal admin,
                                                 @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return service.grantRole(code, qimUserId, body.roleCode(), body.expiresAt(), body.reason(), admin, cid(correlationId));
    }

    @DeleteMapping("/assignments/{qimUserId}/roles/{roleCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeRole(@PathVariable String code, @PathVariable String qimUserId, @PathVariable String roleCode,
                           @RequestParam(required = false) String reason, AdminPrincipal admin,
                           @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        service.revokeRole(code, qimUserId, roleCode, reason, admin, cid(correlationId));
    }

    private static String cid(String correlationId) {
        return correlationId != null && !correlationId.isBlank() ? correlationId : UUID.randomUUID().toString();
    }
}

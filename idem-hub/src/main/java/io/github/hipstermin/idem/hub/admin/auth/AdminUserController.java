package io.github.hipstermin.idem.hub.admin.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 계정 관리 API — SYSTEM_ADMIN(글로벌) 전용 ({@link AdminAuthorization}). */
@RestController
@RequestMapping("/api/v1/admin/admins")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService service;

    public record CreateRequest(String username, String displayName, AdminRole role, String tenantCode) {}
    public record UpdateRequest(AdminRole role, AdminStatus status, String tenantCode, String displayName) {}

    @GetMapping
    public List<AdminUserService.AdminView> list() { return service.list(); }

    @GetMapping("/{adminId}")
    public AdminUserService.AdminView get(@PathVariable String adminId) { return service.get(adminId); }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest req, AdminPrincipal actor, HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(req.username(), req.displayName(), req.role(), req.tenantCode(), actor, AdminAuthFilter.clientIp(http)));
    }

    @PutMapping("/{adminId}")
    public AdminUserService.AdminView update(@PathVariable String adminId, @RequestBody UpdateRequest req, AdminPrincipal actor, HttpServletRequest http) {
        return service.update(adminId, req.role(), req.status(), req.tenantCode(), req.displayName(), actor, AdminAuthFilter.clientIp(http));
    }

    @PostMapping("/{adminId}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable String adminId, AdminPrincipal actor, HttpServletRequest http) {
        return service.resetPassword(adminId, actor, AdminAuthFilter.clientIp(http));
    }

    @PostMapping("/{adminId}/unlock")
    public AdminUserService.AdminView unlock(@PathVariable String adminId, AdminPrincipal actor, HttpServletRequest http) {
        return service.unlock(adminId, actor, AdminAuthFilter.clientIp(http));
    }

    @PostMapping("/{adminId}/reset-mfa")
    public AdminUserService.AdminView resetMfa(@PathVariable String adminId, AdminPrincipal actor, HttpServletRequest http) {
        return service.resetMfa(adminId, actor, AdminAuthFilter.clientIp(http));
    }
}

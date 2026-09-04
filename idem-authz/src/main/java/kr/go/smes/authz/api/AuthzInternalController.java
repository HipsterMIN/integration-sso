package kr.go.smes.authz.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.go.smes.authz.api.dto.CreateRoleRequest;
import kr.go.smes.authz.api.dto.EffectiveRolesResponse;
import kr.go.smes.authz.api.dto.GrantRoleRequest;
import kr.go.smes.authz.api.dto.RoleResponse;
import kr.go.smes.authz.api.dto.UserRoleResponse;
import kr.go.smes.authz.application.AuthzService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 내부 전용 인가 API ({@code /api/v1/internal/authz/**}).
 *
 * <p>ido(플랫폼 PEP/Claim Issuer)와 onepass-admin(PAP)만 호출. 모든 요청은
 * {@code X-Internal-Api-Key} 검증(InternalApiKeyInterceptor)을 통과해야 한다.
 *
 * <h3>엔드포인트</h3>
 * <ul>
 *   <li>POST   /roles                              — 역할 카탈로그 생성</li>
 *   <li>GET    /roles?agencyCode=                   — 기관 역할 목록</li>
 *   <li>POST   /grants                              — 사용자 역할 부여(멱등)</li>
 *   <li>DELETE /grants                              — 사용자 역할 회수</li>
 *   <li>GET    /users/{qimUserId}/roles?agencyCode= — 사용자 부여 목록</li>
 *   <li>GET    /users/{qimUserId}/effective-roles?agencyCode= — 토큰 클레임용 유효 역할</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/authz")
@RequiredArgsConstructor
public class AuthzInternalController {

    private final AuthzService authzService;

    // ── 역할 카탈로그 ──────────────────────────────────────────────────────────

    @PostMapping("/roles")
    public ResponseEntity<RoleResponse> createRole(
            @Valid @RequestBody CreateRoleRequest req,
            @RequestHeader(value = "X-Actor", required = false) String actor,
            HttpServletRequest request) {
        RoleResponse res = RoleResponse.from(
                authzService.createRole(req, resolveActor(actor), clientIp(request)));
        return ResponseEntity.status(HttpStatus.CREATED).body(res);
    }

    @GetMapping("/roles")
    public ResponseEntity<List<RoleResponse>> listRoles(@RequestParam("agencyCode") String agencyCode) {
        return ResponseEntity.ok(
                authzService.listRoles(agencyCode).stream().map(RoleResponse::from).toList());
    }

    // ── 부여/회수 ──────────────────────────────────────────────────────────────

    @PostMapping("/grants")
    public ResponseEntity<UserRoleResponse> grant(
            @Valid @RequestBody GrantRoleRequest req,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            HttpServletRequest request) {
        UserRoleResponse res = UserRoleResponse.from(
                authzService.grantRole(req, clientIp(request), correlationId));
        return ResponseEntity.status(HttpStatus.CREATED).body(res);
    }

    @DeleteMapping("/grants")
    public ResponseEntity<Void> revoke(
            @RequestParam("qimUserId") String qimUserId,
            @RequestParam("agencyCode") String agencyCode,
            @RequestParam("roleCode") String roleCode,
            @RequestParam(value = "revokedBy", required = false) String revokedBy,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            HttpServletRequest request) {
        authzService.revokeRole(qimUserId, agencyCode, roleCode,
                resolveActor(revokedBy), clientIp(request), reason, correlationId);
        return ResponseEntity.noContent().build();
    }

    // ── 조회 ────────────────────────────────────────────────────────────────

    @GetMapping("/users/{qimUserId}/roles")
    public ResponseEntity<List<UserRoleResponse>> listUserRoles(
            @PathVariable String qimUserId,
            @RequestParam("agencyCode") String agencyCode) {
        return ResponseEntity.ok(
                authzService.listUserRoles(qimUserId, agencyCode).stream()
                        .map(UserRoleResponse::from).toList());
    }

    /** 토큰 {@code roles[]} 클레임의 원천 — ido가 Handoff/CAST 발급 시 호출. */
    @GetMapping("/users/{qimUserId}/effective-roles")
    public ResponseEntity<EffectiveRolesResponse> effectiveRoles(
            @PathVariable String qimUserId,
            @RequestParam("agencyCode") String agencyCode) {
        List<String> roles = authzService.effectiveRoleCodes(qimUserId, agencyCode);
        return ResponseEntity.ok(new EffectiveRolesResponse(qimUserId, agencyCode, roles));
    }

    // ── private ───────────────────────────────────────────────────────────────

    private String resolveActor(String actor) {
        return (actor == null || actor.isBlank()) ? "SYSTEM" : actor;
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}

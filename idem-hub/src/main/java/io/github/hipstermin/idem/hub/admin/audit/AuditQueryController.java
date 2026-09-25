package io.github.hipstermin.idem.hub.admin.audit;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal;
import io.github.hipstermin.idem.hub.admin.auth.AdminTenantScope;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/admin/audit?from&to&category&action&actorId&agencyCode&outcome&correlationId&page&size} — 전 역할 읽기.
 * Tenant 범위 관리자는 {@code agencyCode} 를 반드시 주고, 그 Service 가 자기 Tenant 여야 한다.
 */
@RestController
@RequestMapping("/api/v1/admin/audit")
@RequiredArgsConstructor
public class AuditQueryController {

    private final AuditQueryService service;
    private final AdminTenantScope scope;

    @GetMapping
    public AuditQueryService.Page search(@RequestParam(required = false) Instant from,
                                         @RequestParam(required = false) Instant to,
                                         @RequestParam(required = false) String category,
                                         @RequestParam(required = false) String action,
                                         @RequestParam(required = false) String actorId,
                                         @RequestParam(required = false) String agencyCode,
                                         @RequestParam(required = false) String outcome,
                                         @RequestParam(required = false) String correlationId,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "50") int size,
                                         AdminPrincipal admin) {
        if (!admin.isGlobal()) {
            if (agencyCode == null || agencyCode.isBlank()) {
                throw new PlatformException(PlatformErrorCode.ADMIN_FORBIDDEN, null, "Tenant 범위 관리자는 agencyCode 를 지정해야 합니다");
            }
            if (!scope.inScope(admin, agencyCode)) {
                throw new PlatformException(PlatformErrorCode.ADMIN_FORBIDDEN, null, "다른 Tenant 의 Service: " + agencyCode);
            }
        }
        return service.search(new AuditQueryService.Query(from, to, category, action, actorId, agencyCode, outcome, correlationId, page, size));
    }
}

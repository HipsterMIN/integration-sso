package io.github.hipstermin.idem.hub.scim;

import io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal;
import io.github.hipstermin.idem.hub.admin.auth.AdminTenantScope;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.1 SCIM 아웃바운드 관리 API (관리자 세션, 테넌트 범위).
 * <ul>
 *   <li>{@code GET  /api/v1/admin/services/{code}/scim/status} — 아웃박스 상태 집계·마지막 오류</li>
 *   <li>{@code POST /api/v1/admin/services/{code}/scim/sync}   — 전체 동기화 적재(기존 사용자 도입·재조정)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/services/{serviceCode}/scim")
@RequiredArgsConstructor
public class ScimAdminController {

    private final ScimSyncService syncService;
    private final AdminTenantScope tenantScope;
    private final AuditLogPublisher auditLogPublisher;

    @GetMapping("/status")
    public ResponseEntity<ScimSyncService.Status> status(@PathVariable String serviceCode, AdminPrincipal admin) {
        tenantScope.checkService(admin, serviceCode);
        return ResponseEntity.ok(syncService.status(serviceCode));
    }

    @PostMapping("/sync")
    public ResponseEntity<ScimSyncService.SyncResult> sync(@PathVariable String serviceCode, AdminPrincipal admin,
                                                            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        tenantScope.checkService(admin, serviceCode);
        String cid = correlationId != null ? correlationId : UUID.randomUUID().toString();
        ScimSyncService.SyncResult result = syncService.fullSync(serviceCode, cid);
        auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                .eventCategory(ScimOutboxRelay.AUDIT_CATEGORY).eventAction("SCIM_SYNC_REQUESTED")
                .actorType("ADMIN").actorId(admin.username()).resourceType("SERVICE").resourceId(serviceCode)
                .agencyCode(serviceCode).correlationId(cid)
                .metadata(Map.of("syncId", result.syncId(), "users", result.users(), "enqueued", result.enqueued(), "skipped", result.skipped()))
                .build());
        log.info("[ScimAdmin] 전체 동기화 요청: service={} admin={} result={}", serviceCode, admin.username(), result);
        return ResponseEntity.ok(result);
    }
}

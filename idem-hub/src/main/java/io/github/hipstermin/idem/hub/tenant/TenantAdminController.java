package io.github.hipstermin.idem.hub.tenant;

import io.github.hipstermin.idem.common.error.ErrorResponse;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.event.AuditLogEvent;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant(Realm) Admin API (S4b).
 *
 * <pre>
 * GET /api/v1/admin/tenants          목록
 * GET /api/v1/admin/tenants/{code}   단건
 * PUT /api/v1/admin/tenants/{code}   생성/갱신 { name, status }
 * </pre>
 * Service(기관) 프로파일은 {@code /api/v1/admin/services/{code}/profile} — {@code service.tenant} 로 소속을 가리킨다.
 * 관리자 인증은 S7 에서 {@code X-Admin-Id} 를 인증된 신원으로 대체한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/tenants")
@RequiredArgsConstructor
public class TenantAdminController {

    private final TenantJpaRepository tenantRepository;
    private final AuditLogPublisher   auditLogPublisher;

    public record TenantView(String code, String name, String status, Instant createdAt, Instant updatedAt) {
        static TenantView of(TenantJpaEntity e) {
            return new TenantView(e.getTenantCode(), e.getName(), e.getStatus(), e.getCreatedAt(), e.getUpdatedAt());
        }
    }

    public record TenantUpsertRequest(
            @NotBlank @Size(max = 200) String name,
            @Pattern(regexp = "ACTIVE|INACTIVE") String status) {}

    @GetMapping
    public List<TenantView> list() {
        return tenantRepository.findAll().stream().map(TenantView::of).toList();
    }

    @GetMapping("/{code}")
    public ResponseEntity<TenantView> get(@PathVariable String code) {
        return tenantRepository.findById(code).map(TenantView::of).map(ResponseEntity::ok)
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.IDO_INVALID_TENANT_PROFILE, null,
                        "등록되지 않은 Tenant: " + code));
    }

    @PutMapping("/{code}")
    @Transactional
    public ResponseEntity<TenantView> put(
            @PathVariable @Pattern(regexp = "^[A-Za-z0-9_\\-]{1,50}$") String code,
            @RequestHeader(value = "X-Admin-Id", defaultValue = "SYSTEM") String adminId,
            @Valid @RequestBody TenantUpsertRequest req) {
        TenantJpaEntity entity = tenantRepository.findById(code)
                .orElseGet(() -> TenantJpaEntity.builder().tenantCode(code).build());
        boolean created = entity.getCreatedAt() == null;
        entity.setName(req.name());
        if (req.status() != null) entity.setStatus(req.status());
        TenantJpaEntity saved = tenantRepository.save(entity);
        audit(created ? "TENANT_CREATED" : "TENANT_UPDATED", code, adminId);
        log.info("[TenantAdmin] {}: tenant={} adminId={}", created ? "신규" : "갱신", code, adminId);
        return ResponseEntity.ok(TenantView.of(saved));
    }

    private void audit(String action, String code, String adminId) {
        try {
            auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                    .eventCategory(AuditLogEvent.CATEGORY_SYSTEM)
                    .eventAction(action)
                    .actorType(AuditLogEvent.ACTOR_SYSTEM)
                    .actorId(adminId)
                    .resourceType("TENANT")
                    .resourceId(code)
                    .outcome(AuditLogEvent.OUTCOME_SUCCESS)
                    .build());
        } catch (RuntimeException e) {
            log.warn("[TenantAdmin] 감사 로그 실패 (비치명적): action={} err={}", action, e.getMessage());
        }
    }

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ErrorResponse> handlePlatformException(PlatformException ex) {
        return ResponseEntity.status(ex.getErrorCode().getHttpStatus())
                .body(ErrorResponse.builder()
                        .code(ex.getErrorCode().getCode())
                        .message(ex.getMessage())
                        .correlationId(ex.getCorrelationId())
                        .timestamp(Instant.now())
                        .build());
    }
}

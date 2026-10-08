package io.github.hipstermin.idem.hub.consent;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal;
import io.github.hipstermin.idem.hub.admin.auth.AdminTenantScope;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * 1.1 동의 카탈로그 관리 API (플랜 §5 #8) — 항목(문구·버전·필수 여부)은 registry 가 가진다. hub 는 범위를 지키고 감사를 남긴다.
 * <ul>
 *   <li>{@code GET  /api/v1/admin/services/{code}/consents?includeInactive&catalog} — 서비스 전용 버전 목록(catalog=true 면 로그인 화면이 보는 합: 플랫폼 공통 + 전용, ACTIVE)</li>
 *   <li>{@code POST /api/v1/admin/services/{code}/consents} {consentType, versionTag, title, contentUrl, required, effectiveAt} — 새 버전 발행(같은 유형의 ACTIVE 는 SUPERSEDED), 201</li>
 *   <li>{@code POST /api/v1/admin/services/{code}/consents/{versionId}/retire} — 버전 종료(그 서비스 범위의 버전만)</li>
 *   <li>{@code GET/POST /api/v1/admin/consents[/{versionId}/retire]} — 플랫폼 공통 항목(service_code 없음), <b>전역 관리자만</b></li>
 * </ul>
 * 인가는 "그 외" 규칙(GET 전 역할, 쓰기 SYSTEM·POLICY)과 테넌트 범위({@link AdminTenantScope#checkService}). 감사 {@code ADMIN/CONSENT_VERSION_PUBLISHED·RETIRED}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class ConsentAdminController {

    public static final String AUDIT_PUBLISHED = "CONSENT_VERSION_PUBLISHED";
    public static final String AUDIT_RETIRED = "CONSENT_VERSION_RETIRED";

    private final ConsentRegistryClient client;
    private final AdminTenantScope tenantScope;
    private final AuditLogPublisher auditLogPublisher;

    public record PublishBody(String consentType, String versionTag, String title, String contentUrl, Boolean required, Instant effectiveAt) {}

    // ── 서비스 전용 항목 ─────────────────────────────────────────────────────

    @GetMapping("/services/{code}/consents")
    public List<ConsentItem> listService(@PathVariable String code,
                                         @RequestParam(defaultValue = "false") boolean includeInactive,
                                         @RequestParam(defaultValue = "false") boolean catalog,
                                         AdminPrincipal admin,
                                         @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        tenantScope.checkService(admin, code);
        String cid = cid(correlationId);
        return catalog ? client.catalog(code, cid) : client.listVersions(code, includeInactive, cid);
    }

    @PostMapping(value = "/services/{code}/consents", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ConsentItem publishService(@PathVariable String code, @RequestBody PublishBody body, AdminPrincipal admin,
                                      @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        tenantScope.checkService(admin, code);
        return publish(code, body, admin, cid(correlationId));
    }

    @PostMapping("/services/{code}/consents/{versionId}/retire")
    public ConsentItem retireService(@PathVariable String code, @PathVariable String versionId, AdminPrincipal admin,
                                     @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        tenantScope.checkService(admin, code);
        return retire(code, versionId, admin, cid(correlationId));
    }

    // ── 플랫폼 공통 항목 (전역 관리자) ──────────────────────────────────────────

    @GetMapping("/consents")
    public List<ConsentItem> listPlatform(@RequestParam(defaultValue = "false") boolean includeInactive, AdminPrincipal admin,
                                          @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        requireGlobal(admin);
        return client.listVersions(null, includeInactive, cid(correlationId));
    }

    @PostMapping(value = "/consents", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ConsentItem publishPlatform(@RequestBody PublishBody body, AdminPrincipal admin,
                                       @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        requireGlobal(admin);
        return publish(null, body, admin, cid(correlationId));
    }

    @PostMapping("/consents/{versionId}/retire")
    public ConsentItem retirePlatform(@PathVariable String versionId, AdminPrincipal admin,
                                      @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        requireGlobal(admin);
        return retire(null, versionId, admin, cid(correlationId));
    }

    // ── 내부 ────────────────────────────────────────────────────────────────

    private ConsentItem publish(String serviceCode, PublishBody body, AdminPrincipal admin, String cid) {
        if (body == null || body.consentType() == null || body.consentType().isBlank()) {
            throw new PlatformException(PlatformErrorCode.IM_CONSENT_VERSION_INVALID, cid, "consentType 은 필수입니다");
        }
        String type = body.consentType().trim().toUpperCase(Locale.ROOT);
        ConsentItem v = client.publish(serviceCode, type, body.versionTag(), body.title(), body.contentUrl(), body.required(), body.effectiveAt(), cid);
        audit(AUDIT_PUBLISHED, v, serviceCode, admin, cid);
        log.info("[ConsentAdmin] 발행: scope={} type={} version={} by={}", scope(serviceCode), type, v.versionId(), admin.username());
        return v;
    }

    private ConsentItem retire(String serviceCode, String versionId, AdminPrincipal admin, String cid) {
        // 범위 확인 — 다른 서비스나 플랫폼 공통 버전을 이 경로로 끝낼 수 없다
        boolean owned = client.listVersions(serviceCode, true, cid).stream().anyMatch(i -> versionId.equals(i.versionId()));
        if (!owned) {
            throw new PlatformException(PlatformErrorCode.IM_CONSENT_NOT_FOUND, cid, scope(serviceCode) + " 범위의 동의 버전이 아닙니다: " + versionId);
        }
        ConsentItem v = client.retire(versionId, cid);
        audit(AUDIT_RETIRED, v, serviceCode, admin, cid);
        log.info("[ConsentAdmin] 종료: scope={} version={} by={}", scope(serviceCode), versionId, admin.username());
        return v;
    }

    private void audit(String action, ConsentItem v, String serviceCode, AdminPrincipal admin, String cid) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("scope", serviceCode == null ? "PLATFORM" : "SERVICE");
        meta.put("consentType", v.consentType() != null ? v.consentType() : "");
        meta.put("versionTag", v.versionTag() != null ? v.versionTag() : "");
        meta.put("required", v.required());
        auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                .eventCategory("ADMIN").eventAction(action).actorType("ADMIN").actorId(admin.username())
                .resourceType("CONSENT_VERSION").resourceId(v.versionId()).agencyCode(serviceCode).correlationId(cid).outcome("SUCCESS")
                .metadata(meta)
                .build());
    }

    private static void requireGlobal(AdminPrincipal admin) {
        if (!admin.isGlobal()) {
            throw new PlatformException(PlatformErrorCode.ADMIN_FORBIDDEN, null, "플랫폼 공통 동의 항목은 전역 관리자만 관리합니다");
        }
    }

    private static String scope(String serviceCode) {
        return serviceCode == null ? "플랫폼 공통" : "서비스 " + serviceCode;
    }

    private static String cid(String correlationId) {
        return correlationId != null && !correlationId.isBlank() ? correlationId : UUID.randomUUID().toString();
    }
}

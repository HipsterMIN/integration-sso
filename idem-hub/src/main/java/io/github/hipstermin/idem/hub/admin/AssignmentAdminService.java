package io.github.hipstermin.idem.hub.admin;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal;
import io.github.hipstermin.idem.hub.admin.auth.AdminTenantScope;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.infrastructure.QAuthzClient;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 1.1.1 G1-3 (플랜 §2.3) — 관리 콘솔의 할당 관리: Service 의 사용자 할당과 역할(그룹) 부여를 hub 관리 API 로 노출한다.
 * 실제 상태는 idem-authz 가 가진다(여기서는 Service 존재·테넌트 범위를 보고 감사만 남긴다). authz 는 변경마다 아웃박스 이벤트를 내므로
 * 기관 웹훅 {@code ASSIGNMENT_CHANGED}·SCIM 도 그대로 따라간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssignmentAdminService {

    public static final int MAX_SIZE = 200;
    static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.:-]{1,100}");

    private final QAuthzClient authz;
    private final AdminTenantScope tenantScope;
    private final AuditLogPublisher auditLogPublisher;
    private final ServiceProfileService serviceProfileService;

    public record Page(List<QAuthzClient.AssignmentRecord> items, int page, int size, long total, boolean hasNext) {}

    public Page list(String serviceCode, int page, int size, AdminPrincipal admin, String cid) {
        scope(admin, serviceCode);
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), MAX_SIZE);
        QAuthzClient.AssignmentListPage res = authz.listAgencyAssignmentRecords(serviceCode, p, s, cid);
        return new Page(res.items(), p, s, res.total(), res.hasNext());
    }

    public QAuthzClient.AssignmentRecord assign(String serviceCode, String qimUserId, Instant expiresAt, String reason, AdminPrincipal admin, String cid) {
        scope(admin, serviceCode);
        requireCode(qimUserId, "qimUserId");
        QAuthzClient.AssignmentRecord rec = authz.assign(serviceCode, qimUserId, admin.username(), expiresAt, reason, cid);
        audit("ASSIGNMENT_GRANTED", "ASSIGNMENT", qimUserId, serviceCode, admin, cid, meta("qimUserId", qimUserId, "expiresAt", expiresAt, "reason", reason));
        log.info("[AssignmentAdmin] 할당: service={} user={} by={}", serviceCode, qimUserId, admin.username());
        return rec;
    }

    public void unassign(String serviceCode, String qimUserId, String reason, AdminPrincipal admin, String cid) {
        scope(admin, serviceCode);
        requireCode(qimUserId, "qimUserId");
        authz.unassign(serviceCode, qimUserId, admin.username(), reason, cid);
        audit("ASSIGNMENT_REVOKED", "ASSIGNMENT", qimUserId, serviceCode, admin, cid, meta("qimUserId", qimUserId, "reason", reason));
        log.info("[AssignmentAdmin] 할당 해제: service={} user={} by={}", serviceCode, qimUserId, admin.username());
    }

    public List<QAuthzClient.RoleRecord> roles(String serviceCode, AdminPrincipal admin, String cid) {
        scope(admin, serviceCode);
        return authz.listRoles(serviceCode, cid);
    }

    public QAuthzClient.RoleRecord createRole(String serviceCode, String roleCode, String name, String description, AdminPrincipal admin, String cid) {
        scope(admin, serviceCode);
        requireCode(roleCode, "roleCode");
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new PlatformException(PlatformErrorCode.IDO_AUTHZ_REJECTED, cid, "name 은 1~100자");
        }
        QAuthzClient.RoleRecord rec = authz.createRole(serviceCode, roleCode, name.trim(), description, admin.username(), cid);
        audit("ROLE_CREATED", "ROLE", roleCode, serviceCode, admin, cid, meta("roleCode", roleCode, "name", name));
        return rec;
    }

    public List<QAuthzClient.UserRoleRecord> userRoles(String serviceCode, String qimUserId, AdminPrincipal admin, String cid) {
        scope(admin, serviceCode);
        requireCode(qimUserId, "qimUserId");
        return authz.listUserRoles(qimUserId, serviceCode, cid);
    }

    public QAuthzClient.UserRoleRecord grantRole(String serviceCode, String qimUserId, String roleCode, Instant expiresAt, String reason, AdminPrincipal admin, String cid) {
        scope(admin, serviceCode);
        requireCode(qimUserId, "qimUserId");
        requireCode(roleCode, "roleCode");
        QAuthzClient.UserRoleRecord rec = authz.grantRole(serviceCode, qimUserId, roleCode, admin.username(), expiresAt, reason, cid);
        audit("ROLE_GRANTED", "ROLE", roleCode, serviceCode, admin, cid, meta("qimUserId", qimUserId, "roleCode", roleCode, "expiresAt", expiresAt, "reason", reason));
        return rec;
    }

    public void revokeRole(String serviceCode, String qimUserId, String roleCode, String reason, AdminPrincipal admin, String cid) {
        scope(admin, serviceCode);
        requireCode(qimUserId, "qimUserId");
        requireCode(roleCode, "roleCode");
        authz.revokeRole(serviceCode, qimUserId, roleCode, admin.username(), reason, cid);
        audit("ROLE_REVOKED", "ROLE", roleCode, serviceCode, admin, cid, meta("qimUserId", qimUserId, "roleCode", roleCode, "reason", reason));
    }

    // ── 내부 ──

    /** Service 가 있어야 하고(없으면 404 E-AGENCY-307) 관리자의 테넌트 범위 안이어야 한다(403) */
    private void scope(AdminPrincipal admin, String serviceCode) {
        if (serviceProfileService.find(serviceCode).isEmpty()) {
            throw new PlatformException(PlatformErrorCode.AGENCY_NOT_FOUND, null, "등록되지 않은 서비스: " + serviceCode);
        }
        tenantScope.checkService(admin, serviceCode);
    }

    private static void requireCode(String value, String field) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw new PlatformException(PlatformErrorCode.IDO_AUTHZ_REJECTED, null, field + " 는 영문·숫자·_.:- 1~100자");
        }
    }

    private static Map<String, Object> meta(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) m.put(String.valueOf(kv[i]), kv[i + 1] instanceof Instant t ? t.toString() : kv[i + 1]);
        return m;
    }

    private void audit(String action, String resourceType, String resourceId, String serviceCode, AdminPrincipal admin, String cid, Map<String, Object> meta) {
        auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                .eventCategory("ADMIN").eventAction(action).actorType("ADMIN").actorId(admin.username())
                .resourceType(resourceType).resourceId(resourceId).agencyCode(serviceCode).correlationId(cid).outcome("SUCCESS")
                .metadata(meta).build());
    }
}

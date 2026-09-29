package io.github.hipstermin.idem.hub.scim;

import io.github.hipstermin.idem.common.util.UuidV7;
import io.github.hipstermin.idem.hub.infrastructure.QAuthzClient;
import io.github.hipstermin.idem.hub.policy.PolicyEngine;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 1.1 SCIM 전체 동기화(재조정) — Service 의 ACTIVE 할당 전부를 ENSURE_USER + 역할 그룹으로 적재한다.
 * 도입 시점(기존 사용자) 과 기관 쪽 데이터가 어긋났을 때 관리자가 실행한다. 사용자 수만큼 authz·registry 를 부르므로 배치.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScimSyncService {

    private final ServiceProfileService serviceProfileService;
    private final QAuthzClient qAuthzClient;
    private final PolicyEngine policyEngine;
    private final ScimOutboxService outboxService;
    private final JdbcTemplate jdbcTemplate;

    public record SyncResult(String syncId, int users, int enqueued, int skipped) {}
    public record Status(long pending, long dispatched, long failed, long skipped, String lastError, java.time.Instant lastDispatchedAt) {}

    public SyncResult fullSync(String agencyCode, String correlationId) {
        ServiceProfile profile = serviceProfileService.get(agencyCode);
        ServiceProfile.Scim scim = ScimOutboxService.scimOf(profile);
        if (scim == null || !scim.enabledOrFalse()) {
            throw new IllegalStateException("SCIM 아웃바운드가 켜져 있지 않은 서비스입니다: " + agencyCode);
        }
        String syncId = UuidV7.generate();
        int users = 0, enqueued = 0, skipped = 0, page = 0;
        QAuthzClient.AssignmentPage p;
        do {
            p = qAuthzClient.listAgencyAssignments(agencyCode, page, 200, correlationId);
            for (String qimUserId : p.qimUserIds()) {
                users++;
                String subject = policyEngine.resolveAgencySubjectId(profile, qimUserId, agencyCode, correlationId);
                if (subject == null) { skipped++; continue; }   // GUEST(기관 매핑 없음)는 프로비저닝하지 않는다
                var roles = qAuthzClient.getEffectiveRoles(qimUserId, agencyCode, correlationId);
                enqueued += outboxService.enqueueFullSyncUser(profile, subject, roles, syncId, correlationId);
            }
            page++;
        } while (p.hasNext() && page < 10_000);
        log.info("[ScimSync] 전체 동기화 적재: agency={} users={} enqueued={} skipped={} syncId={}", agencyCode, users, enqueued, skipped, syncId);
        return new SyncResult(syncId, users, enqueued, skipped);
    }

    public Status status(String agencyCode) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT COALESCE(SUM(CASE WHEN status='PENDING' THEN 1 ELSE 0 END),0) AS pending,
                       COALESCE(SUM(CASE WHEN status='DISPATCHED' THEN 1 ELSE 0 END),0) AS dispatched,
                       COALESCE(SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END),0) AS failed,
                       COALESCE(SUM(CASE WHEN status='SKIPPED' THEN 1 ELSE 0 END),0) AS skipped,
                       MAX(dispatched_at) AS last_dispatched_at,
                       (SELECT last_error_message FROM idem_hub.scim_outbox WHERE agency_code = ? AND status = 'FAILED'
                          ORDER BY created_at DESC LIMIT 1) AS last_error
                FROM idem_hub.scim_outbox WHERE agency_code = ?
                """, agencyCode, agencyCode);
        Object ts = row.get("last_dispatched_at");
        return new Status(n(row.get("pending")), n(row.get("dispatched")), n(row.get("failed")), n(row.get("skipped")),
                (String) row.get("last_error"), ts instanceof java.sql.Timestamp t ? t.toInstant() : null);
    }

    private static long n(Object o) { return o instanceof Number x ? x.longValue() : 0L; }
}

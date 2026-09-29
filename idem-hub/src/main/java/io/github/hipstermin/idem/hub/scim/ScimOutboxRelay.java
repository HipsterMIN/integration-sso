package io.github.hipstermin.idem.hub.scim;

import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.infrastructure.AgencyCredentialStore;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 1.1 SCIM 아웃박스 릴레이 — {@code idem_hub.scim_outbox} 의 PENDING 행을 기관 SCIM 서버에 반영한다.
 *
 * <ul>
 *   <li>SKIP LOCKED 배치, 지수 백오프({@code backoff-base-seconds << retry}), 초과 시 FAILED + 감사 {@code SCIM_DISPATCH_FAILED}</li>
 *   <li>프로파일이 꺼졌거나 자격증명이 없으면 SKIPPED(재시도 없음, 감사에 남긴다)</li>
 *   <li>4xx 중 400·401·403·404(사용자 없음은 클라이언트가 멱등 처리)·501 은 재시도해도 같으므로 즉시 FAILED, 나머지(429·5xx·연결 실패)는 재시도</li>
 * </ul>
 */
@Slf4j
@Component
public class ScimOutboxRelay {

    public static final String AUDIT_ACTION_DISPATCHED = "SCIM_DISPATCHED";
    public static final String AUDIT_ACTION_FAILED = "SCIM_DISPATCH_FAILED";
    public static final String AUDIT_CATEGORY = "SCIM";

    private final JdbcTemplate jdbcTemplate;
    private final ScimClient scimClient;
    private final ServiceProfileService serviceProfileService;
    private final AgencyCredentialStore credentialStore;
    private final AuditLogPublisher auditLogPublisher;

    /** 스케줄 릴레이 On/Off — 빈은 항상 있어 관리 API·테스트가 relayOnce() 를 직접 부를 수 있다 */
    @Value("${idem.hub.scim.relay-enabled:true}")
    private boolean relayEnabled;
    @Value("${idem.hub.scim.relay-batch-size:20}")
    private int batchSize;
    @Value("${idem.hub.scim.backoff-base-seconds:10}")
    private long backoffBaseSeconds;

    public ScimOutboxRelay(JdbcTemplate jdbcTemplate, ScimClient scimClient, ServiceProfileService serviceProfileService,
                           AgencyCredentialStore credentialStore, AuditLogPublisher auditLogPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.scimClient = scimClient;
        this.serviceProfileService = serviceProfileService;
        this.credentialStore = credentialStore;
        this.auditLogPublisher = auditLogPublisher;
    }

    @Scheduled(fixedDelayString = "${idem.hub.scim.relay-interval-ms:2000}", initialDelayString = "${idem.hub.scim.relay-initial-delay-ms:15000}")
    public void relay() {
        if (!relayEnabled) return;
        try {
            relayOnce();
        } catch (Exception e) {
            log.warn("[ScimRelay] 주기 실패: {}", e.getMessage());
        }
    }

    /** 한 배치 처리. @return 처리한 행 수 */
    public int relayOnce() {
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList("""
                    SELECT scim_id, agency_code, op, agency_subject_id, role_code, payload::text AS payload,
                           source_event_id, source_event_type, correlation_id, retry_count, max_retry
                    FROM idem_hub.scim_outbox
                    WHERE status = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= NOW())
                    ORDER BY created_at ASC
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """, batchSize);
        } catch (Exception e) {
            log.warn("[ScimRelay] PENDING 조회 실패: {}", e.getMessage());
            return 0;
        }
        Map<String, Optional<ScimClient.Target>> targets = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String agency = (String) row.get("agency_code");
            Optional<ScimClient.Target> target = targets.computeIfAbsent(agency, a -> resolveTarget(a, (String) row.get("correlation_id")));
            dispatch(row, target.orElse(null));
        }
        return rows.size();
    }

    /** 프로파일 + 자격증명 → 대상. 꺼졌거나 토큰이 없으면 empty. */
    Optional<ScimClient.Target> resolveTarget(String agency, String cid) {
        Optional<ServiceProfile> profile = serviceProfileService.find(agency);
        ServiceProfile.Scim scim = profile.map(ScimOutboxService::scimOf).orElse(null);
        if (scim == null || !scim.enabledOrFalse() || scim.baseUrl() == null) return Optional.empty();
        String token = credentialStore.findSecret(scim.credentialRef());
        if (token == null || token.isBlank()) {
            log.warn("[ScimRelay] SCIM 토큰 없음 — 자격증명 참조 {} (환경변수 미주입): agency={}", scim.credentialRef(), agency);
            return Optional.empty();
        }
        return Optional.of(new ScimClient.Target(scim.baseUrl(), token, cid));
    }

    void dispatch(Map<String, Object> row, ScimClient.Target target) {
        String id = (String) row.get("scim_id");
        String agency = (String) row.get("agency_code");
        String op = (String) row.get("op");
        String subject = (String) row.get("agency_subject_id");
        String role = (String) row.get("role_code");
        String cid = (String) row.get("correlation_id");
        int retry = ((Number) row.get("retry_count")).intValue();
        int maxRetry = ((Number) row.get("max_retry")).intValue();

        if (target == null) {
            mark(id, "SKIPPED", null, "SCIM 비활성 또는 자격증명 없음");
            audit(AUDIT_ACTION_FAILED, agency, id, op, role, cid, "FAILURE", null, "SKIPPED: SCIM 비활성 또는 자격증명 없음");   // outcome CHECK: SUCCESS/FAILURE/PARTIAL
            return;
        }
        try {
            switch (op) {
                case ScimOutboxService.OP_ENSURE_USER -> scimClient.ensureUser(target, subject, true);
                case ScimOutboxService.OP_DEACTIVATE_USER -> scimClient.deactivateUser(target, subject);
                case ScimOutboxService.OP_DELETE_USER -> scimClient.deleteUser(target, subject);
                case ScimOutboxService.OP_ADD_GROUP_MEMBER -> scimClient.addGroupMember(target, role, subject);
                case ScimOutboxService.OP_REMOVE_GROUP_MEMBER -> scimClient.removeGroupMember(target, role, subject);
                default -> throw new ScimClient.ScimException(400, "알 수 없는 op " + op);
            }
            mark(id, "DISPATCHED", 200, null);
            audit(AUDIT_ACTION_DISPATCHED, agency, id, op, role, cid, "SUCCESS", 200, null);
            log.info("[ScimRelay] 반영: agency={} op={} role={} id={}", agency, op, role, id);
        } catch (ScimClient.ScimException e) {
            boolean permanent = e.status() == 400 || e.status() == 401 || e.status() == 403 || e.status() == 501;
            if (permanent || retry + 1 >= maxRetry) {
                mark(id, "FAILED", e.status() == 0 ? null : e.status(), e.getMessage());
                audit(AUDIT_ACTION_FAILED, agency, id, op, role, cid, "FAILURE", e.status(), e.getMessage());
                log.error("[ScimRelay] 실패(종결): agency={} op={} status={} retry={}/{} err={}", agency, op, e.status(), retry, maxRetry, e.getMessage());
            } else {
                long backoff = backoffBaseSeconds << retry;
                jdbcTemplate.update("""
                        UPDATE idem_hub.scim_outbox SET retry_count = ?, last_http_status = ?, last_error_message = ?,
                               next_retry_at = NOW() + (? || ' seconds')::interval
                        WHERE scim_id = ?
                        """, retry + 1, e.status() == 0 ? null : e.status(), truncate(e.getMessage()), String.valueOf(backoff), id);
                log.warn("[ScimRelay] 재시도 예약: agency={} op={} status={} retry={}/{} backoff={}s", agency, op, e.status(), retry + 1, maxRetry, backoff);
            }
        } catch (Exception e) {
            mark(id, "FAILED", null, e.getMessage());
            audit(AUDIT_ACTION_FAILED, agency, id, op, role, cid, "FAILURE", null, e.getMessage());
            log.error("[ScimRelay] 실패(예외): agency={} op={} id={} err={}", agency, op, id, e.getMessage());
        }
    }

    private void mark(String id, String status, Integer httpStatus, String error) {
        try {
            jdbcTemplate.update("""
                    UPDATE idem_hub.scim_outbox SET status = ?, last_http_status = ?, last_error_message = ?,
                           dispatched_at = CASE WHEN ? = 'DISPATCHED' THEN NOW() ELSE dispatched_at END
                    WHERE scim_id = ?
                    """, status, httpStatus, truncate(error), status, id);
        } catch (Exception e) {
            log.error("[ScimRelay] 상태 갱신 실패: id={} status={} err={}", id, status, e.getMessage());
        }
    }

    private void audit(String action, String agency, String id, String op, String role, String cid, String outcome, Integer status, String detail) {
        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put("op", op);
            if (role != null) meta.put("roleCode", role);
            if (status != null) meta.put("httpStatus", status);
            auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                    .eventCategory(AUDIT_CATEGORY).eventAction(action).actorType("SYSTEM").actorId("idem-hub")
                    .resourceType("SCIM_OUTBOX").resourceId(id).agencyCode(agency).correlationId(cid)
                    .outcome(outcome).outcomeDetail(truncate(detail)).metadata(meta).build());
        } catch (Exception e) {
            log.debug("[ScimRelay] 감사 발행 실패(비치명적): {}", e.getMessage());
        }
    }

    private static String truncate(String s) { return s == null ? null : s.length() <= 500 ? s : s.substring(0, 500); }
}

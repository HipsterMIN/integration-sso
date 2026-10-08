package io.github.hipstermin.idem.hub.audit.anomaly;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal;
import io.github.hipstermin.idem.hub.admin.auth.AdminTenantScope;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 플래그 목록·통계·검토 — 관찰 모드의 산출물은 규칙별 검토 결과(정밀도)다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyAdminService {

    public static final int MAX_SIZE = 200;
    public static final Set<String> VERDICTS = Set.of("TRUE_POSITIVE", "FALSE_POSITIVE", "UNSURE");
    public static final String AUDIT_ACTION_REVIEWED = "ANOMALY_REVIEWED";

    private final JdbcTemplate jdbc;
    private final AdminTenantScope tenantScope;
    private final AuditLogPublisher auditLogPublisher;

    public record Query(Instant from, Instant to, String rule, String agencyCode, String severity, String review, int page, int size) {}
    public record Page(List<Map<String, Object>> items, int page, int size, long total) {}
    public record RuleStat(String rule, long total, long truePositive, long falsePositive, long unsure, long unreviewed, Double precision) {}
    public record Stats(int days, List<RuleStat> byRule, List<Map<String, Object>> byDay, Map<String, Object> cursor) {}

    public Page list(Query q, AdminPrincipal admin) {
        requireScope(admin, q.agencyCode());
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (q.from() != null) { where.append(" AND occurred_at >= ?"); args.add(Timestamp.from(q.from())); }
        if (q.to() != null) { where.append(" AND occurred_at < ?"); args.add(Timestamp.from(q.to())); }
        if (q.rule() != null && !q.rule().isBlank()) { where.append(" AND rule = ?"); args.add(q.rule().trim()); }
        if (q.agencyCode() != null && !q.agencyCode().isBlank()) { where.append(" AND agency_code = ?"); args.add(q.agencyCode().trim()); }
        if (q.severity() != null && !q.severity().isBlank()) { where.append(" AND severity = ?"); args.add(q.severity().trim()); }
        if (q.review() != null && !q.review().isBlank()) {
            switch (q.review().trim()) {
                case "UNREVIEWED" -> where.append(" AND review IS NULL");
                case "REVIEWED" -> where.append(" AND review IS NOT NULL");
                default -> { where.append(" AND review = ?"); args.add(q.review().trim()); }
            }
        }
        int size = Math.max(1, Math.min(q.size(), MAX_SIZE));
        int page = Math.max(0, q.page());
        Long total = jdbc.queryForObject("SELECT COUNT(1) FROM idem_hub.audit_anomaly_flag" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<Map<String, Object>> items = jdbc.query("SELECT flag_id, audit_id, rule, score, severity, subject_type, subject, details::text AS details, "
                        + "event_category, event_action, agency_code, actor_id, source_ip, correlation_id, occurred_at, review, reviewed_by, reviewed_at, review_note, created_at "
                        + "FROM idem_hub.audit_anomaly_flag" + where + " ORDER BY occurred_at DESC LIMIT ? OFFSET ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("flagId", rs.getString("flag_id"));
                    m.put("auditId", rs.getString("audit_id"));
                    m.put("rule", rs.getString("rule"));
                    m.put("score", rs.getInt("score"));
                    m.put("severity", rs.getString("severity"));
                    m.put("subjectType", rs.getString("subject_type"));
                    m.put("subject", rs.getString("subject"));
                    m.put("details", rs.getString("details"));
                    m.put("category", rs.getString("event_category"));
                    m.put("action", rs.getString("event_action"));
                    m.put("agencyCode", rs.getString("agency_code"));
                    m.put("actorId", rs.getString("actor_id"));
                    m.put("sourceIp", rs.getString("source_ip"));
                    m.put("correlationId", rs.getString("correlation_id"));
                    m.put("occurredAt", instant(rs.getTimestamp("occurred_at")));
                    m.put("review", rs.getString("review"));
                    m.put("reviewedBy", rs.getString("reviewed_by"));
                    m.put("reviewedAt", instant(rs.getTimestamp("reviewed_at")));
                    m.put("reviewNote", rs.getString("review_note"));
                    m.put("createdAt", instant(rs.getTimestamp("created_at")));
                    return m;
                }, pageArgs.toArray());
        return new Page(items, page, size, total == null ? 0 : total);
    }

    public Stats stats(int days, AdminPrincipal admin, String agencyCode) {
        requireScope(admin, agencyCode);
        int d = Math.max(1, Math.min(days, 400));
        Timestamp since = Timestamp.from(Instant.now().minusSeconds(d * 86400L));
        String scope = agencyCode != null && !agencyCode.isBlank() ? " AND agency_code = ?" : "";
        Object[] args = scope.isEmpty() ? new Object[] {since} : new Object[] {since, agencyCode.trim()};
        List<RuleStat> byRule = new ArrayList<>();
        Map<String, long[]> acc = new LinkedHashMap<>();
        for (String r : AnomalyRules.ALL) acc.put(r, new long[5]);
        jdbc.query("SELECT rule, review, COUNT(*) AS n FROM idem_hub.audit_anomaly_flag WHERE occurred_at >= ?" + scope + " GROUP BY rule, review",
                rs -> {
                    long[] a = acc.computeIfAbsent(rs.getString("rule"), k -> new long[5]);
                    long n = rs.getLong("n");
                    a[0] += n;
                    String rv = rs.getString("review");
                    if (rv == null) a[4] += n;
                    else switch (rv) { case "TRUE_POSITIVE" -> a[1] += n; case "FALSE_POSITIVE" -> a[2] += n; default -> a[3] += n; }
                }, args);
        acc.forEach((rule, a) -> byRule.add(new RuleStat(rule, a[0], a[1], a[2], a[3], a[4],
                a[1] + a[2] == 0 ? null : Math.round(100.0 * a[1] / (a[1] + a[2])) / 100.0)));
        List<Map<String, Object>> byDay = jdbc.query("SELECT date_trunc('day', occurred_at) AS day, rule, COUNT(*) AS n FROM idem_hub.audit_anomaly_flag "
                        + "WHERE occurred_at >= ?" + scope + " GROUP BY day, rule ORDER BY day",
                (rs, i) -> Map.of("day", instant(rs.getTimestamp("day")), "rule", rs.getString("rule"), "n", rs.getLong("n")), args);
        Map<String, Object> cursor = new LinkedHashMap<>();
        jdbc.query("SELECT last_occurred_at, scanned_total, updated_at FROM idem_hub.audit_anomaly_cursor WHERE id = 1", rs -> {
            cursor.put("lastOccurredAt", instant(rs.getTimestamp("last_occurred_at")));
            cursor.put("scannedTotal", rs.getLong("scanned_total"));
            cursor.put("updatedAt", instant(rs.getTimestamp("updated_at")));
        });
        return new Stats(d, byRule, byDay, cursor);
    }

    public Map<String, Object> review(String flagId, String verdict, String note, AdminPrincipal admin, String cid) {
        if (verdict == null || !VERDICTS.contains(verdict)) throw new PlatformException(PlatformErrorCode.IDO_INVALID_TENANT_PROFILE, cid, "verdict 는 TRUE_POSITIVE | FALSE_POSITIVE | UNSURE");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT rule, agency_code, audit_id FROM idem_hub.audit_anomaly_flag WHERE flag_id = ?", flagId);
        if (rows.isEmpty()) throw new PlatformException(PlatformErrorCode.ADMIN_NOT_FOUND, cid, "플래그 없음: " + flagId);
        String agency = (String) rows.get(0).get("agency_code");
        if (!admin.isGlobal() && (agency == null || !tenantScope.inScope(admin, agency))) {
            throw new PlatformException(PlatformErrorCode.ADMIN_FORBIDDEN, cid, "범위 밖 플래그");
        }
        String n = note == null ? null : (note.length() > 500 ? note.substring(0, 500) : note);
        jdbc.update("UPDATE idem_hub.audit_anomaly_flag SET review = ?, reviewed_by = ?, reviewed_at = NOW(), review_note = ? WHERE flag_id = ?",
                verdict, admin.username(), n, flagId);
        auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                .eventCategory("ADMIN").eventAction(AUDIT_ACTION_REVIEWED).actorType("ADMIN").actorId(admin.username())
                .resourceType("ANOMALY_FLAG").resourceId(flagId).agencyCode(agency).correlationId(cid).outcome("SUCCESS")
                .metadata(Map.of("rule", rows.get(0).get("rule"), "verdict", verdict, "auditId", rows.get(0).get("audit_id")))
                .build());
        log.info("[AuditAnomaly] 검토: flag={} rule={} verdict={} by={}", flagId, rows.get(0).get("rule"), verdict, admin.username());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flagId", flagId); out.put("review", verdict); out.put("reviewedBy", admin.username());
        return out;
    }

    private void requireScope(AdminPrincipal admin, String agencyCode) {
        if (admin.isGlobal()) return;
        if (agencyCode == null || agencyCode.isBlank()) throw new PlatformException(PlatformErrorCode.ADMIN_FORBIDDEN, null, "Tenant 범위 관리자는 agencyCode 를 지정해야 합니다");
        if (!tenantScope.inScope(admin, agencyCode)) throw new PlatformException(PlatformErrorCode.ADMIN_FORBIDDEN, null, "다른 Tenant 의 Service: " + agencyCode);
    }

    private static String instant(Timestamp t) { return t == null ? null : t.toInstant().toString(); }
}

package io.github.hipstermin.idem.hub.audit.anomaly;

import io.github.hipstermin.idem.common.util.UuidV7;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 점수기·규칙이 쓰는 질의 — 전부 창(window) 안 건수와 플래그 적재. audit_log 에는 읽기만 한다. */
@Repository
@RequiredArgsConstructor
public class AnomalyRepository {

    private final JdbcTemplate jdbc;

    // ── 커서 ────────────────────────────────────────────────────────────────

    /** 커서 행을 잠근다(SKIP LOCKED). 다른 복제본이 쥐고 있으면 null. */
    public Map<String, Object> lockCursor() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT last_audit_id, last_occurred_at, scanned_total FROM idem_hub.audit_anomaly_cursor WHERE id = 1 FOR UPDATE SKIP LOCKED");
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String maxAuditId() {
        return jdbc.queryForObject("SELECT MAX(audit_id) FROM idem_hub.audit_log", String.class);
    }

    public void updateCursor(String lastAuditId, Instant lastOccurredAt, int scanned) {
        jdbc.update("UPDATE idem_hub.audit_anomaly_cursor SET last_audit_id = ?, last_occurred_at = COALESCE(?, last_occurred_at), "
                        + "scanned_total = scanned_total + ?, updated_at = NOW() WHERE id = 1",
                lastAuditId, lastOccurredAt == null ? null : Timestamp.from(lastOccurredAt), scanned);
    }

    public List<AuditRow> fetchAfter(String lastAuditId, int limit, int lagSeconds) {
        return jdbc.query("""
                SELECT audit_id, event_category, event_action, actor_type, actor_id, agency_code, source_ip, correlation_id,
                       outcome, outcome_detail, occurred_at
                FROM idem_hub.audit_log
                WHERE audit_id > ? AND occurred_at < NOW() - (? || ' seconds')::interval
                ORDER BY audit_id ASC
                LIMIT ?
                """, (rs, i) -> new AuditRow(rs.getString("audit_id"), rs.getString("event_category"), rs.getString("event_action"),
                        rs.getString("actor_type"), rs.getString("actor_id"), rs.getString("agency_code"), rs.getString("source_ip"),
                        rs.getString("correlation_id"), rs.getString("outcome"), rs.getString("outcome_detail"),
                        rs.getTimestamp("occurred_at").toInstant()),
                lastAuditId, String.valueOf(lagSeconds), limit);
    }

    // ── 규칙 질의 (창은 [from, to]) ───────────────────────────────────────────

    public long countActorActions(String actorId, Collection<String> actions, Instant from, Instant to) {
        String in = String.join(",", actions.stream().map(a -> "?").toList());
        Object[] args = new Object[actions.size() + 3];
        args[0] = actorId;
        int i = 1;
        for (String a : actions) args[i++] = a;
        args[i++] = Timestamp.from(from);
        args[i] = Timestamp.from(to);
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM idem_hub.audit_log WHERE actor_id = ? AND event_action IN (" + in + ") AND occurred_at >= ? AND occurred_at <= ?",
                Long.class, args);
        return n == null ? 0 : n;
    }

    public boolean actorSeenFromIp(String actorId, String ip, String action, Instant from, Instant to, String excludeAuditId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM idem_hub.audit_log WHERE actor_id = ? AND source_ip = ? AND event_action = ? "
                        + "AND occurred_at >= ? AND occurred_at <= ? AND audit_id <> ?", Long.class,
                actorId, ip, action, Timestamp.from(from), Timestamp.from(to), excludeAuditId);
        return n != null && n > 0;
    }

    public boolean actorHasPrior(String actorId, String action, Instant before, String excludeAuditId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM idem_hub.audit_log WHERE actor_id = ? AND event_action = ? AND outcome = 'SUCCESS' "
                        + "AND occurred_at <= ? AND audit_id <> ?", Long.class, actorId, action, Timestamp.from(before), excludeAuditId);
        return n != null && n > 0;
    }

    public long countAgencyFailures(String agencyCode, Instant from, Instant to) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM idem_hub.audit_log WHERE agency_code = ? AND outcome <> 'SUCCESS' AND occurred_at >= ? AND occurred_at <= ?",
                Long.class, agencyCode, Timestamp.from(from), Timestamp.from(to));
        return n == null ? 0 : n;
    }

    public long countAgencyActionDetail(String agencyCode, String action, String detail, Instant from, Instant to) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM idem_hub.audit_log WHERE agency_code = ? AND event_action = ? AND outcome <> 'SUCCESS' "
                        + "AND outcome_detail = ? AND occurred_at >= ? AND occurred_at <= ?", Long.class,
                agencyCode, action, detail, Timestamp.from(from), Timestamp.from(to));
        return n == null ? 0 : n;
    }

    /** 같은 규칙·축이 창 안에서 이미 플래그됐는가 — 버스트 하나에 플래그 하나 */
    public boolean flagExists(String rule, String subjectType, String subject, Instant since) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM idem_hub.audit_anomaly_flag WHERE rule = ? AND subject_type = ? AND subject = ? AND occurred_at >= ?",
                Long.class, rule, subjectType, subject, Timestamp.from(since));
        return n != null && n > 0;
    }

    // ── 플래그 적재 ──────────────────────────────────────────────────────────

    /** @return true 면 새로 넣었다 (같은 감사 행·규칙은 한 번만) */
    public boolean insertFlag(AnomalyFlag f, String detailsJson) {
        AuditRow r = f.row();
        try {
            return jdbc.update("""
                    INSERT INTO idem_hub.audit_anomaly_flag (flag_id, audit_id, rule, score, severity, subject_type, subject, details,
                        event_category, event_action, agency_code, actor_id, source_ip, correlation_id, occurred_at)
                    VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?)
                    ON CONFLICT (audit_id, rule) DO NOTHING
                    """, UuidV7.generate(), r.auditId(), f.rule(), f.score(), f.severity(), f.subjectType(), f.subject(), detailsJson,
                    r.category(), r.action(), r.agencyCode(), r.actorId(), r.sourceIp(), r.correlationId(), Timestamp.from(r.occurredAt())) > 0;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}

package io.github.hipstermin.idem.hub.admin.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 감사 로그 검색 ({@code ido.audit_log}) — 기간·분류·행위·주체·기관·결과 (execution-plan P1 §3.2 "검토"). */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    public static final int MAX_SIZE = 200;

    private final JdbcTemplate jdbcTemplate;

    public record Query(Instant from, Instant to, String category, String action, String actorId, String agencyCode,
                        String outcome, String correlationId, int page, int size) {}

    public record Page(List<Map<String, Object>> items, int page, int size, long total) {}

    public Page search(Query q) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (q.from() != null) { where.append(" AND occurred_at >= ?"); args.add(java.sql.Timestamp.from(q.from())); }
        if (q.to() != null) { where.append(" AND occurred_at < ?"); args.add(java.sql.Timestamp.from(q.to())); }
        add(where, args, "event_category", q.category());
        add(where, args, "event_action", q.action());
        add(where, args, "actor_id", q.actorId());
        add(where, args, "agency_code", q.agencyCode());
        add(where, args, "outcome", q.outcome());
        add(where, args, "correlation_id", q.correlationId());

        int size = Math.max(1, Math.min(q.size(), MAX_SIZE));
        int page = Math.max(0, q.page());
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM ido.audit_log" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<Map<String, Object>> items = jdbcTemplate.query(
                "SELECT audit_id, event_category, event_action, actor_type, actor_id, resource_type, resource_id, agency_code, "
                        + "correlation_id, source_system, source_ip, outcome, outcome_detail, metadata::text AS metadata, occurred_at "
                        + "FROM ido.audit_log" + where + " ORDER BY occurred_at DESC LIMIT ? OFFSET ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("auditId", rs.getString("audit_id"));
                    m.put("category", rs.getString("event_category"));
                    m.put("action", rs.getString("event_action"));
                    m.put("actorType", rs.getString("actor_type"));
                    m.put("actorId", rs.getString("actor_id"));
                    m.put("resourceType", rs.getString("resource_type"));
                    m.put("resourceId", rs.getString("resource_id"));
                    m.put("agencyCode", rs.getString("agency_code"));
                    m.put("correlationId", rs.getString("correlation_id"));
                    m.put("sourceSystem", rs.getString("source_system"));
                    m.put("sourceIp", rs.getString("source_ip"));
                    m.put("outcome", rs.getString("outcome"));
                    m.put("outcomeDetail", rs.getString("outcome_detail"));
                    m.put("metadata", rs.getString("metadata"));
                    java.sql.Timestamp at = rs.getTimestamp("occurred_at");
                    m.put("occurredAt", at != null ? at.toInstant().toString() : null);
                    return m;
                }, pageArgs.toArray());
        return new Page(items, page, size, total == null ? 0 : total);
    }

    private static void add(StringBuilder where, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) { where.append(" AND ").append(column).append(" = ?"); args.add(value.trim()); }
    }
}

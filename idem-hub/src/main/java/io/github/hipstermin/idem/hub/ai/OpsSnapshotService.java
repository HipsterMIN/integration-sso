package io.github.hipstermin.idem.hub.ai;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 장애 요약의 입력 — hub 가 이미 가진 운영 신호를 한 장으로 모은다. 사용자 데이터는 없다(건수·상태·지표뿐).
 * <ul>
 *   <li>health 구성요소(db·redis·kms …)</li>
 *   <li>웹훅·SCIM 아웃박스, SLO IdP 재시도 큐 — 상태별 건수, 5분 넘게 PENDING, 24시간 내 FAILED, 마지막 오류</li>
 *   <li>감사 FAILURE 건수 — 1시간·24시간, 분류별</li>
 *   <li>감사 유실·WAL 지표</li>
 * </ul>
 * 한 항목의 조회가 실패해도 나머지는 낸다(항목에 {@code error} 를 넣는다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsSnapshotService {

    private final JdbcTemplate jdbc;
    private final ObjectProvider<HealthEndpoint> healthEndpoint;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    public record Snapshot(String at, Map<String, String> health, Map<String, Object> webhookOutbox, Map<String, Object> scimOutbox,
                           Map<String, Object> sloRetry, Map<String, Long> auditFailures1h, Map<String, Long> auditFailures24h,
                           Map<String, Double> metrics, Map<String, Long> auditAnomalies24h) {}

    public Snapshot snapshot() {
        return new Snapshot(Instant.now().toString(), health(),
                safe(() -> outbox("idem_hub.webhook_dispatch_outbox", "created_at", "last_error_message")),
                safe(() -> outbox("idem_hub.scim_outbox", "created_at", "last_error_message")),
                safe(() -> outbox("idem_hub.slo_idp_logout_retry", "created_at", "last_error")),
                safeCounts(() -> auditFailures("1 hour")), safeCounts(() -> auditFailures("24 hours")), metrics(),
                safeCounts(this::anomalies24h));
    }

    /** 1.1 감사 이상 탐지(관찰 모드) — 24시간 규칙별 플래그 수 */
    Map<String, Long> anomalies24h() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("SELECT rule, COUNT(*) AS n FROM idem_hub.audit_anomaly_flag WHERE occurred_at > NOW() - INTERVAL '24 hours' GROUP BY rule ORDER BY n DESC",
                rs -> { out.put(rs.getString("rule"), rs.getLong("n")); });
        return out;
    }

    Map<String, String> health() {
        Map<String, String> out = new LinkedHashMap<>();
        HealthEndpoint ep = healthEndpoint.getIfAvailable();
        if (ep == null) { out.put("(health)", "unavailable"); return out; }
        try {
            HealthComponent root = ep.health();
            out.put("overall", root.getStatus().getCode());
            if (root instanceof CompositeHealth c) {
                c.getComponents().forEach((k, v) -> out.put(k, v.getStatus().getCode()));
            }
        } catch (RuntimeException e) {
            out.put("(health)", "error: " + e.getMessage());
        }
        return out;
    }

    Map<String, Object> outbox(String table, String createdCol, String errorCol) {
        Map<String, Object> out = new LinkedHashMap<>();
        jdbc.query("SELECT status, COUNT(*) AS n FROM " + table + " GROUP BY status",
                rs -> { out.put(rs.getString("status"), rs.getLong("n")); });
        out.put("pendingOver5m", jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE status = 'PENDING' AND " + createdCol + " < NOW() - INTERVAL '5 minutes'", Long.class));
        out.put("failed24h", jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE status = 'FAILED' AND " + createdCol + " > NOW() - INTERVAL '24 hours'", Long.class));
        var errors = jdbc.queryForList("SELECT " + errorCol + " FROM " + table + " WHERE status = 'FAILED' AND " + errorCol + " IS NOT NULL ORDER BY " + createdCol + " DESC LIMIT 1", String.class);
        out.put("lastError", errors.isEmpty() ? null : AuditDigest.truncate(errors.get(0)));
        return out;
    }

    Map<String, Long> auditFailures(String interval) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("SELECT event_category, COUNT(*) AS n FROM idem_hub.audit_log WHERE outcome <> 'SUCCESS' AND occurred_at > NOW() - INTERVAL '" + interval + "' GROUP BY event_category ORDER BY n DESC",
                rs -> { out.put(rs.getString("event_category"), rs.getLong("n")); });
        return out;
    }

    Map<String, Double> metrics() {
        Map<String, Double> out = new LinkedHashMap<>();
        MeterRegistry reg = meterRegistry.getIfAvailable();
        if (reg == null) return out;
        for (String name : new String[] {"audit.lost.total", "audit.wal.appended.total", "audit.wal.replayed.total", "slo.webhook.enqueued.total"}) {
            Counter c = reg.find(name).counter();
            if (c != null) out.put(name, c.count());
        }
        return out;
    }

    private static Map<String, Object> safe(Supplier<Map<String, Object>> s) {
        try { return s.get(); } catch (RuntimeException e) { log.warn("[AI] 운영 스냅샷 항목 조회 실패: {}", e.getMessage()); return Map.of("error", String.valueOf(e.getMessage())); }
    }

    private static Map<String, Long> safeCounts(Supplier<Map<String, Long>> s) {
        try { return s.get(); } catch (RuntimeException e) { log.warn("[AI] 운영 스냅샷 항목 조회 실패: {}", e.getMessage()); return Map.of(); }
    }
}

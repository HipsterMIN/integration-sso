package io.github.hipstermin.idem.relay.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 백로그 Micrometer Gauge (PR-A5 — 단순화)
 *
 * <p><b>설계 결정 — Health → Metric 강등 이유</b>:
 * 이전 PR-A4의 {@code OutboxBacklogHealthIndicator}를 본 메트릭 클래스로 대체한다.
 *
 * <ul>
 *   <li><b>왜 Health가 아닌 Metric인가</b>:
 *     백로그 적체는 "연속값"이며 2진법(UP/DOWN)으로 표현하면 정보를 잃는다.
 *     또한 백로그 적체 시 readiness DOWN → K8s 트래픽 차단 → 더 큰 적체의
 *     <b>악순환 위험</b>이 있다. Prometheus Gauge + 알람 룰이 정답.</li>
 *
 *   <li><b>왜 단순한가</b>:
 *     운영자가 코드를 읽고 즉시 이해할 수 있어야 한다. 동적 TTL 캐싱·복합
 *     임계값 정책 등은 운영 인지 부하를 늘릴 뿐 가치가 없다.
 *     본 클래스는 단순히 {@code @Scheduled}로 주기적으로 COUNT(*)를 수행하고
 *     Gauge에 반영한다.</li>
 * </ul>
 *
 * <p><b>노출 메트릭</b>:
 * <pre>
 * onepass_outbox_pending_total{shard="ido"}     → ido.outbox WHERE status='PENDING'
 * onepass_outbox_pending_total{shard="qim"}     → qim.outbox WHERE status='PENDING'
 * onepass_outbox_pending_total{shard="qsign"}   → qsign.outbox WHERE status='PENDING'
 * onepass_outbox_failed_total{shard="ido"}      → ido.outbox WHERE status='FAILED'
 * onepass_outbox_failed_total{shard="qim"}      → qim.outbox WHERE status='FAILED'
 * onepass_outbox_failed_total{shard="qsign"}    → qsign.outbox WHERE status='FAILED'
 * </pre>
 *
 * <p><b>Prometheus 알람 룰 권장</b>:
 * <pre>
 * sum(onepass_outbox_pending_total) > 1000  for 5m  → warning
 * sum(onepass_outbox_pending_total) > 10000 for 2m  → critical
 * sum(onepass_outbox_failed_total)  > 100   for 5m  → critical (수동 개입 필요)
 * </pre>
 *
 * <p><b>장애 정책</b>:
 * COUNT(*) 쿼리 실패 시 Gauge 값은 직전 값을 유지 (메트릭 갱신만 스킵).
 * Health Indicator처럼 readiness를 차단하지 않으므로 트래픽은 정상 처리된다.
 * 메트릭 수집 자체 실패는 Prometheus의 {@code up} 메트릭으로 별도 감지된다.
 */
@Slf4j
@Component
public class OutboxBacklogMetrics {

    private final JdbcTemplate idoJdbcTemplate;
    private final JdbcTemplate qimJdbcTemplate;
    private final JdbcTemplate qsignJdbcTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${batch.metrics.outbox.query-timeout-sec:3}")
    private int queryTimeoutSec;

    // 샤드별 Gauge가 참조하는 AtomicLong (Micrometer 표준 패턴)
    private final AtomicLong idoPending   = new AtomicLong(0);
    private final AtomicLong qimPending   = new AtomicLong(0);
    private final AtomicLong qsignPending = new AtomicLong(0);
    private final AtomicLong idoFailed    = new AtomicLong(0);
    private final AtomicLong qimFailed    = new AtomicLong(0);
    private final AtomicLong qsignFailed  = new AtomicLong(0);

    public OutboxBacklogMetrics(
            @Qualifier("idoJdbcTemplate")   JdbcTemplate idoJdbcTemplate,
            @Qualifier("qimJdbcTemplate")   JdbcTemplate qimJdbcTemplate,
            @Qualifier("qsignJdbcTemplate") JdbcTemplate qsignJdbcTemplate,
            MeterRegistry meterRegistry) {
        this.idoJdbcTemplate   = idoJdbcTemplate;
        this.qimJdbcTemplate   = qimJdbcTemplate;
        this.qsignJdbcTemplate = qsignJdbcTemplate;
        this.meterRegistry     = meterRegistry;
    }

    @PostConstruct
    void registerGauges() {
        // pending
        meterRegistry.gauge("onepass.outbox.pending.total",
                Tags.of("shard", "ido"),   idoPending,   AtomicLong::get);
        meterRegistry.gauge("onepass.outbox.pending.total",
                Tags.of("shard", "qim"),   qimPending,   AtomicLong::get);
        meterRegistry.gauge("onepass.outbox.pending.total",
                Tags.of("shard", "qsign"), qsignPending, AtomicLong::get);

        // failed
        meterRegistry.gauge("onepass.outbox.failed.total",
                Tags.of("shard", "ido"),   idoFailed,    AtomicLong::get);
        meterRegistry.gauge("onepass.outbox.failed.total",
                Tags.of("shard", "qim"),   qimFailed,    AtomicLong::get);
        meterRegistry.gauge("onepass.outbox.failed.total",
                Tags.of("shard", "qsign"), qsignFailed,  AtomicLong::get);

        log.info("[Outbox-Metrics] Micrometer Gauge 등록 완료: pending/failed × 3 shards");
    }

    /**
     * 30초 주기로 outbox 3개 샤드 COUNT(*) 갱신.
     *
     * <p>쿼리 실패 시 Gauge 값은 직전 값 유지(보수적). 운영자는 Prometheus의
     * 메트릭 시계열에서 갱신 정체를 즉시 감지할 수 있다.
     */
    @Scheduled(fixedDelayString = "${batch.metrics.outbox.refresh-interval-ms:30000}",
               initialDelayString = "${batch.metrics.outbox.initial-delay-ms:10000}")
    public void refreshMetrics() {
        refreshShard("ido",   idoJdbcTemplate,   "ido.outbox",   idoPending,   idoFailed);
        refreshShard("qim",   qimJdbcTemplate,   "qim.outbox",   qimPending,   qimFailed);
        refreshShard("qsign", qsignJdbcTemplate, "qsign.outbox", qsignPending, qsignFailed);
    }

    private void refreshShard(String shard, JdbcTemplate jdbc, String fqTable,
                              AtomicLong pendingGauge, AtomicLong failedGauge) {
        try {
            jdbc.setQueryTimeout(queryTimeoutSec);
            Long pending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + fqTable + " WHERE status = 'PENDING'",
                    Long.class);
            Long failed = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + fqTable + " WHERE status = 'FAILED'",
                    Long.class);
            pendingGauge.set(pending != null ? pending : 0L);
            failedGauge.set(failed   != null ? failed   : 0L);
        } catch (Exception e) {
            // Gauge 값 유지 — 메트릭 갱신만 스킵. 알람은 Prometheus가 처리.
            log.warn("[Outbox-Metrics] {} COUNT 실패 (비치명적): {}", fqTable, e.getMessage());
        }
    }
}

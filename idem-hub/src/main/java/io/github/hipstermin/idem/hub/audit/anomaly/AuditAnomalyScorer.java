package io.github.hipstermin.idem.hub.audit.anomaly;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 비동기 점수기 — 감사 행이 DB(아웃박스)에 실린 <b>뒤</b> 커서 다음 행을 배치로 읽어 규칙을 평가하고 플래그만 남긴다.
 * 인증·발급 경로와 무관하고(별도 스케줄 스레드), 실패해도 감사 기록에는 영향이 없다.
 * 복제본이 여럿이어도 커서 행 잠금(SKIP LOCKED)으로 한 번에 하나만 돈다. 첫 실행은 현재 최대 audit_id 에 맞춘다 — 과거를 소급하지 않는다.
 */
@Slf4j
@Component
public class AuditAnomalyScorer {

    public static final String METRIC_SCANNED = "audit.anomaly.scanned.total";
    public static final String METRIC_FLAGGED = "audit.anomaly.flagged.total";

    private final AnomalyRepository repo;
    private final AnomalyRules rules;
    private final AnomalyProperties props;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public AuditAnomalyScorer(AnomalyRepository repo, AnomalyRules rules, AnomalyProperties props, MeterRegistry meterRegistry,
                              ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.rules = rules;
        this.props = props;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${idem.hub.audit.anomaly.interval-ms:30000}", initialDelayString = "${idem.hub.audit.anomaly.initial-delay-ms:30000}")
    public void run() {
        if (!props.isEnabled()) return;
        try {
            int rounds = 0;
            while (scoreOnce() == props.getBatchSize() && ++rounds < 20) { /* 밀린 만큼 연달아 — 한 주기에 최대 20 배치 */ }
        } catch (Exception e) {
            log.warn("[AuditAnomaly] 점수 주기 실패: {}", e.getMessage());
        }
    }

    /** 한 배치. @return 읽은 감사 행 수 (커서를 못 쥐었거나 첫 실행이면 0) */
    public int scoreOnce() {
        Integer n = tx.execute(status -> {
            Map<String, Object> cursor = repo.lockCursor();
            if (cursor == null) return 0;
            String last = (String) cursor.get("last_audit_id");
            if (last == null) {
                String max = repo.maxAuditId();
                repo.updateCursor(max == null ? "" : max, null, 0);
                log.info("[AuditAnomaly] 커서 초기화 — 지금부터의 감사 행만 본다 (소급 없음): last={}", max);
                return 0;
            }
            List<AuditRow> rows = repo.fetchAfter(last, props.getBatchSize(), props.getLagSeconds());
            int flagged = 0;
            for (AuditRow row : rows) {
                for (AnomalyFlag f : rules.evaluate(row)) {
                    if (repo.insertFlag(f, toJson(f.details()))) {
                        flagged++;
                        meterRegistry.counter(METRIC_FLAGGED, "rule", f.rule()).increment();
                        log.info("[AuditAnomaly] 플래그: rule={} severity={} score={} subject={} auditId={} cid={}",
                                f.rule(), f.severity(), f.score(), f.subject(), row.auditId(), row.correlationId());
                    }
                }
            }
            if (!rows.isEmpty()) {
                AuditRow lastRow = rows.get(rows.size() - 1);
                repo.updateCursor(lastRow.auditId(), lastRow.occurredAt(), rows.size());
                meterRegistry.counter(METRIC_SCANNED).increment(rows.size());
                if (flagged > 0) log.info("[AuditAnomaly] 배치 {}행 중 플래그 {}건", rows.size(), flagged);
            }
            return rows.size();
        });
        return n == null ? 0 : n;
    }

    private String toJson(Map<String, Object> m) {
        try { return objectMapper.writeValueAsString(m); } catch (Exception e) { return "{}"; }
    }
}

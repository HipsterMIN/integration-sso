package kr.go.smes.batch.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Outbox 백로그 헬스 인디케이터 (PR-A4)
 *
 * <p><b>역할</b>:
 * 3개 outbox 테이블(ido.outbox / qim.outbox / qsign.outbox)의 PENDING+FAILED
 * 레코드 수를 집계하여, 백로그가 임계값을 초과하면 {@code DOWN} 상태로 알린다.
 * Spring Boot Actuator의 {@code /actuator/health}를 통해 Prometheus·K8s가
 * "릴레이 적체"를 즉시 감지하도록 한다.
 *
 * <p><b>왜 별도 HealthIndicator인가</b>:
 * <ol>
 *   <li>Kafka·KMS·DB 자체는 정상이지만 처리량이 입력을 못 따라가는 상황 감지</li>
 *   <li>배치 Pod가 살아있어도 백로그가 쌓이는 비정상 상태를 운영자에게 즉시 전달</li>
 *   <li>HPA를 SQL 백로그 기반(미래 KEDA 연동) 트리거로 확장 가능한 사전 메트릭</li>
 * </ol>
 *
 * <p><b>상태 결정 정책</b>:
 * <pre>
 * - 임계값 이내           ⇒ UP   (정상)
 * - warningThreshold 초과 ⇒ UP   (상세 details만 노출, 알람 트리거)
 * - downThreshold 초과    ⇒ DOWN (Readiness 차단 가능, 운영자 개입 필요)
 * </pre>
 *
 * <p><b>K8s Probe 매핑 권장</b>:
 * 본 Indicator는 <b>Liveness에는 포함하지 말 것</b> (백로그가 쌓여도 Pod 재시작은
 * 도움이 안 됨 — 오히려 처리 진행 중인 Job이 중단되어 악화). Readiness에는 포함하여
 * 트래픽 차단·HPA 신호로 활용.
 *
 * <p><b>성능</b>:
 * 매 probe(기본 10s)마다 3개 테이블 COUNT(*) 실행 ⇒ 부담 우려 →
 * <b>{@code cacheTtlMs}(기본 15s) 캐싱</b>으로 실제 호출은 ~15s 마다.
 * 임계값을 초과한 상태에서는 {@code cacheTtlMs/2}로 단축하여 빠른 회복 감지.
 *
 * <p><b>출력 예시</b>:
 * <pre>
 * UP (정상):
 *   "outboxBacklog": {
 *     "status": "UP",
 *     "details": {
 *       "ido":     {"pending": 12, "failed": 0},
 *       "qim":     {"pending":  3, "failed": 0},
 *       "qsign":   {"pending":  0, "failed": 0},
 *       "totalPending": 15, "totalFailed": 0,
 *       "warningThreshold": 1000, "downThreshold": 10000
 *     }
 *   }
 *
 * DOWN (백로그 적체):
 *   "outboxBacklog": {
 *     "status": "DOWN",
 *     "details": {
 *       "ido":   {"pending": 12000, "failed": 50},
 *       ...
 *       "reason": "totalPending(12000) > downThreshold(10000)"
 *     }
 *   }
 * </pre>
 */
@Slf4j
@Component("outboxBacklog")
public class OutboxBacklogHealthIndicator implements HealthIndicator {

    private final JdbcTemplate idoJdbcTemplate;
    private final JdbcTemplate qimJdbcTemplate;
    private final JdbcTemplate qsignJdbcTemplate;

    /** 백로그 경고 임계값 (PENDING+FAILED 합) — UP이지만 details에 상태 강조 */
    @Value("${batch.health.outbox.warning-threshold:1000}")
    private long warningThreshold;

    /** 백로그 다운 임계값 — DOWN 처리 */
    @Value("${batch.health.outbox.down-threshold:10000}")
    private long downThreshold;

    /** 캐시 TTL (ms) */
    @Value("${batch.health.outbox.cache-ttl-ms:15000}")
    private long cacheTtlMs;

    /** SQL 쿼리 타임아웃 (초) */
    @Value("${batch.health.outbox.query-timeout-sec:3}")
    private int queryTimeoutSec;

    private final AtomicReference<CachedResult> cache = new AtomicReference<>(null);

    public OutboxBacklogHealthIndicator(
            @Qualifier("idoJdbcTemplate")   JdbcTemplate idoJdbcTemplate,
            @Qualifier("qimJdbcTemplate")   JdbcTemplate qimJdbcTemplate,
            @Qualifier("qsignJdbcTemplate") JdbcTemplate qsignJdbcTemplate) {
        this.idoJdbcTemplate   = idoJdbcTemplate;
        this.qimJdbcTemplate   = qimJdbcTemplate;
        this.qsignJdbcTemplate = qsignJdbcTemplate;
    }

    @Override
    public Health health() {
        CachedResult cached = cache.get();
        Instant now = Instant.now();

        // 동적 TTL: 직전 DOWN이면 더 빠른 회복 감지를 위해 TTL/2
        long effectiveTtl = (cached != null && !cached.healthy)
                ? Math.max(1000L, cacheTtlMs / 2)
                : cacheTtlMs;

        if (cached != null && effectiveTtl > 0
                && Duration.between(cached.checkedAt, now).toMillis() < effectiveTtl) {
            return cached.health;
        }

        return performCheck(now);
    }

    private Health performCheck(Instant now) {
        // 각 outbox COUNT(*) — 실패해도 부분 결과 반환 (관측성 우선)
        Counts ido   = safeCount("ido",   idoJdbcTemplate,   "ido.outbox");
        Counts qim   = safeCount("qim",   qimJdbcTemplate,   "qim.outbox");
        Counts qsign = safeCount("qsign", qsignJdbcTemplate, "qsign.outbox");

        long totalPending = ido.pending + qim.pending + qsign.pending;
        long totalFailed  = ido.failed  + qim.failed  + qsign.failed;
        long totalBacklog = totalPending + totalFailed;

        // 조회 실패가 하나라도 있으면 DOWN — 백로그 가시성을 못 얻는 상태는 위험
        boolean anyQueryFailed = ido.error != null || qim.error != null || qsign.error != null;
        boolean exceedsDown    = totalBacklog > downThreshold;
        boolean exceedsWarning = totalBacklog > warningThreshold;
        boolean healthy        = !anyQueryFailed && !exceedsDown;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ido",   ido.toMap());
        details.put("qim",   qim.toMap());
        details.put("qsign", qsign.toMap());
        details.put("totalPending", totalPending);
        details.put("totalFailed",  totalFailed);
        details.put("totalBacklog", totalBacklog);
        details.put("warningThreshold", warningThreshold);
        details.put("downThreshold",    downThreshold);

        if (exceedsWarning && !exceedsDown) {
            details.put("warning",
                    String.format("totalBacklog(%d) > warningThreshold(%d)",
                            totalBacklog, warningThreshold));
        }
        if (exceedsDown) {
            details.put("reason",
                    String.format("totalBacklog(%d) > downThreshold(%d)",
                            totalBacklog, downThreshold));
        }
        if (anyQueryFailed) {
            details.put("reason",
                    "outbox COUNT query failed for one or more shards (see per-shard error)");
        }

        Health.Builder builder = healthy ? Health.up() : Health.down();
        details.forEach(builder::withDetail);
        Health result = builder.build();
        cache.set(new CachedResult(result, now, healthy));
        return result;
    }

    /**
     * outbox 테이블 COUNT(*) — 실패 시 error 필드 설정.
     *
     * <p>3개 outbox 테이블 모두 동일한 status 컬럼 값 사용:
     * 'PENDING' / 'PUBLISHED' / 'FAILED' (IdoKafkaRelayJob/QimKafkaRelayJob/QSignKafkaRelayJob 기준)
     */
    private Counts safeCount(String shard, JdbcTemplate jdbc, String fqTable) {
        try {
            jdbc.setQueryTimeout(queryTimeoutSec);
            Long pending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + fqTable + " WHERE status = 'PENDING'",
                    Long.class);
            Long failed = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + fqTable + " WHERE status = 'FAILED'",
                    Long.class);
            return new Counts(
                    pending != null ? pending : 0L,
                    failed  != null ? failed  : 0L,
                    null);
        } catch (Exception e) {
            log.warn("[Outbox-Health] {} COUNT 실패: {}", fqTable, e.getMessage());
            return new Counts(-1L, -1L,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 샤드별 카운트 결과 */
    private record Counts(long pending, long failed, String error) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pending", pending);
            m.put("failed",  failed);
            if (error != null) m.put("error", error);
            return m;
        }
    }

    private record CachedResult(Health health, Instant checkedAt, boolean healthy) {}
}

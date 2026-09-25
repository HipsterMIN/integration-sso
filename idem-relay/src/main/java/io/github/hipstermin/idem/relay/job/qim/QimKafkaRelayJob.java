package io.github.hipstermin.idem.relay.job.qim;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * idem.registry.outbox → idem.registry.user.events Kafka 릴레이 배치 Job
 *
 * <h2>대상</h2>
 * Q-IM 서비스(PostgreSQL, 스키마 qim — D1)의 idem.registry.outbox 테이블 PENDING 레코드.
 * 기존 {@code OutboxServiceImpl#relayPendingEvents()}를 대체.
 *
 * <h2>FOR UPDATE SKIP LOCKED</h2>
 * PostgreSQL 9.5+ 지원. (1 릴리스 호환으로 남긴 MariaDB 경로도 10.6+ 에서 같은 문법을 지원한다.)
 *
 * <h2>Snapshot 발행 (GAP-QIM-05)</h2>
 * 기존 인-프로세스 {@code OutboxServiceImpl}에서 스냅샷 발행 트리거를 포함했으나
 * 배치 서비스에서는 스냅샷 로직을 포함하지 않음.
 * 이유: 스냅샷 발행은 Q-IM 도메인 비즈니스 로직으로, 배치 서비스가
 * Q-IM 도메인을 직접 알 필요 없음 → Q-IM 서비스의 잔류 @Scheduled에서 처리 또는
 * Kafka Consumer에서 처리 권장.
 *
 * <h2>주의 — idem.registry.outbox 스키마</h2>
 * idem.registry.outbox 테이블 구조가 ido.outbox와 다름:
 * - status: 'PENDING' / 'PUBLISHED' / 'FAILED' (동일)
 * - payload: JSONB (PostgreSQL) — 문자열로 읽어 그대로 발행
 * - partition_key 컬럼명 확인 필요 (qim은 qimUserId)
 */
@Slf4j
@Component
public class QimKafkaRelayJob {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};
    private static final String QIM_EVENTS_TOPIC = "idem.registry.user.events";

    private final JdbcTemplate                  qimJdbcTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper                  objectMapper;

    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter deadLetterCounter;

    @Value("${idem.relay.jobs.registry.kafka.batch-size:100}")
    private int batchSize;

    @Value("${idem.relay.jobs.registry.kafka.max-retry:5}")
    private int maxRetry;

    @Value("${idem.relay.jobs.registry.kafka.enabled:true}")
    private boolean enabled;

    public QimKafkaRelayJob(
            @Qualifier("qimJdbcTemplate") JdbcTemplate qimJdbcTemplate,
            @Qualifier("batchKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.qimJdbcTemplate    = qimJdbcTemplate;
        this.kafkaTemplate      = kafkaTemplate;
        this.objectMapper       = objectMapper;
        this.successCounter     = meterRegistry.counter("idem.relay.jobs.registry.kafka.success");
        this.failureCounter     = meterRegistry.counter("idem.relay.jobs.registry.kafka.failure");
        this.deadLetterCounter  = meterRegistry.counter("idem.relay.jobs.registry.kafka.dead_letter");
    }

    /**
     * idem.registry.outbox PENDING 레코드 릴레이
     *
     * <p>lockAtMostFor 10s: 100건 × 평균 처리 50ms = 5s + 여유분.
     * Q-IM 이벤트는 사용자 가입/탈퇴 이벤트로 빈도가 낮으므로
     * 실제로는 훨씬 빠르게 완료.
     */
    @Scheduled(fixedDelayString = "${idem.relay.jobs.registry.kafka.interval-ms:500}")
    @SchedulerLock(
            name           = "qim-kafka-relay",
            lockAtMostFor  = "${idem.relay.jobs.registry.kafka.lock-at-most:10s}",
            lockAtLeastFor = "${idem.relay.jobs.registry.kafka.lock-at-least:400ms}"
    )
    @Transactional(transactionManager = "qimTransactionManager")
    public void relay() {
        if (!enabled) return;

        List<QimOutboxRow> pending = fetchPending();
        if (pending.isEmpty()) return;

        log.debug("[QimKafkaRelayJob] PENDING {} 건 발행 시작", pending.size());

        int sent = 0;
        int retried = 0;
        int dead = 0;

        for (QimOutboxRow row : pending) {
            RelayResult result = dispatch(row);
            switch (result) {
                case SENT    -> sent++;
                case RETRIED -> retried++;
                case DEAD    -> dead++;
            }
        }

        successCounter.increment(sent);
        failureCounter.increment(retried);
        deadLetterCounter.increment(dead);

        if (sent + retried + dead > 0) {
            log.info("[QimKafkaRelayJob] 완료: sent={} retried={} dead={}", sent, retried, dead);
        }
    }

    private RelayResult dispatch(QimOutboxRow row) {
        try {
            // idem.registry.outbox payload는 JSON 문자열 — Map으로 역직렬화하여 그대로 발행
            Map<String, Object> payload = objectMapper.readValue(row.payload(), MAP_TYPE_REF);

            CompletableFuture<SendResult<String, Object>> future =
                    kafkaTemplate.send(QIM_EVENTS_TOPIC, row.partitionKey(), payload);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    markPublished(row.eventId());
                } else {
                    handleFailure(row, ex);
                }
            });

            return RelayResult.SENT;

        } catch (Exception e) {
            log.error("[QimKafkaRelayJob] 발행 예외: eventId={} error={}", row.eventId(), e.getMessage());
            handleFailure(row, e);
            return RelayResult.RETRIED;
        }
    }

    private void markPublished(String eventId) {
        try {
            qimJdbcTemplate.update("""
                    UPDATE qim.outbox
                    SET status = 'PUBLISHED', published_at = NOW()
                    WHERE event_id = ?
                    """, eventId);
        } catch (Exception e) {
            log.warn("[QimKafkaRelayJob] PUBLISHED 갱신 실패 (재발행 예정): eventId={}", eventId);
        }
    }

    private void handleFailure(QimOutboxRow row, Throwable ex) {
        try {
            int nextRetry = row.retryCount() + 1;
            String errorMsg = truncate(ex.getMessage(), 500);

            if (nextRetry >= maxRetry) {
                // 영구 FAILED — 운영팀 수동 조치
                qimJdbcTemplate.update("""
                        UPDATE qim.outbox
                        SET status = 'FAILED',
                            retry_count = retry_count + 1,
                            error_message = ?
                        WHERE event_id = ?
                        """, errorMsg, row.eventId());
                log.error("[QimKafkaRelayJob] 영구 FAILED: eventId={} retry={}/{} error={}",
                        row.eventId(), nextRetry, maxRetry, errorMsg);
            } else {
                // FAILED 전환 (q-im은 next_retry_at 컬럼 없음 → relayFailedEvents로 복구)
                // ⚠️ q-im의 qim.outbox가 next_retry_at 컬럼을 가지면 지수 백오프 적용 가능
                qimJdbcTemplate.update("""
                        UPDATE qim.outbox
                        SET status = 'FAILED',
                            retry_count = retry_count + 1,
                            error_message = ?
                        WHERE event_id = ?
                        """, errorMsg, row.eventId());
                log.warn("[QimKafkaRelayJob] FAILED(재시도 가능): eventId={} retry={}/{}",
                        row.eventId(), nextRetry, maxRetry);
            }
        } catch (Exception dbEx) {
            log.error("[QimKafkaRelayJob] 상태 갱신 실패: eventId={}", row.eventId());
        }
    }

    /**
     * PENDING 레코드 조회 (FOR UPDATE SKIP LOCKED)
     *
     * <p>idem.registry.outbox 스키마 기준:
     * event_id(PK), event_type, partition_key, payload(JSON), status, retry_count
     */
    private List<QimOutboxRow> fetchPending() {
        List<QimOutboxRow> rows = new ArrayList<>();
        try {
            qimJdbcTemplate.query("""
                    SELECT event_id, partition_key, payload, retry_count
                    FROM qim.outbox
                    WHERE status = 'PENDING'
                    ORDER BY created_at ASC
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """, (ResultSet rs) -> {
                rows.add(new QimOutboxRow(
                        rs.getString("event_id"),
                        rs.getString("partition_key"),
                        rs.getString("payload"),
                        rs.getInt("retry_count")
                ));
            }, batchSize);
        } catch (Exception e) {
            log.warn("[QimKafkaRelayJob] PENDING 조회 실패: {}", e.getMessage());
        }
        return rows;
    }

    /**
     * FAILED 레코드 재시도 (별도 주기로 실행)
     *
     * <p>기존 OutboxServiceImpl.relayFailedEvents()와 동일한 역할.
     * maxRetry 미만인 FAILED 레코드를 PENDING으로 복구하여 다음 주기에 재발행.
     */
    @Scheduled(fixedDelayString = "${idem.relay.jobs.registry.kafka.retry-interval-ms:30000}")
    @SchedulerLock(
            name           = "qim-kafka-relay-failed",
            lockAtMostFor  = "60s",
            lockAtLeastFor = "25s"
    )
    @Transactional(transactionManager = "qimTransactionManager")
    public void relayFailed() {
        if (!enabled) return;

        List<String> retryable = new ArrayList<>();
        try {
            qimJdbcTemplate.query("""
                    SELECT event_id
                    FROM qim.outbox
                    WHERE status = 'FAILED'
                      AND retry_count < ?
                    ORDER BY created_at ASC
                    LIMIT 50
                    FOR UPDATE SKIP LOCKED
                    """, (ResultSet rs) -> {
                retryable.add(rs.getString("event_id"));
            }, maxRetry);
        } catch (Exception e) {
            log.warn("[QimKafkaRelayJob] FAILED 조회 실패: {}", e.getMessage());
            return;
        }

        if (retryable.isEmpty()) return;

        log.info("[QimKafkaRelayJob] FAILED → PENDING 복구: {} 건", retryable.size());
        for (String eventId : retryable) {
            try {
                qimJdbcTemplate.update("""
                        UPDATE qim.outbox SET status = 'PENDING' WHERE event_id = ?
                        """, eventId);
            } catch (Exception e) {
                log.error("[QimKafkaRelayJob] PENDING 복구 실패: eventId={}", eventId);
            }
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private record QimOutboxRow(String eventId, String partitionKey, String payload, int retryCount) {}

    private enum RelayResult { SENT, RETRIED, DEAD }
}

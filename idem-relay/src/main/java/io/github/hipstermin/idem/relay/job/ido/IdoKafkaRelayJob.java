package io.github.hipstermin.idem.relay.job.ido;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * idem.hub.outbox Kafka 릴레이 배치 Job
 *
 * <h2>처리 대상</h2>
 * <ul>
 *   <li>idem.hub.outbox 테이블 PENDING 레코드</li>
 *   <li>제외: idem.registry.user.events (→ {@link IdoQimKafkaRelayJob} 전담)</li>
 * </ul>
 *
 * <h2>ShedLock 설정</h2>
 * <pre>
 * lockAtMostFor  = "10s": 비정상 종료 시 10초 후 자동 해제
 * lockAtLeastFor = "400ms": 빠른 완료 후에도 400ms는 다른 Pod 실행 방지
 *                           (배치 주기 500ms 기준, 거의 한 주기를 채움)
 * </pre>
 *
 * <h2>이중 중복 방지</h2>
 * <ol>
 *   <li>ShedLock: 다중 배치 인스턴스 중 하나만 Job 실행</li>
 *   <li>FOR UPDATE SKIP LOCKED: 동일 인스턴스 중복 실행 또는
 *       기존 ido 서비스의 @Scheduled 릴레이와의 경합 방지</li>
 * </ol>
 *
 * <h2>at-least-once 보장</h2>
 * Kafka 발행 성공 → DB PUBLISHED 갱신 실패 시 다음 주기에 재발행.
 * Consumer는 IdempotentEventStore로 중복 방어.
 */
@Slf4j
@Component
public class IdoKafkaRelayJob {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    /** idem.registry.user.events는 IdoQimKafkaRelayJob이 전담 */
    private static final Set<String> EXCLUDED_TOPICS = Set.of("idem.registry.user.events");

    private final JdbcTemplate                     idoJdbcTemplate;
    private final KafkaTemplate<String, Object>    kafkaTemplate;
    private final ObjectMapper                     objectMapper;

    // Micrometer 메트릭
    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter deadLetterCounter;

    @Value("${idem.relay.jobs.hub.kafka.batch-size:100}")
    private int batchSize;

    @Value("${idem.relay.jobs.hub.kafka.max-retry:3}")
    private int maxRetry;

    @Value("${idem.relay.jobs.hub.kafka.enabled:true}")
    private boolean enabled;

    public IdoKafkaRelayJob(
            @Qualifier("idoJdbcTemplate") JdbcTemplate idoJdbcTemplate,
            @Qualifier("batchKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.idoJdbcTemplate = idoJdbcTemplate;
        this.kafkaTemplate   = kafkaTemplate;
        this.objectMapper    = objectMapper;
        this.successCounter     = meterRegistry.counter("idem.relay.jobs.hub.kafka.success");
        this.failureCounter     = meterRegistry.counter("idem.relay.jobs.hub.kafka.failure");
        this.deadLetterCounter  = meterRegistry.counter("idem.relay.jobs.hub.kafka.dead_letter");
    }

    /**
     * idem.hub.outbox PENDING 레코드 Kafka 릴레이
     *
     * <p>{@code @SchedulerLock}: ShedLock이 분산 락을 획득한 단일 인스턴스만 실행.
     * {@code @Transactional}: FOR UPDATE SKIP LOCKED가 TX 내에서만 유효.
     */
    @Scheduled(fixedDelayString = "${idem.relay.jobs.hub.kafka.interval-ms:500}")
    @SchedulerLock(
            name                = "ido-kafka-relay",
            lockAtMostFor       = "${idem.relay.jobs.hub.kafka.lock-at-most:10s}",
            lockAtLeastFor      = "${idem.relay.jobs.hub.kafka.lock-at-least:400ms}"
    )
    @Transactional(transactionManager = "idoTransactionManager")
    public void relay() {
        if (!enabled) {
            log.trace("[IdoKafkaRelayJob] 비활성화 상태 (idem.relay.jobs.hub.kafka.enabled=false)");
            return;
        }

        List<OutboxRow> pending = fetchPending();
        if (pending.isEmpty()) return;

        log.debug("[IdoKafkaRelayJob] PENDING {} 건 처리 시작", pending.size());

        int sent = 0;
        int retried = 0;
        int dead = 0;

        for (OutboxRow row : pending) {
            RelayResult result = dispatchToKafka(row);
            switch (result) {
                case SENT       -> sent++;
                case RETRIED    -> retried++;
                case DEAD       -> dead++;
            }
        }

        if (sent + retried + dead > 0) {
            successCounter.increment(sent);
            failureCounter.increment(retried);
            deadLetterCounter.increment(dead);
            log.info("[IdoKafkaRelayJob] 완료: sent={} retried={} dead={}", sent, retried, dead);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // private
    // ────────────────────────────────────────────────────────────────────

    private RelayResult dispatchToKafka(OutboxRow row) {
        try {
            // payload → Map (타입 무관 — idem_hub.outbox에는 AuthEvent/QIM 이벤트 혼재)
            Map<String, Object> payload = objectMapper.readValue(row.payload(), MAP_TYPE_REF);

            CompletableFuture<SendResult<String, Object>> future =
                    kafkaTemplate.send(row.topic(), row.partitionKey(), payload);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    markPublished(row.eventId(), result);
                } else {
                    handleFailure(row, ex);
                }
            });

            return RelayResult.SENT;

        } catch (Exception e) {
            log.error("[IdoKafkaRelayJob] 역직렬화/발행 예외: eventId={} error={}",
                    row.eventId(), e.getMessage());
            handleFailure(row, e);
            return RelayResult.RETRIED;
        }
    }

    private void markPublished(String eventId, SendResult<String, Object> result) {
        try {
            idoJdbcTemplate.update("""
                    UPDATE idem_hub.outbox
                    SET status = 'PUBLISHED', published_at = NOW()
                    WHERE event_id = ?
                    """, eventId);
            log.debug("[IdoKafkaRelayJob] PUBLISHED: eventId={} partition={} offset={}",
                    eventId, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
        } catch (Exception e) {
            // DB 갱신 실패 → 다음 주기 재발행 (at-least-once)
            log.warn("[IdoKafkaRelayJob] PUBLISHED 갱신 실패 (재발행 예정): eventId={} error={}",
                    eventId, e.getMessage());
        }
    }

    private void handleFailure(OutboxRow row, Throwable ex) {
        try {
            int nextRetry = row.retryCount() + 1;
            if (nextRetry >= maxRetry) {
                idoJdbcTemplate.update("""
                        UPDATE idem_hub.outbox
                        SET status = 'FAILED',
                            error_message = ?,
                            retry_count = retry_count + 1
                        WHERE event_id = ?
                        """, truncate(ex.getMessage(), 2000), row.eventId());
                log.error("[IdoKafkaRelayJob] FAILED(DEAD): eventId={} retry={}/{} error={}",
                        row.eventId(), nextRetry, maxRetry, ex.getMessage());
            } else {
                // 지수 백오프: 2^retryCount 초
                long backoffSec = Math.min((long) Math.pow(2, row.retryCount() + 1), 64L);
                idoJdbcTemplate.update("""
                        UPDATE idem_hub.outbox
                        SET retry_count   = retry_count + 1,
                            error_message = ?,
                            next_retry_at = NOW() + (? || ' seconds')::interval
                        WHERE event_id = ?
                        """, truncate(ex.getMessage(), 2000), backoffSec, row.eventId());
                log.warn("[IdoKafkaRelayJob] PENDING(백오프 {}s): eventId={} retry={}/{}",
                        backoffSec, row.eventId(), nextRetry, maxRetry);
            }
        } catch (Exception dbEx) {
            log.error("[IdoKafkaRelayJob] 상태 갱신 실패: eventId={} dbError={}", row.eventId(), dbEx.getMessage());
        }
    }

    /**
     * PENDING 레코드 배치 조회 (FOR UPDATE SKIP LOCKED)
     *
     * <p>excluded topics (idem.registry.user.events): IN 절로 동적 제외.
     * SKIP LOCKED: 기존 ido 서비스 @Scheduled 릴레이와 경합 방지 (이중 방어).
     */
    private List<OutboxRow> fetchPending() {
        String placeholders = EXCLUDED_TOPICS.stream().map(t -> "?").reduce("", (a, b) ->
                a.isEmpty() ? b : a + ", " + b);

        String sql = """
                SELECT event_id, event_type, partition_key, topic,
                       payload::text, retry_count, next_retry_at
                FROM idem_hub.outbox
                WHERE status = 'PENDING'
                  AND topic NOT IN (%s)
                  AND (next_retry_at IS NULL OR next_retry_at <= NOW())
                ORDER BY created_at ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """.formatted(EXCLUDED_TOPICS.stream().map(t -> "?")
                .reduce((a, b) -> a + ", " + b).orElse("''"));

        Object[] params = new Object[EXCLUDED_TOPICS.size() + 1];
        int i = 0;
        for (String t : EXCLUDED_TOPICS) params[i++] = t;
        params[i] = batchSize;

        List<OutboxRow> rows = new ArrayList<>();
        try {
            idoJdbcTemplate.query(sql, (ResultSet rs) -> {
                rows.add(new OutboxRow(
                        rs.getString("event_id"),
                        rs.getString("event_type"),
                        rs.getString("partition_key"),
                        rs.getString("topic"),
                        rs.getString("payload"),
                        rs.getInt("retry_count")
                ));
            }, params);
        } catch (Exception e) {
            log.warn("[IdoKafkaRelayJob] PENDING 조회 실패 (비치명적): {}", e.getMessage());
        }
        return rows;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    // ────────────────────────────────────────────────────────────────────
    // Inner types
    // ────────────────────────────────────────────────────────────────────

    private record OutboxRow(
            String eventId,
            String eventType,
            String partitionKey,
            String topic,
            String payload,
            int    retryCount
    ) {}

    private enum RelayResult { SENT, RETRIED, DEAD }
}

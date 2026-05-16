package kr.go.smes.batch.job.qsign;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * qsign.outbox → qsign.auth.events Kafka 릴레이 배치 Job
 *
 * <h2>대상</h2>
 * Q-Sign 서비스(PostgreSQL)의 qsign.outbox 테이블 PENDING 레코드.
 * 기존 {@code OutboxRelay#relay()}를 대체.
 *
 * <h2>payload 처리</h2>
 * 기존 {@code OutboxRelay}는 payload를 {@code AuthEvent}로 역직렬화했으나,
 * 배치 서비스는 도메인 의존성을 최소화하기 위해 {@code Map<String, Object>}로 처리.
 * Consumer({@code QsignAuthEventConsumer})는 이미 eventType 기반 분기를 지원하므로 호환됨.
 *
 * <h2>성능</h2>
 * qsign.outbox는 인증 세션 이벤트로 트래픽이 높을 수 있음.
 * batch-size 기본값 100, interval 500ms → 초당 최대 200건 처리.
 * 60,000명 급증 시나리오에서도 약 5분 내 처리 완료.
 */
@Slf4j
@Component
public class QSignKafkaRelayJob {

    private static final String AUTH_EVENTS_TOPIC = "qsign.auth.events";

    private final JdbcTemplate                  qsignJdbcTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper                  objectMapper;

    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter deadLetterCounter;

    @Value("${batch.relay.qsign.kafka.batch-size:100}")
    private int batchSize;

    @Value("${batch.relay.qsign.kafka.max-retry:3}")
    private int maxRetry;

    @Value("${batch.relay.qsign.kafka.enabled:true}")
    private boolean enabled;

    @Value("${batch.relay.qsign.kafka.topic:qsign.auth.events}")
    private String authEventsTopic;

    public QSignKafkaRelayJob(
            @Qualifier("qsignJdbcTemplate") JdbcTemplate qsignJdbcTemplate,
            @Qualifier("batchKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.qsignJdbcTemplate = qsignJdbcTemplate;
        this.kafkaTemplate     = kafkaTemplate;
        this.objectMapper      = objectMapper;
        this.successCounter    = meterRegistry.counter("batch.relay.qsign.kafka.success");
        this.failureCounter    = meterRegistry.counter("batch.relay.qsign.kafka.failure");
        this.deadLetterCounter = meterRegistry.counter("batch.relay.qsign.kafka.dead_letter");
    }

    @Scheduled(fixedDelayString = "${batch.relay.qsign.kafka.interval-ms:500}")
    @SchedulerLock(
            name           = "qsign-kafka-relay",
            lockAtMostFor  = "${batch.relay.qsign.kafka.lock-at-most:10s}",
            lockAtLeastFor = "${batch.relay.qsign.kafka.lock-at-least:400ms}"
    )
    @Transactional(transactionManager = "qsignTransactionManager")
    public void relay() {
        if (!enabled) return;

        List<QSignOutboxRow> pending = fetchPending();
        if (pending.isEmpty()) return;

        log.debug("[QSignKafkaRelayJob] PENDING {} 건 발행 시작", pending.size());

        int sent = 0;
        int retried = 0;
        int dead = 0;

        for (QSignOutboxRow row : pending) {
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
            log.info("[QSignKafkaRelayJob] 완료: sent={} retried={} dead={}", sent, retried, dead);
        }
    }

    private RelayResult dispatch(QSignOutboxRow row) {
        try {
            // payload → Map (AuthEvent 구조를 Map으로 유지하여 Consumer 호환성 보장)
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(row.payload(), Map.class);

            CompletableFuture<SendResult<String, Object>> future =
                    kafkaTemplate.send(authEventsTopic, row.partitionKey(), payload);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    markPublished(row.eventId(), result);
                } else {
                    handleFailure(row, ex);
                }
            });

            return RelayResult.SENT;

        } catch (Exception e) {
            log.error("[QSignKafkaRelayJob] 발행 예외: eventId={} error={}", row.eventId(), e.getMessage());
            handleFailure(row, e);
            return RelayResult.RETRIED;
        }
    }

    private void markPublished(String eventId, SendResult<String, Object> result) {
        try {
            qsignJdbcTemplate.update("""
                    UPDATE qsign.outbox
                    SET status = 'PUBLISHED', published_at = NOW()
                    WHERE event_id = ?
                    """, eventId);
            log.debug("[QSignKafkaRelayJob] PUBLISHED: eventId={} partition={} offset={}",
                    eventId, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
        } catch (Exception e) {
            log.warn("[QSignKafkaRelayJob] PUBLISHED 갱신 실패 (at-least-once 재발행): eventId={}", eventId);
        }
    }

    private void handleFailure(QSignOutboxRow row, Throwable ex) {
        try {
            int nextRetry = row.retryCount() + 1;
            String errorMsg = truncate(ex.getMessage(), 2000);

            if (nextRetry >= maxRetry) {
                qsignJdbcTemplate.update("""
                        UPDATE qsign.outbox
                        SET status = 'FAILED', error_message = ?
                        WHERE event_id = ?
                        """, errorMsg, row.eventId());
                deadLetterCounter.increment();
                log.error("[QSignKafkaRelayJob] FAILED(영구): eventId={} retry={}/{} error={}",
                        row.eventId(), nextRetry, maxRetry, errorMsg);
            } else {
                // qsign.outbox는 next_retry_at 컬럼 없음 → incrementRetry만 수행
                // (기존 QSignOutboxRepository.incrementRetry()와 동일 동작)
                qsignJdbcTemplate.update("""
                        UPDATE qsign.outbox
                        SET retry_count = retry_count + 1, error_message = ?
                        WHERE event_id = ?
                        """, errorMsg, row.eventId());
                log.warn("[QSignKafkaRelayJob] 재시도 예약: eventId={} retry={}/{}",
                        row.eventId(), nextRetry, maxRetry);
            }
        } catch (Exception dbEx) {
            log.error("[QSignKafkaRelayJob] 상태 갱신 실패: eventId={}", row.eventId());
        }
    }

    private List<QSignOutboxRow> fetchPending() {
        List<QSignOutboxRow> rows = new ArrayList<>();
        try {
            qsignJdbcTemplate.query("""
                    SELECT event_id, partition_key, payload::text, retry_count
                    FROM qsign.outbox
                    WHERE status = 'PENDING'
                    ORDER BY created_at ASC
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """, (ResultSet rs) -> {
                rows.add(new QSignOutboxRow(
                        rs.getString("event_id"),
                        rs.getString("partition_key"),
                        rs.getString("payload"),
                        rs.getInt("retry_count")
                ));
            }, batchSize);
        } catch (Exception e) {
            log.warn("[QSignKafkaRelayJob] PENDING 조회 실패: {}", e.getMessage());
        }
        return rows;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private record QSignOutboxRow(String eventId, String partitionKey, String payload, int retryCount) {}

    private enum RelayResult { SENT, RETRIED, DEAD }
}

package io.github.hipstermin.idem.relay.job.ido;

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
 * idem.hub.outbox → idem.registry.user.events Kafka 릴레이 배치 Job
 *
 * <h2>배경</h2>
 * idem.hub.outbox 테이블에는 Q-IM SP 수신 이벤트({@code BIZ_MEMBER_CONVERTED} 등)도
 * idem.registry.user.events 토픽으로 INSERT된다.
 * 기존에는 {@code QimOutboxRelay}(ido 서비스 내부)가 전담했으나,
 * 이 Job이 배치 서비스에서 독립적으로 처리한다.
 *
 * <h2>ShedLock 락 이름 분리</h2>
 * {@code IdoKafkaRelayJob}과 다른 락 이름({@code "ido-qim-kafka-relay"})을 사용하여
 * 두 Job이 동시에 실행 가능하도록 설계.
 * (ido.outbox를 FOR UPDATE SKIP LOCKED로 파티셔닝하므로 실제 레코드 충돌 없음)
 *
 * <h2>주기</h2>
 * 기본 1000ms — Q-IM 이벤트는 Kafka Auth 이벤트보다 처리 지연이 허용 가능.
 */
@Slf4j
@Component
public class IdoQimKafkaRelayJob {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};
    private static final String QIM_TOPIC = "idem.registry.user.events";

    private final JdbcTemplate                  idoJdbcTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper                  objectMapper;

    private final Counter successCounter;
    private final Counter failureCounter;

    @Value("${idem.relay.jobs.hub.qim.batch-size:50}")
    private int batchSize;

    @Value("${idem.relay.jobs.hub.qim.max-retry:5}")
    private int maxRetry;

    @Value("${idem.relay.jobs.hub.qim.enabled:true}")
    private boolean enabled;

    public IdoQimKafkaRelayJob(
            @Qualifier("idoJdbcTemplate") JdbcTemplate idoJdbcTemplate,
            @Qualifier("batchKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.idoJdbcTemplate = idoJdbcTemplate;
        this.kafkaTemplate   = kafkaTemplate;
        this.objectMapper    = objectMapper;
        this.successCounter  = meterRegistry.counter("idem.relay.jobs.hub.qim.success");
        this.failureCounter  = meterRegistry.counter("idem.relay.jobs.hub.qim.failure");
    }

    @Scheduled(fixedDelayString = "${idem.relay.jobs.hub.qim.interval-ms:1000}")
    @SchedulerLock(
            name           = "ido-qim-kafka-relay",
            lockAtMostFor  = "${idem.relay.jobs.hub.qim.lock-at-most:15s}",
            lockAtLeastFor = "${idem.relay.jobs.hub.qim.lock-at-least:800ms}"
    )
    @Transactional(transactionManager = "idoTransactionManager")
    public void relay() {
        if (!enabled) return;

        List<OutboxRow> pending = fetchPendingByTopic();
        if (pending.isEmpty()) return;

        log.debug("[IdoQimKafkaRelayJob] idem.registry.user.events PENDING {} 건", pending.size());

        int sent = 0;
        for (OutboxRow row : pending) {
            if (dispatch(row)) sent++;
        }

        successCounter.increment(sent);
        failureCounter.increment(pending.size() - sent);

        if (pending.size() > 0) {
            log.info("[IdoQimKafkaRelayJob] 완료: sent={}/{}", sent, pending.size());
        }
    }

    private boolean dispatch(OutboxRow row) {
        try {
            Map<String, Object> payload = objectMapper.readValue(row.payload(), MAP_TYPE_REF);
            CompletableFuture<SendResult<String, Object>> future =
                    kafkaTemplate.send(QIM_TOPIC, row.partitionKey(), payload);
            future.whenComplete((r, ex) -> {
                if (ex == null) {
                    markPublished(row.eventId(), r);
                } else {
                    handleFailure(row, ex);
                }
            });
            return true;
        } catch (Exception e) {
            log.error("[IdoQimKafkaRelayJob] 발행 예외: eventId={} error={}", row.eventId(), e.getMessage());
            handleFailure(row, e);
            return false;
        }
    }

    private void markPublished(String eventId, SendResult<String, Object> result) {
        try {
            idoJdbcTemplate.update("""
                    UPDATE ido.outbox
                    SET status = 'PUBLISHED', published_at = NOW()
                    WHERE event_id = ?
                    """, eventId);
        } catch (Exception e) {
            log.warn("[IdoQimKafkaRelayJob] PUBLISHED 갱신 실패: eventId={}", eventId);
        }
    }

    private void handleFailure(OutboxRow row, Throwable ex) {
        try {
            int nextRetry = row.retryCount() + 1;
            if (nextRetry >= maxRetry) {
                idoJdbcTemplate.update("""
                        UPDATE ido.outbox
                        SET status = 'FAILED', error_message = ?, retry_count = retry_count + 1
                        WHERE event_id = ?
                        """, truncate(ex.getMessage(), 2000), row.eventId());
            } else {
                long backoffSec = Math.min((long) Math.pow(2, row.retryCount() + 1), 64L);
                idoJdbcTemplate.update("""
                        UPDATE ido.outbox
                        SET retry_count = retry_count + 1,
                            error_message = ?,
                            next_retry_at = NOW() + (? || ' seconds')::interval
                        WHERE event_id = ?
                        """, truncate(ex.getMessage(), 2000), backoffSec, row.eventId());
            }
        } catch (Exception dbEx) {
            log.error("[IdoQimKafkaRelayJob] 상태 갱신 실패: eventId={}", row.eventId());
        }
    }

    private List<OutboxRow> fetchPendingByTopic() {
        List<OutboxRow> rows = new ArrayList<>();
        try {
            idoJdbcTemplate.query("""
                    SELECT event_id, partition_key, payload::text, retry_count
                    FROM ido.outbox
                    WHERE status = 'PENDING'
                      AND topic = ?
                      AND (next_retry_at IS NULL OR next_retry_at <= NOW())
                    ORDER BY created_at ASC
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """, (ResultSet rs) -> {
                rows.add(new OutboxRow(
                        rs.getString("event_id"),
                        rs.getString("partition_key"),
                        rs.getString("payload"),
                        rs.getInt("retry_count")
                ));
            }, QIM_TOPIC, batchSize);
        } catch (Exception e) {
            log.warn("[IdoQimKafkaRelayJob] 조회 실패: {}", e.getMessage());
        }
        return rows;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private record OutboxRow(String eventId, String partitionKey, String payload, int retryCount) {}
}

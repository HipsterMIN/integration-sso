package kr.go.smes.batch.job.authz;

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
 * authz.authz_outbox → authz.assignment.events Kafka 릴레이 배치 Job
 *
 * <h2>대상</h2>
 * q-authz 서비스(PostgreSQL)의 authz.authz_outbox 테이블 PENDING 레코드.
 * 인가 부여/회수/만료(AUTHZ_GRANTED/REVOKED/EXPIRED) 이벤트를 발행하여
 * 다운스트림(기관 게이트웨이·세션 캐시·ido)이 <b>역할 회수를 토큰 만료
 * 이전에 전파</b>할 수 있게 한다.
 *
 * <h2>동시성</h2>
 * FOR UPDATE SKIP LOCKED(배치 인스턴스 간) + ShedLock(다중 Pod 간) 이중 보호.
 * q-authz 인-프로세스 만료 스케줄러는 EXPIRED 전이만 담당하고 발행은 본 Job이
 * 단일 리더로 수행하므로 중복 발행이 발생하지 않는다.
 *
 * <h2>payload 처리</h2>
 * 도메인 의존성 최소화를 위해 payload를 {@code Map<String, Object>}로 처리한다.
 * (authz.authz_outbox.payload 컬럼은 text — JSON 문자열)
 */
@Slf4j
@Component
public class AuthzKafkaRelayJob {

    private final JdbcTemplate                  authzJdbcTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper                  objectMapper;

    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter deadLetterCounter;

    @Value("${batch.relay.authz.kafka.batch-size:100}")
    private int batchSize;

    @Value("${batch.relay.authz.kafka.max-retry:5}")
    private int maxRetry;

    @Value("${batch.relay.authz.kafka.enabled:true}")
    private boolean enabled;

    @Value("${batch.relay.authz.kafka.topic:authz.assignment.events}")
    private String assignmentEventsTopic;

    public AuthzKafkaRelayJob(
            @Qualifier("authzJdbcTemplate") JdbcTemplate authzJdbcTemplate,
            @Qualifier("batchKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.authzJdbcTemplate = authzJdbcTemplate;
        this.kafkaTemplate     = kafkaTemplate;
        this.objectMapper      = objectMapper;
        this.successCounter    = meterRegistry.counter("batch.relay.authz.kafka.success");
        this.failureCounter    = meterRegistry.counter("batch.relay.authz.kafka.failure");
        this.deadLetterCounter = meterRegistry.counter("batch.relay.authz.kafka.dead_letter");
    }

    @Scheduled(fixedDelayString = "${batch.relay.authz.kafka.interval-ms:500}")
    @SchedulerLock(
            name           = "authz-kafka-relay",
            lockAtMostFor  = "${batch.relay.authz.kafka.lock-at-most:10s}",
            lockAtLeastFor = "${batch.relay.authz.kafka.lock-at-least:400ms}"
    )
    @Transactional(transactionManager = "authzTransactionManager")
    public void relay() {
        if (!enabled) return;

        List<AuthzOutboxRow> pending = fetchPending();
        if (pending.isEmpty()) return;

        log.debug("[AuthzKafkaRelayJob] PENDING {} 건 발행 시작", pending.size());

        int sent = 0;
        int retried = 0;
        int dead = 0;

        for (AuthzOutboxRow row : pending) {
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
            log.info("[AuthzKafkaRelayJob] 완료: sent={} retried={} dead={}", sent, retried, dead);
        }
    }

    private RelayResult dispatch(AuthzOutboxRow row) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(row.payload(), Map.class);

            CompletableFuture<SendResult<String, Object>> future =
                    kafkaTemplate.send(assignmentEventsTopic, row.partitionKey(), payload);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    markPublished(row.eventId());
                } else {
                    handleFailure(row, ex);
                }
            });

            return RelayResult.SENT;

        } catch (Exception e) {
            log.error("[AuthzKafkaRelayJob] 발행 예외: eventId={} error={}", row.eventId(), e.getMessage());
            handleFailure(row, e);
            return RelayResult.RETRIED;
        }
    }

    private void markPublished(String eventId) {
        try {
            authzJdbcTemplate.update("""
                    UPDATE authz.authz_outbox
                    SET status = 'PUBLISHED', published_at = NOW()
                    WHERE event_id = ?
                    """, eventId);
        } catch (Exception e) {
            log.warn("[AuthzKafkaRelayJob] PUBLISHED 갱신 실패 (at-least-once 재발행): eventId={}", eventId);
        }
    }

    private void handleFailure(AuthzOutboxRow row, Throwable ex) {
        try {
            int nextRetry = row.retryCount() + 1;
            String errorMsg = truncate(ex.getMessage(), 2000);

            if (nextRetry >= maxRetry) {
                authzJdbcTemplate.update("""
                        UPDATE authz.authz_outbox
                        SET status = 'FAILED', retry_count = retry_count + 1, error_message = ?
                        WHERE event_id = ?
                        """, errorMsg, row.eventId());
                deadLetterCounter.increment();
                log.error("[AuthzKafkaRelayJob] FAILED(영구): eventId={} retry={}/{} error={}",
                        row.eventId(), nextRetry, maxRetry, errorMsg);
            } else {
                authzJdbcTemplate.update("""
                        UPDATE authz.authz_outbox
                        SET retry_count = retry_count + 1, error_message = ?
                        WHERE event_id = ?
                        """, errorMsg, row.eventId());
                log.warn("[AuthzKafkaRelayJob] 재시도 예약: eventId={} retry={}/{}",
                        row.eventId(), nextRetry, maxRetry);
            }
        } catch (Exception dbEx) {
            log.error("[AuthzKafkaRelayJob] 상태 갱신 실패: eventId={}", row.eventId());
        }
    }

    private List<AuthzOutboxRow> fetchPending() {
        List<AuthzOutboxRow> rows = new ArrayList<>();
        try {
            authzJdbcTemplate.query("""
                    SELECT event_id, partition_key, payload, retry_count
                    FROM authz.authz_outbox
                    WHERE status = 'PENDING'
                    ORDER BY created_at ASC
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """, (ResultSet rs) -> {
                rows.add(new AuthzOutboxRow(
                        rs.getString("event_id"),
                        rs.getString("partition_key"),
                        rs.getString("payload"),
                        rs.getInt("retry_count")
                ));
            }, batchSize);
        } catch (Exception e) {
            log.warn("[AuthzKafkaRelayJob] PENDING 조회 실패: {}", e.getMessage());
        }
        return rows;
    }

    /**
     * FAILED(retry_count &lt; maxRetry) 레코드를 PENDING으로 복구해 재발행 기회 제공.
     */
    @Scheduled(fixedDelayString = "${batch.relay.authz.kafka.retry-interval-ms:30000}")
    @SchedulerLock(
            name           = "authz-kafka-relay-failed",
            lockAtMostFor  = "60s",
            lockAtLeastFor = "25s"
    )
    @Transactional(transactionManager = "authzTransactionManager")
    public void relayFailed() {
        if (!enabled) return;

        List<String> retryable = new ArrayList<>();
        try {
            authzJdbcTemplate.query("""
                    SELECT event_id
                    FROM authz.authz_outbox
                    WHERE status = 'FAILED'
                      AND retry_count < ?
                    ORDER BY created_at ASC
                    LIMIT 50
                    FOR UPDATE SKIP LOCKED
                    """, (ResultSet rs) -> retryable.add(rs.getString("event_id")), maxRetry);
        } catch (Exception e) {
            log.warn("[AuthzKafkaRelayJob] FAILED 조회 실패: {}", e.getMessage());
            return;
        }

        if (retryable.isEmpty()) return;

        log.info("[AuthzKafkaRelayJob] FAILED → PENDING 복구: {} 건", retryable.size());
        for (String eventId : retryable) {
            try {
                authzJdbcTemplate.update(
                        "UPDATE authz.authz_outbox SET status = 'PENDING' WHERE event_id = ?", eventId);
            } catch (Exception e) {
                log.error("[AuthzKafkaRelayJob] PENDING 복구 실패: eventId={}", eventId);
            }
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private record AuthzOutboxRow(String eventId, String partitionKey, String payload, int retryCount) {}

    private enum RelayResult { SENT, RETRIED, DEAD }
}

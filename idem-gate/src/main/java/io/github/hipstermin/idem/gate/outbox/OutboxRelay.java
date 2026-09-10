package io.github.hipstermin.idem.gate.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.event.AuthEvent;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Q-Sign Transactional Outbox Relay
 * 설계서 §9.3 Outbox 패턴 — DB 커밋 후 Kafka 발행 보장
 *
 * <p>동작 방식:
 * <ol>
 *   <li>PENDING 상태 outbox 레코드를 배치 조회</li>
 *   <li>Kafka 발행 (partitionKey = identifierHash)</li>
 *   <li>성공 시 PUBLISHED, 실패 시 retry_count 증가 → 임계치 초과 시 FAILED</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final QSignOutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> qsignKafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${qsign.outbox.batch-size:100}")
    private int batchSize;

    @Value("${qsign.outbox.max-retry:3}")
    private int maxRetry;

    @Value("${qsign.kafka.topic-auth-events:qsign.auth.events}")
    private String authEventsTopic;

    /**
     * 500ms 마다 PENDING 이벤트 발행
     * §9.3 Outbox Relay: at-least-once 보장, 멱등 컨슈머가 중복 처리
     */
    @Scheduled(fixedDelayString = "${qsign.outbox.relay-interval-ms:500}")
    @Transactional
    public void relay() {
        List<QSignOutboxRecord> pending = outboxRepository.findPendingBatch(batchSize);
        if (pending.isEmpty()) {
            return;
        }

        log.debug("[OutboxRelay] PENDING 이벤트 {} 건 발행 시작", pending.size());

        for (QSignOutboxRecord record : pending) {
            try {
                Object payload = objectMapper.readValue(record.getPayload(), AuthEvent.class);

                CompletableFuture<SendResult<String, Object>> future =
                        qsignKafkaTemplate.send(
                                authEventsTopic,
                                record.getPartitionKey(),   // identifierHash
                                payload
                        );

                future.whenComplete((result, ex) -> {
                    if (ex != null) {
                        handleFailure(record, ex);
                    } else {
                        handleSuccess(record, result);
                    }
                });

            } catch (Exception e) {
                log.error("[OutboxRelay] 이벤트 직렬화 실패: eventId={}", record.getEventId(), e);
                handleFailure(record, e);
            }
        }
    }

    private void handleSuccess(QSignOutboxRecord record, SendResult<String, Object> result) {
        outboxRepository.markPublished(record.getEventId());
        log.debug("[OutboxRelay] 발행 완료: eventId={}, partition={}, offset={}",
                record.getEventId(),
                result.getRecordMetadata().partition(),
                result.getRecordMetadata().offset());
    }

    private void handleFailure(QSignOutboxRecord record, Throwable ex) {
        int nextRetry = record.getRetryCount() + 1;
        if (nextRetry >= maxRetry) {
            outboxRepository.markFailed(record.getEventId(), ex.getMessage());
            log.error("[OutboxRelay] 최대 재시도 초과 → FAILED: eventId={}", record.getEventId(), ex);
        } else {
            outboxRepository.incrementRetry(record.getEventId(), ex.getMessage());
            log.warn("[OutboxRelay] 발행 실패 (retry={}/{}): eventId={}",
                    nextRetry, maxRetry, record.getEventId(), ex);
        }
    }
}

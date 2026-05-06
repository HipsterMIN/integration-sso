package kr.go.smes.ido.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.common.event.AuthEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * IdO Transactional Outbox Relay — ido.outbox PENDING 이벤트 재발행 (문서 §P0-2)
 *
 * <p>문제 배경:
 * {@code KeycloakOidcService}와 {@code NonOidcAuthService} 모두
 * Kafka 즉시 발행 실패 시 {@code ido.outbox}에 PENDING 레코드를 남기지만,
 * q-sign의 {@code OutboxRelay}는 {@code qsign.outbox}만 읽는다.
 * 이 클래스가 없으면 {@code ido.outbox} PENDING 레코드는 영구 미처리된다.
 *
 * <p>동작 방식 (q-sign {@code OutboxRelay}와 동일한 Transactional Outbox 패턴):
 * <ol>
 *   <li>PENDING 상태 레코드를 {@code created_at ASC + FOR UPDATE SKIP LOCKED}로 배치 조회</li>
 *   <li>각 레코드의 {@code topic} 필드에 명시된 Kafka 토픽으로 비동기 발행</li>
 *   <li>발행 성공 → {@code PUBLISHED} 상태로 갱신 + {@code published_at} 기록</li>
 *   <li>발행 실패 → {@code retry_count} 증가, 최대 재시도 초과 시 {@code FAILED}</li>
 * </ol>
 *
 * <p>at-least-once 보장:
 * DB 상태 갱신은 발행 결과 콜백에서 수행하므로 Kafka 발행 성공 후 DB 갱신 실패 시
 * 다음 사이클에서 재발행될 수 있다. 하위 컨슈머({@code QsignAuthEventConsumer})는
 * {@code IdempotentEventStore}를 통해 중복 처리를 방어한다.
 *
 * <p>설정 키:
 * <ul>
 *   <li>{@code ido.outbox.relay-interval-ms} — 스케줄 주기 (기본 500ms)</li>
 *   <li>{@code ido.outbox.batch-size}         — 배치 크기 (기본 100)</li>
 *   <li>{@code ido.outbox.max-retry}          — 최대 재시도 횟수 (기본 3)</li>
 * </ul>
 *
 * <p>{@code @EnableScheduling}은 {@code IdoApplication} + {@code IdoWebConfig}에
 * 이미 선언되어 있어 별도 설정 불필요.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdoOutboxRelay {

    private final IdoOutboxRepository               outboxRepository;
    private final KafkaTemplate<String, Object>     kafkaTemplate;
    private final ObjectMapper                       objectMapper;

    @Value("${ido.outbox.batch-size:100}")
    private int batchSize;

    @Value("${ido.outbox.max-retry:3}")
    private int maxRetry;

    /**
     * PENDING 이벤트 Kafka 재발행 스케줄러
     *
     * <p>{@code fixedDelayString}: 이전 실행 완료 후 지정 시간만큼 대기.
     * {@code fixedRate}가 아닌 {@code fixedDelay}를 사용하여
     * 처리 지연 시 중복 실행을 방지.
     *
     * <p>트랜잭션: SELECT … FOR UPDATE SKIP LOCKED 는 트랜잭션 내에서만 동작.
     * 상태 갱신(markPublished/markFailed)은 Kafka 비동기 콜백에서 수행하므로
     * 여기서는 SELECT 범위만 트랜잭션으로 묶음.
     */
    @Scheduled(fixedDelayString = "${ido.outbox.relay-interval-ms:500}")
    @Transactional
    public void relay() {
        List<IdoOutboxRecord> pending = outboxRepository.findPendingBatch(batchSize);
        if (pending.isEmpty()) {
            return;
        }

        log.debug("[IdoOutboxRelay] PENDING 이벤트 {} 건 발행 시작", pending.size());

        for (IdoOutboxRecord record : pending) {
            try {
                // payload JSON → AuthEvent 역직렬화
                Object payload = objectMapper.readValue(record.getPayload(), AuthEvent.class);

                // topic 필드에 기록된 토픽으로 발행 (qsign.auth.events 등)
                CompletableFuture<SendResult<String, Object>> future =
                        kafkaTemplate.send(
                                record.getTopic(),
                                record.getPartitionKey(),   // identifierHash (파티션 키)
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
                log.error("[IdoOutboxRelay] 이벤트 직렬화 실패: eventId={} error={}",
                        record.getEventId(), e.getMessage());
                handleFailure(record, e);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 성공 / 실패 콜백
    // ──────────────────────────────────────────────────────────────────────

    private void handleSuccess(IdoOutboxRecord record, SendResult<String, Object> result) {
        try {
            outboxRepository.markPublished(record.getEventId());
            log.debug("[IdoOutboxRelay] 발행 완료: eventId={} topic={} partition={} offset={}",
                    record.getEventId(),
                    record.getTopic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
        } catch (Exception e) {
            // DB 갱신 실패 → 다음 사이클에서 재발행 (at-least-once)
            log.warn("[IdoOutboxRelay] PUBLISHED 갱신 실패 (재발행 예정): eventId={}", record.getEventId(), e);
        }
    }

    private void handleFailure(IdoOutboxRecord record, Throwable ex) {
        try {
            int nextRetry = record.getRetryCount() + 1;
            if (nextRetry >= maxRetry) {
                outboxRepository.markFailed(record.getEventId(), ex.getMessage());
                log.error("[IdoOutboxRelay] 최대 재시도 초과 → FAILED: eventId={} retry={}/{}",
                        record.getEventId(), nextRetry, maxRetry, ex);
            } else {
                outboxRepository.incrementRetry(record.getEventId(), ex.getMessage());
                log.warn("[IdoOutboxRelay] 발행 실패 (retry={}/{}): eventId={}",
                        nextRetry, maxRetry, record.getEventId(), ex);
            }
        } catch (Exception dbEx) {
            log.error("[IdoOutboxRelay] 실패 상태 갱신 실패: eventId={}", record.getEventId(), dbEx);
        }
    }
}

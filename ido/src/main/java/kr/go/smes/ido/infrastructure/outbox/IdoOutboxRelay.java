package kr.go.smes.ido.infrastructure.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
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
 * <p><b>payload 역직렬화 전략 (QIM-OUTBOX-SPEC-001 대응)</b>:
 * 이전에는 {@code AuthEvent} 타입으로 고정 역직렬화하였으나,
 * {@code QimSpReceiverService}가 {@code BIZ_MEMBER_CONVERTED} 등 QIM 이벤트를
 * 동일한 {@code ido.outbox}에 INSERT하면서 타입 불일치({@code ClassCastException}) 위험이
 * 발생하였다.
 *
 * <p>해결책: payload를 {@code Map<String, Object>}로 역직렬화한 뒤 Kafka로 발행.
 * Kafka Producer는 {@code JsonSerializer}를 사용하므로 Map이 그대로 JSON으로 직렬화된다.
 * Consumer 쪽에서는 {@code eventType} 필드를 기준으로 처리 분기하면 되며,
 * 타입 헤더({@code ADD_TYPE_INFO_HEADERS=false})를 끄고 있으므로 타입 충돌이 없다.
 *
 * <p>at-least-once 보장:
 * DB 상태 갱신은 발행 결과 콜백에서 수행하므로 Kafka 발행 성공 후 DB 갱신 실패 시
 * 다음 사이클에서 재발행될 수 있다. 하위 컨슈머({@code QsignAuthEventConsumer},
 * {@code QimSpMemberEventConsumer})는 {@code IdempotentEventStore}를 통해 중복 처리를 방어한다.
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

    /** payload JSON → Map 역직렬화에 사용하는 TypeReference */
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF =
            new TypeReference<>() {};

    private final IdoOutboxRepository               outboxRepository;
    private final KafkaTemplate<String, Object>     kafkaTemplate;
    private final ObjectMapper                       objectMapper;

    // F-13: Outbox Relay On/Off (IDO_OUTBOX_RELAY_ENABLED)
    // false → @Scheduled 실행되어도 즉시 return, DB 500ms 폴링 없음
    // Kafka 없는 로컬 환경에서 연결 오류 없이 실행 가능
    @Value("${ido.outbox.relay-enabled:${IDO_OUTBOX_RELAY_ENABLED:true}}")
    private boolean relayEnabled;

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
     * <p>트랜잭션 경계 주의:
     * {@code @Transactional}은 {@code FOR UPDATE SKIP LOCKED} 잠금 유지를 위해 필요하다.
     * 단, Kafka 발행 결과 콜백({@code whenComplete})은 비동기로 TX 커밋 이후에 실행되므로
     * {@code markPublished/markFailed} 호출은 별도 트랜잭션으로 처리된다.
     * 이는 at-least-once 보장 설계 원칙과 일치한다.
     */
    /**
     * 이 릴레이가 처리하지 않을 토픽 목록.
     * qim.user.events는 {@link QimOutboxRelay}가 전담하므로 제외.
     */
    private static final java.util.Set<String> EXCLUDED_TOPICS =
            java.util.Set.of("qim.user.events");

    @Scheduled(fixedDelayString = "${ido.outbox.relay-interval-ms:500}")
    @Transactional
    public void relay() {
        // F-13 Guard
        if (!relayEnabled) {
            log.trace("[IdoOutboxRelay] DISABLED (IDO_OUTBOX_RELAY_ENABLED=false)");
            return;
        }
        List<IdoOutboxRecord> pending = outboxRepository.findPendingBatchExcludingTopics(
                EXCLUDED_TOPICS, batchSize);
        if (pending.isEmpty()) {
            return;
        }

        log.debug("[IdoOutboxRelay] PENDING 이벤트 {} 건 발행 시작", pending.size());

        for (IdoOutboxRecord record : pending) {
            try {
                // ── payload 역직렬화: Map<String, Object> (타입 무관, 범용 처리) ──
                // 이유: ido.outbox에는 AuthEvent(qsign.auth.events) 외에
                //        QIM 이벤트(qim.user.events, BIZ_MEMBER_CONVERTED 등)도 INSERT됨.
                //        AuthEvent 고정 역직렬화 시 ClassCastException/JsonMappingException 발생.
                //        Map으로 역직렬화하면 어떤 이벤트 타입이든 JSON 구조가 유지되어
                //        Consumer가 eventType 필드를 기준으로 처리 분기 가능.
                Map<String, Object> payload = objectMapper.readValue(record.getPayload(), MAP_TYPE_REF);

                // topic 필드에 기록된 토픽으로 발행 (qsign.auth.events, qim.user.events 등)
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
                log.error("[IdoOutboxRelay] 이벤트 역직렬화/발행 실패: eventId={} eventType={} error={}",
                        record.getEventId(), record.getEventType(), e.getMessage());
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
                log.error("[IdoOutboxRelay] 최대 재시도 초과 → FAILED: eventId={} eventType={} retry={}/{}",
                        record.getEventId(), record.getEventType(), nextRetry, maxRetry, ex);
            } else {
                // 지수 백오프: 2^currentRetry 초 후 재시도 (Thundering Herd 방지)
                outboxRepository.incrementRetryWithBackoff(
                        record.getEventId(), ex.getMessage(), record.getRetryCount());
                log.warn("[IdoOutboxRelay] 발행 실패 → 백오프 재예약 (retry={}/{}): eventId={} eventType={}",
                        nextRetry, maxRetry, record.getEventId(), record.getEventType(), ex);
            }
        } catch (Exception dbEx) {
            log.error("[IdoOutboxRelay] 실패 상태 갱신 실패: eventId={}", record.getEventId(), dbEx);
        }
    }
}

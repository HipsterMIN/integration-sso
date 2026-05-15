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
 * Q-IM 회원 이벤트 전용 Outbox Relay — qim.user.events PENDING 이벤트 Kafka 발행
 *
 * <h2>도입 배경 (QIM-OUTBOX-SPEC-001)</h2>
 * <p>Q-IM / Q-Sign 은 Kafka Producer 로직을 직접 구현하기 어려운 상황에서
 * <b>Outbox 테이블에 INSERT만 수행</b>하고, IdO 측 폴링 스케줄러가 대신 Kafka 발행을 담당하는
 * 방식을 채택하였다.
 *
 * <p>이 클래스는 {@code ido.outbox}에 INSERT된 {@code qim.user.events} 토픽 대상
 * PENDING 레코드를 폴링하여 Kafka로 발행하는 전용 릴레이다.
 *
 * <h2>IdoOutboxRelay 와의 차이점</h2>
 * <table border="1">
 *   <tr><th>항목</th><th>IdoOutboxRelay</th><th>QimOutboxRelay (이 클래스)</th></tr>
 *   <tr><td>대상 이벤트</td><td>qsign.auth.events (인증)</td><td>qim.user.events (회원)</td></tr>
 *   <tr><td>INSERT 주체</td><td>KeycloakOidcService / NonOidcAuthService</td><td>QimSpReceiverService</td></tr>
 *   <tr><td>폴링 주기</td><td>500ms</td><td>1,000ms (회원 이벤트는 실시간성 요구 낮음)</td></tr>
 *   <tr><td>토픽 필터</td><td>없음 (topic 컬럼 기반 발행)</td><td>qim.user.events 만 처리</td></tr>
 * </table>
 *
 * <h2>데이터 흐름</h2>
 * <pre>
 * [Q-IM / Q-Sign]
 *   └─► POST /api/v1/qim/sp/member/register  (QimSpReceiverController)
 *         └─► QimSpReceiverService.handleMemberRegister()
 *               └─► INSERT ido.outbox (topic='qim.user.events', status='PENDING')
 *
 * [이 클래스 — 1000ms 폴링]
 *   └─► SELECT FOR UPDATE SKIP LOCKED
 *         WHERE topic = 'qim.user.events' AND status = 'PENDING'
 *               AND (next_retry_at IS NULL OR next_retry_at <= NOW())
 *         └─► kafkaTemplate.send('qim.user.events', payload)
 *               ├─ 성공 → UPDATE status='PUBLISHED'
 *               └─ 실패 → incrementRetryWithBackoff() / markFailed()
 *
 * [Consumer — QimEventConsumer, QimSpMemberEventConsumer]
 *   └─► 캐시 갱신, 프로비저닝 트리거, 기관 webhook 적재
 * </pre>
 *
 * <h2>at-least-once 보장</h2>
 * <ul>
 *   <li>Kafka 발행 성공 후 DB 갱신 실패 시 → 다음 사이클에서 재발행</li>
 *   <li>Consumer 중복 방어: {@code IdempotentEventStore} (eventId 기반)</li>
 * </ul>
 *
 * <h2>멀티 인스턴스 안전성</h2>
 * <ul>
 *   <li>{@code FOR UPDATE SKIP LOCKED}: 다중 IdO Pod 동시 폴링 시 동일 레코드 중복 발행 방지</li>
 *   <li>{@code fixedDelay}: 이전 실행이 완료된 후 대기 → 단일 인스턴스 내 중복 실행 없음</li>
 * </ul>
 *
 * <h2>설정 키</h2>
 * <ul>
 *   <li>{@code ido.qim-outbox.relay-enabled}    — On/Off 피처 플래그 (기본 true)</li>
 *   <li>{@code ido.qim-outbox.relay-interval-ms} — 폴링 주기 (기본 1000ms)</li>
 *   <li>{@code ido.qim-outbox.batch-size}        — 배치 크기 (기본 50)</li>
 *   <li>{@code ido.qim-outbox.max-retry}         — 최대 재시도 횟수 (기본 5)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QimOutboxRelay {

    /** 처리 대상 토픽 (qim.user.events) */
    private static final String TARGET_TOPIC = "qim.user.events";

    /** payload JSON → Map 역직렬화용 TypeReference */
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF =
            new TypeReference<>() {};

    private final IdoOutboxRepository           outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper                  objectMapper;

    /**
     * Feature Flag: IDO_QIM_OUTBOX_RELAY_ENABLED
     * false → @Scheduled 실행되어도 즉시 return.
     * Kafka 없는 로컬 환경 또는 Q-IM 통합 전 개발 단계에서 OFF 설정 가능.
     */
    @Value("${ido.qim-outbox.relay-enabled:${IDO_QIM_OUTBOX_RELAY_ENABLED:true}}")
    private boolean relayEnabled;

    @Value("${ido.qim-outbox.relay-interval-ms:1000}")
    private long relayIntervalMs;

    @Value("${ido.qim-outbox.batch-size:50}")
    private int batchSize;

    @Value("${ido.qim-outbox.max-retry:5}")
    private int maxRetry;

    // ══════════════════════════════════════════════════════════════════════
    // 폴링 스케줄러
    // ══════════════════════════════════════════════════════════════════════

    /**
     * qim.user.events PENDING 이벤트 Kafka 발행 스케줄러
     *
     * <p>fixedDelay: 이전 실행 완료 후 대기 → 처리량이 배치 크기보다 많아도 중복 실행 없음.
     *
     * <p>트랜잭션 경계:
     * {@code @Transactional}로 SELECT FOR UPDATE SKIP LOCKED 잠금 유지.
     * Kafka 비동기 콜백({@code whenComplete})은 TX 커밋 이후에 실행되므로
     * 상태 갱신은 별도 TX로 처리된다 (at-least-once 설계).
     */
    @Scheduled(fixedDelayString = "${ido.qim-outbox.relay-interval-ms:1000}")
    @Transactional
    public void relay() {
        if (!relayEnabled) {
            log.trace("[QimOutboxRelay] DISABLED (IDO_QIM_OUTBOX_RELAY_ENABLED=false)");
            return;
        }

        List<IdoOutboxRecord> pending = outboxRepository.findPendingBatchByTopic(TARGET_TOPIC, batchSize);
        if (pending.isEmpty()) {
            return;
        }

        log.debug("[QimOutboxRelay] PENDING {} 건 발행 시작 (topic={})", pending.size(), TARGET_TOPIC);

        for (IdoOutboxRecord record : pending) {
            publishRecord(record);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // private: 단건 발행
    // ══════════════════════════════════════════════════════════════════════

    private void publishRecord(IdoOutboxRecord record) {
        try {
            // payload JSON → Map<String, Object> 역직렬화
            // eventType 필드(BIZ_MEMBER_CONVERTED 등)가 보존되어 Consumer가 분기 처리 가능
            Map<String, Object> payload = objectMapper.readValue(record.getPayload(), MAP_TYPE_REF);

            CompletableFuture<SendResult<String, Object>> future =
                    kafkaTemplate.send(
                            record.getTopic(),        // qim.user.events
                            record.getPartitionKey(), // qimUserId (파티션 키 — 동일 사용자 순서 보장)
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
            log.error("[QimOutboxRelay] 역직렬화/발행 실패: eventId={} eventType={} error={}",
                    record.getEventId(), record.getEventType(), e.getMessage());
            handleFailure(record, e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 성공 / 실패 콜백 (TX 커밋 이후 비동기 실행)
    // ══════════════════════════════════════════════════════════════════════

    private void handleSuccess(IdoOutboxRecord record, SendResult<String, Object> result) {
        try {
            outboxRepository.markPublished(record.getEventId());
            log.info("[QimOutboxRelay] 발행 완료: eventId={} eventType={} topic={} partition={} offset={}",
                    record.getEventId(),
                    record.getEventType(),
                    record.getTopic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
        } catch (Exception e) {
            // DB 갱신 실패 → 다음 사이클에서 재발행 (at-least-once)
            // Consumer의 IdempotentEventStore가 중복 수신 방어
            log.warn("[QimOutboxRelay] PUBLISHED 갱신 실패 (재발행 예정): eventId={}", record.getEventId(), e);
        }
    }

    private void handleFailure(IdoOutboxRecord record, Throwable ex) {
        try {
            int nextRetry = record.getRetryCount() + 1;
            if (nextRetry >= maxRetry) {
                outboxRepository.markFailed(record.getEventId(), ex.getMessage());
                log.error("[QimOutboxRelay] 최대 재시도 초과 → FAILED: eventId={} eventType={} retry={}/{}",
                        record.getEventId(), record.getEventType(), nextRetry, maxRetry, ex);
            } else {
                // 지수 백오프: 2^currentRetry 초 후 재시도 (Thundering Herd 방지)
                outboxRepository.incrementRetryWithBackoff(
                        record.getEventId(), ex.getMessage(), record.getRetryCount());
                log.warn("[QimOutboxRelay] 발행 실패 → 백오프 재예약 (retry={}/{}): eventId={} eventType={}",
                        nextRetry, maxRetry, record.getEventId(), record.getEventType(), ex);
            }
        } catch (Exception dbEx) {
            log.error("[QimOutboxRelay] 실패 상태 갱신 실패: eventId={}", record.getEventId(), dbEx);
        }
    }
}

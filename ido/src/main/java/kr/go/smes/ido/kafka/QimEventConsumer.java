package kr.go.smes.ido.kafka;

import kr.go.smes.common.domain.UserStatus;
import kr.go.smes.common.event.UserEvent;
import kr.go.smes.ido.infrastructure.LastEventVersionStore;
import kr.go.smes.ido.infrastructure.QimClient;
import kr.go.smes.ido.infrastructure.UserStatusCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * IdO ← Q-IM 사용자 이벤트 컨슈머
 * 설계서 §11.5 Selective Pull 최적화 / §11.5.5 Ordered Consumer 패턴
 *
 * <p>처리 흐름:
 * <ol>
 *   <li>LastEventVersion 조회 → 중복/순서 오류 감지</li>
 *   <li>Q-IM 캐시(Redis) 무효화 or 갱신</li>
 *   <li>needsSync=true 이면 Q-IM API pull 트리거</li>
 *   <li>ProcessedEvent 기록 + 수동 ACK</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QimEventConsumer {

    private static final String CONSUMER_GROUP = "ido-qim-consumer";

    private final LastEventVersionStore lastEventVersionStore;
    private final UserStatusCache       userStatusCache;
    private final IdempotentEventStore  idempotentEventStore;
    private final QimClient             qimClient;
    // consumer group 전용 버전 저장 → 단순 qimUserId 기반 LastEventVersionStore 래핑
    // (consumerGroup prefix 는 key 에 포함하여 구분)

    /**
     * qim.user.events 구독
     * containerFactory = qimListenerContainerFactory (KafkaConsumerConfig 참조)
     */
    @KafkaListener(
            topics       = "${qim.kafka.topic-user-events:qim.user.events}",
            groupId      = "${ido.kafka.consumer-group-qim:ido-qim-consumer}",
            containerFactory = "qimListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, UserEvent> record, Acknowledgment ack) {
        UserEvent event = record.value();
        if (event == null) {
            log.warn("[QimEventConsumer] null 이벤트 수신, 파티션={} 오프셋={}",
                    record.partition(), record.offset());
            ack.acknowledge();
            return;
        }

        String qimUserId   = event.getQimUserId();
        Long   version     = event.getEventVersion();
        String eventId     = event.getEventId();

        try {
            // ① 멱등 처리: 이미 처리한 eventId 이면 스킵
            if (idempotentEventStore.isAlreadyProcessed(eventId, CONSUMER_GROUP)) {
                log.debug("[QimEventConsumer] 중복 이벤트 스킵: eventId={}", eventId);
                ack.acknowledge();
                return;
            }

            // ② §11.5.5 Ordered Consumer: 버전 순서 검증
            // LastEventVersionStore 키 = CONSUMER_GROUP + ":" + qimUserId
            String versionKey = CONSUMER_GROUP + ":" + qimUserId;
            Long lastVersion = lastEventVersionStore.get(versionKey);
            if (lastVersion != null && version <= lastVersion) {
                log.debug("[QimEventConsumer] 이전 버전 스킵: qimUserId={} version={} lastVersion={}",
                        qimUserId, version, lastVersion);
                idempotentEventStore.markProcessed(eventId, CONSUMER_GROUP, event.getEventType(), "SKIPPED");
                ack.acknowledge();
                return;
            }

            // ③ Q-IM 캐시 무효화 (TTL ≤5분 설계 §11.3)
            userStatusCache.invalidate(qimUserId);
            log.debug("[QimEventConsumer] Q-IM 캐시 무효화: qimUserId={}", qimUserId);

            // ④ needsSync=true → Selective Pull: Q-IM API 직접 호출하여 최신 상태 갱신 (§10.5.1, §24.4.1)
            if (Boolean.TRUE.equals(event.isNeedsSync())) {
                log.info("[QimEventConsumer] Selective Pull 실행: qimUserId={} eventType={}",
                        qimUserId, event.getEventType());
                try {
                    UserStatus freshStatus = qimClient.getUserStatus(qimUserId, eventId);
                    userStatusCache.put(qimUserId, freshStatus);
                    log.info("[QimEventConsumer] Selective Pull 완료: qimUserId={} status={}",
                            qimUserId, freshStatus);
                } catch (Exception pullEx) {
                    // Pull 실패는 캐시 무효화로 대체 — 다음 Handoff Issue 시 재조회됨
                    log.warn("[QimEventConsumer] Selective Pull 실패 (캐시 무효화 유지): qimUserId={} error={}",
                            qimUserId, pullEx.getMessage());
                }
            }

            // ⑤ 버전 갱신 + ProcessedEvent 기록
            lastEventVersionStore.put(versionKey, version);
            idempotentEventStore.markProcessed(eventId, CONSUMER_GROUP, event.getEventType(), "OK");

            log.info("[QimEventConsumer] 처리 완료: qimUserId={} eventType={} version={}",
                    qimUserId, event.getEventType(), version);

        } catch (Exception e) {
            log.error("[QimEventConsumer] 처리 실패: eventId={} qimUserId={}",
                    eventId, qimUserId, e);
            // DefaultErrorHandler 가 재시도 → 임계치 초과 시 DLQ
            throw e;
        } finally {
            ack.acknowledge();
        }
    }
}

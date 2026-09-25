package io.github.hipstermin.idem.hub.kafka;

import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.common.event.UserEvent;
import io.github.hipstermin.idem.hub.infrastructure.LastEventVersionStore;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import io.github.hipstermin.idem.hub.infrastructure.UserStatusCache;
import java.util.Map;
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

    private static final String CONSUMER_GROUP = "idem-hub-registry-consumer";

    private final LastEventVersionStore lastEventVersionStore;
    private final UserStatusCache       userStatusCache;
    private final IdempotentEventStore  idempotentEventStore;
    private final QimClient             qimClient;
    private final io.github.hipstermin.idem.hub.fe.session.FeSessionService feSessionService;
    // consumer group 전용 버전 저장 → 단순 qimUserId 기반 LastEventVersionStore 래핑
    // (consumerGroup prefix 는 key 에 포함하여 구분)

    /**
     * idem.registry.user.events 구독
     * containerFactory = qimListenerContainerFactory (KafkaConsumerConfig 참조)
     */
    @KafkaListener(
            topics       = "${idem.registry.kafka.topic-user-events:idem.registry.user.events}",
            groupId      = "${idem.hub.kafka.consumer-group-qim:idem-hub-registry-consumer}",
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
        try {
            handle(event);
        } finally {
            ack.acknowledge();
        }
    }

    /**
     * D3: 전송 수단과 무관한 처리 진입점 — Kafka 리스너와 {@code RegistryOutboxPoller}(Kafka 없는 설치) 가 같이 쓴다.
     * 멱등({@code processed_event})·순서(버전) 검사 후 상태 캐시를 무효화하고, 정지·탈퇴면 그 사용자의 FE 세션을 전부 끝낸다 —
     * 종전에는 캐시만 비워 이미 로그인한 세션이 그대로 살아 있었다.
     *
     * @throws RuntimeException 처리 실패 (호출자가 재시도·DLQ 를 결정)
     */
    public void handle(UserEvent event) {
        String qimUserId   = event.getQimUserId();
        Long   version     = event.getEventVersion();
        String eventId     = event.getEventId();

        try {
            // ① 멱등 처리: 이미 처리한 eventId 이면 스킵
            if (idempotentEventStore.isAlreadyProcessed(eventId, CONSUMER_GROUP)) {
                log.debug("[QimEventConsumer] 중복 이벤트 스킵: eventId={}", eventId);
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
                return;
            }

            // ③ Q-IM 캐시 무효화 (TTL ≤5분 설계 §11.3)
            userStatusCache.invalidate(qimUserId);
            log.debug("[QimEventConsumer] Q-IM 캐시 무효화: qimUserId={}", qimUserId);

            // ③-b D3: 정지·탈퇴는 살아 있는 FE 세션까지 끝낸다 (상태 캐시만 비우면 다음 Handoff 발급만 막히고 세션은 남았다)
            if (isTerminal(event)) {
                feSessionService.invalidateByQimUserId(qimUserId, event.getEventType());
                log.info("[QimEventConsumer] 상태 변경 전파 → FE 세션 무효화: qimUserId={} eventType={} status={}",
                        qimUserId, event.getEventType(), event.getUserStatus());
            }

            // ④ needsSync=true → Selective Pull (GAP-QIM-01): Q-IM getUserById() 전체 정보 조회
            //    getUserStatus() 대신 getUserById()로 상태+프로필 전체를 한 번에 pull (§10.5.1, §24.4.1)
            if (Boolean.TRUE.equals(event.isNeedsSync())) {
                log.info("[QimEventConsumer] Selective Pull (getUserById) 실행: qimUserId={} eventType={}",
                        qimUserId, event.getEventType());
                try {
                    Map<String, Object> userInfo = qimClient.getUserById(qimUserId, eventId);
                    if (userInfo != null) {
                        // 상태 캐시 갱신
                        String statusStr = (String) userInfo.get("status");
                        if (statusStr != null) {
                            try {
                                UserStatus freshStatus = UserStatus.valueOf(statusStr);
                                userStatusCache.put(qimUserId, freshStatus);
                                log.info("[QimEventConsumer] Selective Pull 완료: qimUserId={} status={} eventType={}",
                                        qimUserId, freshStatus, event.getEventType());
                            } catch (IllegalArgumentException e) {
                                log.warn("[QimEventConsumer] 알 수 없는 UserStatus '{}' qimUserId={}", statusStr, qimUserId);
                                userStatusCache.invalidate(qimUserId);
                            }
                        } else {
                            userStatusCache.invalidate(qimUserId);
                        }
                    } else {
                        // getUserById 실패(null) → 상태 캐시 무효화로 대체
                        // 다음 Handoff Issue 시 getUserStatus로 재조회
                        log.warn("[QimEventConsumer] Selective Pull null 반환 — 캐시 무효화 유지: qimUserId={}", qimUserId);
                        userStatusCache.invalidate(qimUserId);
                    }
                } catch (Exception pullEx) {
                    // Pull 예외 → 캐시 무효화로 fallback
                    log.warn("[QimEventConsumer] Selective Pull 예외 (캐시 무효화 유지): qimUserId={} error={}",
                            qimUserId, pullEx.getMessage());
                    userStatusCache.invalidate(qimUserId);
                }
            }

            // ⑤ 버전 갱신 + ProcessedEvent 기록
            lastEventVersionStore.put(versionKey, version);
            idempotentEventStore.markProcessed(eventId, CONSUMER_GROUP, event.getEventType(), "OK");

            // S4b: 전 기관 프로비저닝 트리거 제거 — 서비스에는 어설션·백채널 로그아웃·보안/감사 이벤트만 push 한다(플랜 §2.0)

            log.info("[QimEventConsumer] 처리 완료: qimUserId={} eventType={} version={}",
                    qimUserId, event.getEventType(), version);

        } catch (Exception e) {
            log.error("[QimEventConsumer] 처리 실패: eventId={} qimUserId={}",
                    eventId, qimUserId, e);
            // Kafka: DefaultErrorHandler 가 재시도 → 임계치 초과 시 DLQ / 폴링: 워터마크를 넘기지 않고 다음 주기에 재시도
            throw e;
        }
    }

    /** 정지·탈퇴(예정 포함) 이벤트인가 — 이벤트 타입 또는 실린 상태값 어느 쪽이든 */
    static boolean isTerminal(UserEvent event) {
        String type = event.getEventType();
        if (UserEvent.TYPE_SUSPENDED.equals(type) || UserEvent.TYPE_WITHDRAWN.equals(type)) return true;
        String status = event.getUserStatus();
        return status != null && (status.equals(UserStatus.SUSPENDED.name()) || status.equals(UserStatus.WITHDRAWN.name())
                || status.equals(UserStatus.WITHDRAWAL_SCHEDULED.name()));
    }
}

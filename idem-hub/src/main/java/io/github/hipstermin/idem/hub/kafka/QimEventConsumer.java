package io.github.hipstermin.idem.hub.kafka;

import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.common.event.UserEvent;
import io.github.hipstermin.idem.hub.infrastructure.LastEventVersionStore;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import io.github.hipstermin.idem.hub.infrastructure.UserStatusCache;
import io.github.hipstermin.idem.hub.provision.ProvisioningService;
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

    private static final String CONSUMER_GROUP = "ido-qim-consumer";

    private final LastEventVersionStore lastEventVersionStore;
    private final UserStatusCache       userStatusCache;
    private final IdempotentEventStore  idempotentEventStore;
    private final QimClient             qimClient;
    /** Sprint 14: 전 기관 프로비저닝 트리거 (QIM-OUTBOX-SPEC-001 신규 4종 이벤트 대응) */
    private final ProvisioningService   provisioningService;
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

            // ⑥ Sprint 14: 전 기관 프로비저닝 트리거 (QIM-OUTBOX-SPEC-001 신규 4종 명칭 적용)
            //    등록/전환 4종 이벤트 모두 프로비저닝 트리거 대상
            //    provisioningService 내부에서 Feature Flag + 중복 sourceEventId 방어 처리
            String evtType = event.getEventType();
            if (isProvisioningTriggerEvent(evtType)) {
                try {
                    provisioningService.triggerProvisioning(
                            qimUserId,
                            evtType,
                            eventId,        // sourceEventId — 중복 트리거 방어
                            eventId         // correlationId — 이벤트 단위 흐름 추적
                    );
                } catch (Exception provEx) {
                    // 프로비저닝 실패는 provisioning_outbox PENDING으로 이미 저장됨
                    // Relay가 재시도하므로 consumer 실패로 전파하지 않음
                    log.error("[QimEventConsumer] 프로비저닝 트리거 예외 (Relay 재시도 예정): " +
                                    "qimUserId={} eventType={} eventId={} error={}",
                            qimUserId, evtType, eventId, provEx.getMessage());
                }
            }

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

    /**
     * 프로비저닝 트리거 대상 이벤트인지 판별
     *
     * <p>QIM-OUTBOX-SPEC-001 기준 등록/전환 4종 이벤트가
     * 프로비저닝(68개 기관 병렬 알림) 트리거 대상이다.
     *
     * <ul>
     *   <li>BIZ_MEMBER_CONVERTED      — 기업회원 전환</li>
     *   <li>BIZ_MEMBER_REGISTERED     — 기업회원 신규</li>
     *   <li>PERSONAL_MEMBER_CONVERTED — 개인회원 전환</li>
     *   <li>PERSONAL_MEMBER_REGISTERED— 개인회원 신규</li>
     * </ul>
     *
     * @param eventType Kafka 메시지의 eventType 필드
     * @return 프로비저닝 트리거 대상이면 true
     */
    private static boolean isProvisioningTriggerEvent(String eventType) {
        return "BIZ_MEMBER_CONVERTED".equals(eventType)
            || "BIZ_MEMBER_REGISTERED".equals(eventType)
            || "PERSONAL_MEMBER_CONVERTED".equals(eventType)
            || "PERSONAL_MEMBER_REGISTERED".equals(eventType);
    }
}

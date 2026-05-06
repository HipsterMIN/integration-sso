package kr.go.smes.ido.infrastructure;

import kr.go.smes.common.event.UserEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Q-IM 사용자 변경 이벤트 소비자 (IdO 캐시 무효화)
 * 설계서 11.5.2 / 11.5.3절 참조
 *
 * 순서 보장 전략 (설계서 11.5.3절):
 *   - partitionKey = qimUserId → 동일 사용자 이벤트는 동일 파티션에 직렬 처리
 *   - lastAppliedEventVersion vs incoming eventVersion 비교 (optimistic-lock)
 *   - incoming <= lastApplied → 멱등하게 무시 (감사 로그 기록)
 *   - incoming > lastApplied + 1 → 중간 이벤트 유실 가능, Selective Pull로 보정
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QimEventConsumer {

    private final UserStatusCache userStatusCache;
    private final LastEventVersionStore lastEventVersionStore;

    @KafkaListener(
        topics = "qim.user.events",
        groupId = "ido-cache-invalidator",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void onUserEvent(
            @Payload UserEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        String qimUserId    = event.getQimUserId();
        Long   incomingVer  = event.getEventVersion();
        Long   lastApplied  = lastEventVersionStore.get(qimUserId);

        log.debug("[IdO Cache] 이벤트 수신 qimUserId={} type={} version={} partition={} offset={}",
                qimUserId, event.getEventType(), incomingVer, partition, offset);

        // ① 역전 도착 / 중복 수신 → 멱등 무시
        if (lastApplied != null && incomingVer <= lastApplied) {
            log.info("[IdO Cache] 이벤트 역전/중복 무시 qimUserId={} incoming={} lastApplied={}",
                    qimUserId, incomingVer, lastApplied);
            return;
        }

        // ② 중간 이벤트 유실 가능성
        if (lastApplied != null && incomingVer > lastApplied + 1) {
            log.warn("[IdO Cache] 이벤트 유실 가능 → Selective Pull 트리거 qimUserId={} expected={} incoming={}",
                    qimUserId, lastApplied + 1, incomingVer);
            // TODO: Selective Pull 호출 (Q-IM 직접 재조회)
        }

        // ③ 캐시 무효화 (이벤트 수신 즉시)
        userStatusCache.invalidate(qimUserId);
        lastEventVersionStore.put(qimUserId, incomingVer);

        log.info("[IdO Cache] 캐시 무효화 완료 qimUserId={} event={}", qimUserId, event.getEventType());
    }
}

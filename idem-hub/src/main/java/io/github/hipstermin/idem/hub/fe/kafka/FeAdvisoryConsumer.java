package io.github.hipstermin.idem.hub.fe.kafka;

import io.github.hipstermin.idem.common.event.SessionAdvisoryEvent;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * IdO ← platform.session.advisory 컨슈머 (FE 세션 처리)
 * 설계서 §12.5 세션 Advisory 수신 처리
 *
 * <p>onepass-fe Spring Boot BFF 제거에 따라
 * SessionAdvisoryConsumer 기능이 IdO 로 이관됨.
 *
 * <p>Advisory / Mandatory 이벤트 구분:
 * <ul>
 *   <li>Advisory  — 권고 사항. 다음 요청 시 로그아웃 안내 플래그 설정</li>
 *   <li>Mandatory — 보안 강제. 즉시 FE 세션 일괄 무효화</li>
 * </ul>
 *
 * <p>§14.11 세션 오너십: 세션 종료는 채널이 단독 결정 (플랫폼 강제 불가).
 * Mandatory 처리는 보안 정책 예외 케이스.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeAdvisoryConsumer {

    private final FeSessionService feSessionService;

    @KafkaListener(
            topics           = "${ido.kafka.topic-session-advisory:platform.session.advisory}",
            groupId          = "${ido.kafka.consumer-group-fe-advisory:ido-fe-advisory-consumer}",
            containerFactory = "feAdvisoryListenerFactory"
    )
    public void consume(ConsumerRecord<String, SessionAdvisoryEvent> record,
                        Acknowledgment ack) {

        SessionAdvisoryEvent event = record.value();

        if (event == null) {
            log.warn("[FeAdvisoryConsumer] null 이벤트 수신 — ACK 처리");
            ack.acknowledge();
            return;
        }

        String qimUserId = event.getQimUserId();
        String eventType = event.getEventType();

        log.debug("[FeAdvisoryConsumer] 수신 eventId={} type={} qimUserId={}",
                event.getEventId(), eventType, qimUserId);

        try {
            if (SessionAdvisoryEvent.TYPE_MANDATORY_SECURITY.equals(eventType)) {
                // MANDATORY: FE 세션 즉시 일괄 무효화 (§12.5)
                feSessionService.invalidateByQimUserId(qimUserId, "MANDATORY_SECURITY");
                log.warn("[FeAdvisoryConsumer] MANDATORY 세션 무효화: qimUserId={} reason={}",
                        qimUserId, event.getReason());
            } else {
                // ADVISORY: 다음 요청 시 로그아웃 안내용 플래그 설정
                feSessionService.markAdvisoryFlag(qimUserId, event.getReason());
                log.info("[FeAdvisoryConsumer] Advisory 플래그 설정: qimUserId={} type={} severity={}",
                        qimUserId, eventType, event.getSeverity());
            }
        } catch (Exception e) {
            log.error("[FeAdvisoryConsumer] 처리 실패: eventId={} qimUserId={} type={}",
                    event.getEventId(), qimUserId, eventType, e);
            throw e;   // DefaultErrorHandler (exponential back-off) 에 위임
        } finally {
            ack.acknowledge();
        }
    }
}

package com.onepass.fe.kafka;

import com.onepass.common.event.SessionAdvisoryEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Onepass-FE ← platform.session.advisory 컨슈머
 * 설계서 §12.5 세션 Advisory 수신 처리
 *
 * <p>Advisory / Mandatory 이벤트 구분:
 * <ul>
 *   <li>Advisory  — 권고 사항. FE 가 다음 요청 시 확인 후 로그아웃 안내</li>
 *   <li>Mandatory — 보안 강제. 즉시 FE 세션 무효화</li>
 * </ul>
 *
 * <p>§14.11 세션 오너십: 세션 종료는 채널이 단독 결정 (플랫폼 강제 불가)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionAdvisoryConsumer {

    private final com.onepass.fe.session.FeSessionService feSessionService;

    @KafkaListener(
            topics           = "${onepass.kafka.topic-session-advisory:platform.session.advisory}",
            groupId          = "${onepass.kafka.consumer-group:onepass-fe-consumer}",
            containerFactory = "feAdvisoryListenerFactory"
    )
    public void consume(ConsumerRecord<String, SessionAdvisoryEvent> record,
                        Acknowledgment ack) {
        SessionAdvisoryEvent event = record.value();
        if (event == null) {
            ack.acknowledge();
            return;
        }

        String qimUserId = event.getQimUserId();
        String severity  = event.getSeverity();

        try {
            if (SessionAdvisoryEvent.TYPE_MANDATORY_SECURITY.equals(event.getEventType())) {
                // MANDATORY: FE 세션 즉시 무효화
                feSessionService.invalidateByQimUserId(qimUserId, "MANDATORY_SECURITY");
                log.warn("[FE-AdvisoryConsumer] MANDATORY 세션 무효화: qimUserId={} reason={}",
                        qimUserId, event.getReason());
            } else {
                // ADVISORY: 세션에 플래그만 설정 → 다음 요청 시 로그아웃 안내
                feSessionService.markAdvisoryFlag(qimUserId, event.getReason());
                log.info("[FE-AdvisoryConsumer] Advisory 플래그 설정: qimUserId={} severity={}",
                        qimUserId, severity);
            }
        } catch (Exception e) {
            log.error("[FE-AdvisoryConsumer] 처리 실패: eventId={} qimUserId={}",
                    event.getEventId(), qimUserId, e);
            throw e;
        } finally {
            ack.acknowledge();
        }
    }
}

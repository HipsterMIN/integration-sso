package io.github.hipstermin.idem.hub.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.event.AuthEvent;
import io.github.hipstermin.idem.common.event.HandoffEvent;
import io.github.hipstermin.idem.common.event.SessionAdvisoryEvent;
import io.github.hipstermin.idem.common.messaging.KafkaOptional;
import io.github.hipstermin.idem.hub.fe.kafka.FeAdvisoryConsumer;
import io.github.hipstermin.idem.hub.kafka.HandoffEventConsumer;
import io.github.hipstermin.idem.hub.kafka.QsignAuthEventConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kafka 없는 배포의 아웃박스 배달기 (D1-b, {@code docs/generalization-plan.md} D1).
 *
 * <p>{@link IdoOutboxRelay} 가 {@code idem.hub.outbox} PENDING 레코드를 Kafka 로 보내는 대신 이 클래스로 넘기면,
 * 토픽 이름으로 같은 프로세스의 컨슈머 진입점({@code handle(...)})을 골라 호출한다. 컨슈머 쪽 멱등 처리
 * ({@code processed_event})가 그대로 동작하므로 릴레이의 at-least-once 재시도와 합쳐 정확히 한 번 효과를 낸다.
 *
 * <p>배달 대상 (hub 자기 자신이 발행하고 자기 자신이 소비하는 토픽):
 * <ul>
 *   <li>{@code idem.gate.auth.events}        → {@link QsignAuthEventConsumer#handle}</li>
 *   <li>{@code platform.session.advisory} → {@link FeAdvisoryConsumer#handle}</li>
 *   <li>{@code idem.hub.handoff.events}       → {@link HandoffEventConsumer#handle}</li>
 * </ul>
 * 그 외 토픽(예: {@code idem.registry.user.events}, 감사 토픽)은 이 프로세스에 소비자가 없으므로 실패로 돌려 릴레이가 FAILED 로 남긴다 —
 * 운영자가 보게 하는 것이 조용히 PUBLISHED 처리하는 것보다 낫다.
 *
 * <p>각 배달은 {@code REQUIRES_NEW} 트랜잭션이다: 핸들러가 실패해도 릴레이의 바깥 트랜잭션(레코드 잠금·상태 갱신)이
 * rollback-only 로 오염되지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = KafkaOptional.PROPERTY, havingValue = "false", matchIfMissing = true)
public class InProcessOutboxDispatcher {

    private final ObjectMapper             objectMapper;
    private final QsignAuthEventConsumer   qsignAuthEventConsumer;
    private final FeAdvisoryConsumer       feAdvisoryConsumer;
    private final HandoffEventConsumer     handoffEventConsumer;

    @Value("${idem.hub.kafka.topic-auth-events:idem.gate.auth.events}")
    private String authEventsTopic;

    @Value("${idem.hub.kafka.topic-session-advisory:platform.session.advisory}")
    private String sessionAdvisoryTopic;

    @Value("${idem.hub.kafka.topic-handoff-events:idem.hub.handoff.events}")
    private String handoffEventsTopic;

    /**
     * @throws IllegalStateException 이 프로세스에 소비자가 없는 토픽
     * @throws RuntimeException      역직렬화·핸들러 실패 (릴레이가 백오프 재예약)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatch(IdoOutboxRecord record) {
        String topic = record.getTopic();
        try {
            if (authEventsTopic.equals(topic)) {
                qsignAuthEventConsumer.handle(objectMapper.readValue(record.getPayload(), AuthEvent.class));
            } else if (sessionAdvisoryTopic.equals(topic)) {
                feAdvisoryConsumer.handle(objectMapper.readValue(record.getPayload(), SessionAdvisoryEvent.class));
            } else if (handoffEventsTopic.equals(topic)) {
                handoffEventConsumer.handle(objectMapper.readValue(record.getPayload(), HandoffEvent.class));
            } else {
                throw new IllegalStateException("no in-process consumer for topic '" + topic
                        + "' (Kafka disabled: " + KafkaOptional.PROPERTY + "=false)");
            }
            log.debug("[InProcessOutbox] 배달 완료: eventId={} topic={} type={}",
                    record.getEventId(), topic, record.getEventType());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("outbox payload 역직렬화 실패: eventId=" + record.getEventId()
                    + " topic=" + topic + " — " + e.getMessage(), e);
        }
    }
}

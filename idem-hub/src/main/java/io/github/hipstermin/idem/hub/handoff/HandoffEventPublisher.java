package io.github.hipstermin.idem.hub.handoff;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.event.HandoffEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Handoff 이벤트 발행 경로 선택 (D1-b).
 *
 * <ul>
 *   <li>Kafka 켜짐: 종전과 같이 {@code idem.hub.handoff.events} 로 즉시 발행 (비동기, 실패는 로그).</li>
 *   <li>Kafka 꺼짐(기본): {@code idem.hub.outbox} 에 PENDING 으로 적재 — 호출자의 DB 트랜잭션과 함께 커밋되므로
 *       티켓 상태 변경과 이벤트가 원자적이다. {@code IdoOutboxRelay} 가 폴링해 같은 프로세스의
 *       {@code HandoffEventConsumer.handle()} 로 배달한다(웹훅 적재·캐시 무효화·감사).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HandoffEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final JdbcTemplate                  jdbcTemplate;
    private final ObjectMapper                  objectMapper;

    @Value("${idem.messaging.kafka.enabled:false}")
    private boolean kafkaEnabled;

    @Value("${idem.hub.kafka.topic-handoff-events:idem.hub.handoff.events}")
    private String handoffTopic;

    /**
     * @param event        발행할 이벤트
     * @param partitionKey Kafka 파티션 키 / 아웃박스 partition_key (qimUserId)
     */
    public void publish(HandoffEvent event, String partitionKey) {
        if (kafkaEnabled) {
            kafkaTemplate.send(handoffTopic, partitionKey, event);
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(event);
            jdbcTemplate.update("""
                    INSERT INTO idem_hub.outbox
                        (event_id, event_type, partition_key, aggregate_id,
                         event_version, payload, topic, status, retry_count, created_at)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, 'PENDING', 0, NOW())
                    ON CONFLICT (event_id) DO NOTHING
                    """,
                    event.getEventId(),
                    event.getEventType(),
                    partitionKey,
                    event.getTicketId() != null ? event.getTicketId() : event.getEventId(),
                    event.getEventVersion() != null ? event.getEventVersion() : 1L,
                    payload,
                    handoffTopic);
        } catch (Exception e) {
            // 아웃박스 적재 실패는 호출 흐름(티켓 발급/소비)을 막지 않는다 — 감사 로그는 호출자가 별도로 남긴다
            log.error("[HandoffEventPublisher] outbox 적재 실패 (비치명적): eventId={} type={} error={}",
                    event.getEventId(), event.getEventType(), e.getMessage());
        }
    }
}

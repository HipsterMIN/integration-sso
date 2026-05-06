package com.onepass.qim.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onepass.common.event.DomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Transactional Outbox 구현체
 * 설계서 10.5.2절 참조
 *
 * 흐름:
 *   1. publishInTx() → 현재 트랜잭션 내 OUTBOX 레코드 INSERT
 *   2. relayPendingEvents() → 스케줄러가 PENDING 레코드 조회 후 Kafka 발행 + 상태 PUBLISHED 갱신
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxServiceImpl implements OutboxService {

    private static final String TOPIC_USER_EVENTS = "qim.user.events";

    private final OutboxRepository             outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper                 objectMapper;

    @Override
    @Transactional  // 호출자 트랜잭션에 참여 (PROPAGATION.REQUIRED)
    public void publishInTx(DomainEvent event) {
        OutboxRecord record = OutboxRecord.builder()
                .eventId(event.getEventId())
                .eventType(event.getEventType())
                .partitionKey(event.getQimUserId())  // Kafka partitionKey = qimUserId
                .eventVersion(event.getEventVersion())
                .payload(serialize(event))
                .status(OutboxRecord.OutboxStatus.PENDING)
                .build();

        outboxRepository.save(record);
        log.debug("[Outbox] 레코드 적재 eventId={} type={}", event.getEventId(), event.getEventType());
    }

    @Override
    @Scheduled(fixedDelayString = "${qim.outbox.relay-interval-ms:500}")
    public void relayPendingEvents() {
        List<OutboxRecord> pending = outboxRepository.findPending(100);
        if (pending.isEmpty()) return;

        for (OutboxRecord record : pending) {
            try {
                kafkaTemplate.send(TOPIC_USER_EVENTS, record.getPartitionKey(), record.getPayload())
                        .whenComplete((result, ex) -> {
                            if (ex == null) {
                                outboxRepository.markPublished(record.getEventId());
                                log.debug("[Outbox] 발행 완료 eventId={}", record.getEventId());
                            } else {
                                log.error("[Outbox] 발행 실패 eventId={}", record.getEventId(), ex);
                            }
                        });
            } catch (Exception e) {
                log.error("[Outbox] Relay 오류 eventId={}", record.getEventId(), e);
            }
        }
    }

    private String serialize(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException("Outbox 직렬화 실패", e);
        }
    }
}

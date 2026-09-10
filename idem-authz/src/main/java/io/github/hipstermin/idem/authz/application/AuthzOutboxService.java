package io.github.hipstermin.idem.authz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.authz.infrastructure.AuthzOutboxEntity;
import io.github.hipstermin.idem.authz.infrastructure.AuthzOutboxRepository;
import io.github.hipstermin.idem.common.event.AuthorizationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인가 이벤트 트랜잭셔널 아웃박스 발행기.
 *
 * <p>{@link #publishInTx}는 호출자 트랜잭션(PROPAGATION.REQUIRED)에 참여하여
 * 부여 변경과 동일 커밋에 아웃박스 행을 적재한다. 실제 Kafka 발행은
 * {@code outbox-relay-batch}의 AuthzKafkaRelayJob이 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthzOutboxService {

    static final String TOPIC = "authz.assignment.events";

    private final AuthzOutboxRepository outboxRepository;
    private final ObjectMapper          objectMapper;

    @Transactional
    public void publishInTx(AuthorizationEvent event) {
        AuthzOutboxEntity record = AuthzOutboxEntity.builder()
                .eventId(event.getEventId())
                .eventType(event.getEventType())
                .partitionKey(event.getQimUserId())               // Kafka 파티션 키
                .aggregateId(event.getAgencyCode() + ":" + event.getRoleCode())
                .eventVersion(event.getEventVersion())            // authz는 null
                .payload(serialize(event))
                .topic(TOPIC)
                .build();
        outboxRepository.save(record);
        log.debug("[AuthzOutbox] 적재 eventId={} type={} agg={}",
                event.getEventId(), event.getEventType(), record.getAggregateId());
    }

    private String serialize(AuthorizationEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            // 직렬화 실패는 비즈니스 TX를 롤백시켜야 함(이벤트 유실 방지)
            throw new IllegalStateException("인가 이벤트 직렬화 실패: " + event.getEventType(), e);
        }
    }
}

package io.github.hipstermin.idem.hub.infrastructure.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** D1-b: Kafka 꺼진 릴레이는 Kafka 를 건드리지 않고 InProcessOutboxDispatcher 로 배달한 뒤 상태를 갱신한다. */
@ExtendWith(MockitoExtension.class)
class IdoOutboxRelayInProcessTest {

    @Mock IdoOutboxRepository outboxRepository;
    @Mock KafkaTemplate<String, Object> kafkaTemplate;
    @Mock ObjectProvider<InProcessOutboxDispatcher> provider;
    @Mock InProcessOutboxDispatcher dispatcher;

    private IdoOutboxRelay sut;

    @BeforeEach
    void setUp() {
        sut = new IdoOutboxRelay(outboxRepository, kafkaTemplate, new ObjectMapper(), provider);
        ReflectionTestUtils.setField(sut, "relayEnabled", true);
        ReflectionTestUtils.setField(sut, "kafkaEnabled", false);
        ReflectionTestUtils.setField(sut, "batchSize", 100);
        ReflectionTestUtils.setField(sut, "maxRetry", 3);
    }

    @Test
    @DisplayName("배달 성공 → PUBLISHED, Kafka 미사용")
    void success() {
        IdoOutboxRecord r = record("evt-1", 0);
        given(outboxRepository.findPendingBatchExcludingTopics(any(), anyInt())).willReturn(List.of(r));
        given(provider.getIfAvailable()).willReturn(dispatcher);

        sut.relay();

        then(dispatcher).should().dispatch(r);
        then(outboxRepository).should().markPublished("evt-1");
        then(kafkaTemplate).should(never()).send(anyString(), any(), any());
    }

    @Test
    @DisplayName("배달 실패 → 백오프 재예약 (retry < max)")
    void failureBackoff() {
        IdoOutboxRecord r = record("evt-2", 0);
        given(outboxRepository.findPendingBatchExcludingTopics(any(), anyInt())).willReturn(List.of(r));
        given(provider.getIfAvailable()).willReturn(dispatcher);
        willThrow(new IllegalStateException("handler down")).given(dispatcher).dispatch(r);

        sut.relay();

        then(outboxRepository).should().incrementRetryWithBackoff(eq("evt-2"), anyString(), eq(0));
        then(outboxRepository).should(never()).markPublished(any());
    }

    @Test
    @DisplayName("최대 재시도 도달 → FAILED")
    void failureFinal() {
        IdoOutboxRecord r = record("evt-3", 2);
        given(outboxRepository.findPendingBatchExcludingTopics(any(), anyInt())).willReturn(List.of(r));
        given(provider.getIfAvailable()).willReturn(dispatcher);
        willThrow(new IllegalStateException("no in-process consumer for topic")).given(dispatcher).dispatch(r);

        sut.relay();

        then(outboxRepository).should().markFailed(eq("evt-3"), anyString());
    }

    @Test
    @DisplayName("배달기 빈이 없으면(설정 불일치) 실패 처리하고 예외를 삼키지 않는 대신 상태로 남긴다")
    void noDispatcher() {
        IdoOutboxRecord r = record("evt-4", 0);
        given(outboxRepository.findPendingBatchExcludingTopics(any(), anyInt())).willReturn(List.of(r));
        given(provider.getIfAvailable()).willReturn(null);

        sut.relay();

        then(outboxRepository).should().incrementRetryWithBackoff(eq("evt-4"), anyString(), eq(0));
    }

    private static IdoOutboxRecord record(String id, int retry) {
        return IdoOutboxRecord.builder()
                .eventId(id).eventType("AUTH_COMPLETED").partitionKey("pk").aggregateId("agg")
                .eventVersion(1L).payload("{}").topic("idem.gate.auth.events").status("PENDING").retryCount(retry)
                .createdAt(Instant.now()).build();
    }
}

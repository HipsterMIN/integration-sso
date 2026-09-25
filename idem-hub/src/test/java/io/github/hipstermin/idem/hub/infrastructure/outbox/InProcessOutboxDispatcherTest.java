package io.github.hipstermin.idem.hub.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.event.AuthEvent;
import io.github.hipstermin.idem.common.event.HandoffEvent;
import io.github.hipstermin.idem.common.event.SessionAdvisoryEvent;
import io.github.hipstermin.idem.hub.fe.kafka.FeAdvisoryConsumer;
import io.github.hipstermin.idem.hub.kafka.HandoffEventConsumer;
import io.github.hipstermin.idem.hub.kafka.QsignAuthEventConsumer;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * D1-b: Kafka 없는 배포에서 idem_hub.outbox 레코드가 토픽별로 같은 프로세스의 컨슈머 진입점으로 배달되는지.
 * ObjectMapper 는 Boot 가 만드는 것과 같은 빌더(파라미터 이름 모듈 포함)로 만든다 — 이벤트 클래스는 @JsonCreator 없이
 * 생성자 파라미터 이름으로 역직렬화된다.
 */
@ExtendWith(MockitoExtension.class)
class InProcessOutboxDispatcherTest {

    @Mock QsignAuthEventConsumer qsignAuthEventConsumer;
    @Mock FeAdvisoryConsumer     feAdvisoryConsumer;
    @Mock HandoffEventConsumer   handoffEventConsumer;

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private InProcessOutboxDispatcher sut;

    @BeforeEach
    void setUp() {
        sut = new InProcessOutboxDispatcher(objectMapper, qsignAuthEventConsumer, feAdvisoryConsumer, handoffEventConsumer);
        ReflectionTestUtils.setField(sut, "authEventsTopic", "idem.gate.auth.events");
        ReflectionTestUtils.setField(sut, "sessionAdvisoryTopic", "platform.session.advisory");
        ReflectionTestUtils.setField(sut, "handoffEventsTopic", "idem.hub.handoff.events");
    }

    @Test
    @DisplayName("idem.gate.auth.events → QsignAuthEventConsumer.handle — eventId 가 보존되어 멱등 처리가 유효하다")
    void authEvents() throws Exception {
        AuthEvent original = new AuthEvent(AuthEvent.TYPE_AUTH_COMPLETED, "idem-hub", "cid-1", "user-1", 1L,
                "ar-1", AuthResult.AuthLevel.L2, "MOCK", null, AuthResult.VerificationResult.SUCCESS);
        String payload = objectMapper.writeValueAsString(original);

        sut.dispatch(record("idem.gate.auth.events", original.getEventType(), payload));

        ArgumentCaptor<AuthEvent> captor = ArgumentCaptor.forClass(AuthEvent.class);
        then(qsignAuthEventConsumer).should().handle(captor.capture());
        AuthEvent delivered = captor.getValue();
        assertThat(delivered.getEventId()).isEqualTo(original.getEventId());
        assertThat(delivered.getEventType()).isEqualTo(AuthEvent.TYPE_AUTH_COMPLETED);
        assertThat(delivered.getCorrelationId()).isEqualTo("cid-1");
        assertThat(delivered.getAuthResultId()).isEqualTo("ar-1");
        assertThat(delivered.getAuthLevel()).isEqualTo(AuthResult.AuthLevel.L2);
        assertThat(delivered.getProviderCode()).isEqualTo("MOCK");
    }

    @Test
    @DisplayName("platform.session.advisory → FeAdvisoryConsumer.handle — SessionAdvisoryPublisher 의 축약 payload 도 읽힌다")
    void sessionAdvisory_subsetPayload() {
        // SessionAdvisoryPublisher.buildAdvisoryPayload() 가 만드는 형태(부분 필드)
        String payload = """
                {"eventId":"evt-adv-1","eventType":"MANDATORY_SECURITY_TERMINATE","qimUserId":"user-9",\
                "correlationId":"cid-9","severity":"MANDATORY","reason":"AUTH_LOCKED:MOCK","occurredAt":"%s"}"""
                .formatted(Instant.now());

        sut.dispatch(record("platform.session.advisory", "MANDATORY_SECURITY_TERMINATE", payload));

        ArgumentCaptor<SessionAdvisoryEvent> captor = ArgumentCaptor.forClass(SessionAdvisoryEvent.class);
        then(feAdvisoryConsumer).should().handle(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(SessionAdvisoryEvent.TYPE_MANDATORY_SECURITY);
        assertThat(captor.getValue().getQimUserId()).isEqualTo("user-9");
        assertThat(captor.getValue().getReason()).isEqualTo("AUTH_LOCKED:MOCK");
        assertThat(captor.getValue().getEventId()).isEqualTo("evt-adv-1");
    }

    @Test
    @DisplayName("idem.hub.handoff.events → HandoffEventConsumer.handle")
    void handoffEvents() throws Exception {
        HandoffEvent original = new HandoffEvent(HandoffEvent.TYPE_HANDOFF_ISSUED, "idem-hub", "cid-2", "user-2", 1L,
                "ticket-2", "AGENCY001", "ar-2", "ISSUED", null);
        String payload = objectMapper.writeValueAsString(original);

        sut.dispatch(record("idem.hub.handoff.events", original.getEventType(), payload));

        ArgumentCaptor<HandoffEvent> captor = ArgumentCaptor.forClass(HandoffEvent.class);
        then(handoffEventConsumer).should().handle(captor.capture());
        assertThat(captor.getValue().getEventId()).isEqualTo(original.getEventId());
        assertThat(captor.getValue().getTicketId()).isEqualTo("ticket-2");
        assertThat(captor.getValue().getAgencyCode()).isEqualTo("AGENCY001");
    }

    @Test
    @DisplayName("이 프로세스에 소비자가 없는 토픽은 실패로 돌려 릴레이가 FAILED 로 남기게 한다")
    void unknownTopic() {
        assertThatThrownBy(() -> sut.dispatch(record("idem.registry.user.events", "USER_WITHDRAWN", "{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idem.registry.user.events");
        then(qsignAuthEventConsumer).should(never()).handle(any());
        then(feAdvisoryConsumer).should(never()).handle(any());
        then(handoffEventConsumer).should(never()).handle(any());
    }

    @Test
    @DisplayName("깨진 payload 는 IllegalStateException(원인 포함) — 핸들러 호출 없음")
    void malformedPayload() {
        assertThatThrownBy(() -> sut.dispatch(record("idem.gate.auth.events", "AUTH_COMPLETED", "{not-json")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("역직렬화");
        then(qsignAuthEventConsumer).should(never()).handle(any());
    }

    private static IdoOutboxRecord record(String topic, String type, String payload) {
        return IdoOutboxRecord.builder()
                .eventId("evt-" + topic).eventType(type).partitionKey("pk").aggregateId("agg")
                .eventVersion(1L).payload(payload).topic(topic).status("PENDING").retryCount(0)
                .createdAt(Instant.now()).build();
    }
}

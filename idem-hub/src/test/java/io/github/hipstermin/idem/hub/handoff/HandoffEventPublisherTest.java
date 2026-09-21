package io.github.hipstermin.idem.hub.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.event.HandoffEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class HandoffEventPublisherTest {

    @Mock KafkaTemplate<String, Object> kafkaTemplate;
    @Mock JdbcTemplate jdbcTemplate;

    private final HandoffEvent event = new HandoffEvent(HandoffEvent.TYPE_HANDOFF_ISSUED, "ido", "cid", "user-1", 1L,
            "ticket-1", "AGENCY001", "ar-1", "ISSUED", null);

    private HandoffEventPublisher sut(boolean kafkaEnabled) {
        HandoffEventPublisher p = new HandoffEventPublisher(kafkaTemplate, jdbcTemplate, new ObjectMapper()
                .findAndRegisterModules());
        ReflectionTestUtils.setField(p, "kafkaEnabled", kafkaEnabled);
        ReflectionTestUtils.setField(p, "handoffTopic", "ido.handoff.events");
        return p;
    }

    @Test
    @DisplayName("Kafka 꺼짐(기본) → ido.outbox INSERT (topic=ido.handoff.events, aggregate=ticketId), Kafka 미호출")
    void disabled_outbox() {
        sut(false).publish(event, "user-1");

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        then(jdbcTemplate).should().update(anyString(), args.capture());
        Object[] a = args.getValue();
        assertThat(a[0]).isEqualTo(event.getEventId());
        assertThat(a[1]).isEqualTo(HandoffEvent.TYPE_HANDOFF_ISSUED);
        assertThat(a[2]).isEqualTo("user-1");
        assertThat(a[3]).isEqualTo("ticket-1");
        assertThat((String) a[5]).contains("\"ticketId\":\"ticket-1\"").contains("\"eventId\":\"" + event.getEventId() + "\"");
        assertThat(a[6]).isEqualTo("ido.handoff.events");
        then(kafkaTemplate).should(never()).send(anyString(), any(), any());
    }

    @Test
    @DisplayName("Kafka 켜짐 → 종전처럼 즉시 발행, outbox 미사용")
    void enabled_kafka() {
        sut(true).publish(event, "user-1");

        then(kafkaTemplate).should().send(eq("ido.handoff.events"), eq("user-1"), eq(event));
        then(jdbcTemplate).should(never()).update(anyString(), any(Object[].class));
    }
}

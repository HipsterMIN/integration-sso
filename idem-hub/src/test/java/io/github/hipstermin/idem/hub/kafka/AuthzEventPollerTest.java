package io.github.hipstermin.idem.hub.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.event.AuthorizationEvent;
import io.github.hipstermin.idem.hub.infrastructure.AuthzEventRecord;
import io.github.hipstermin.idem.hub.infrastructure.QAuthzClient;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("1.1 AuthzEventPoller — authz 피드를 워터마크로 순회")
class AuthzEventPollerTest {

    @Mock QAuthzClient authz;
    @Mock AuthzEventConsumer consumer;
    @Mock IdempotentEventStore idempotent;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    AuthzEventPoller sut;
    final Instant t1 = Instant.parse("2026-09-28T00:00:01Z");
    final Instant t2 = Instant.parse("2026-09-28T00:00:02Z");

    @BeforeEach
    void setUp() {
        given(redis.opsForValue()).willReturn(values);
        sut = new AuthzEventPoller(authz, consumer, idempotent, redis, mapper);
    }

    private AuthzEventRecord rec(AuthorizationEvent e, Instant at) {
        return new AuthzEventRecord(e.getEventId(), e.getEventType(), "AG1:*", null, at, mapper.valueToTree(e));
    }

    @Test
    @DisplayName("순서대로 소비하고 마지막 (createdAt, eventId) 를 워터마크로")
    void processesInOrder() {
        var e1 = AuthorizationEvent.assigned("u1", "AG1", "a", null, "API", "r", "c1");
        var e2 = AuthorizationEvent.unassigned("u1", "AG1", "a", "r", "c2");
        given(values.get(AuthzEventPoller.WATERMARK_KEY)).willReturn(t1.minusSeconds(60) + "|");
        given(authz.fetchAssignmentEvents(eq(t1.minusSeconds(60)), eq(""), anyInt(), anyString()))
                .willReturn(List.of(rec(e1, t1), rec(e2, t2)));

        assertThat(sut.pollOnce()).isEqualTo(2);

        ArgumentCaptor<AuthorizationEvent> ev = ArgumentCaptor.forClass(AuthorizationEvent.class);
        verify(consumer, times(2)).handle(ev.capture());
        assertThat(ev.getAllValues()).extracting(AuthorizationEvent::getEventType)
                .containsExactly(AuthorizationEvent.TYPE_ASSIGNED, AuthorizationEvent.TYPE_UNASSIGNED);
        verify(values).set(AuthzEventPoller.WATERMARK_KEY, t2 + "|" + e2.getEventId());
    }

    @Test
    @DisplayName("실패한 이벤트 앞까지만 워터마크를 옮기고 멈춘다; max-failures 뒤엔 FAILED 로 기록하고 건너뛴다")
    void failureThenPoison() {
        ReflectionTestUtils.setField(sut, "maxFailures", 2);
        var bad = AuthorizationEvent.revoked("u1", "AG1", "R", "a", "r", "c");
        given(values.get(AuthzEventPoller.WATERMARK_KEY)).willReturn(null);
        given(authz.fetchAssignmentEvents(any(), any(), anyInt(), anyString())).willReturn(List.of(rec(bad, t1)));
        willThrow(new IllegalStateException("registry down")).given(consumer).handle(any());

        assertThat(sut.pollOnce()).isEqualTo(0);
        verify(idempotent, never()).markProcessed(any(), any(), any(), any());
        assertThat(sut.pollOnce()).isEqualTo(1);
        verify(idempotent).markProcessed(bad.getEventId(), AuthzEventConsumer.CONSUMER_GROUP, AuthorizationEvent.TYPE_REVOKED, "FAILED");
    }

    @Test
    @DisplayName("피드 조회 자체가 실패하면 poll() 이 삼키고 다음 주기에")
    void feedFailureSwallowedByPoll() {
        given(values.get(AuthzEventPoller.WATERMARK_KEY)).willReturn(null);
        given(authz.fetchAssignmentEvents(any(), any(), anyInt(), anyString())).willThrow(new IllegalStateException("authz down"));
        sut.poll();
        verify(consumer, never()).handle(any());
    }
}

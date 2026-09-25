package io.github.hipstermin.idem.hub.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.event.UserEvent;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import io.github.hipstermin.idem.hub.infrastructure.QimUserEventRecord;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

/** D3: Kafka 없는 설치의 상태 변경 전파 — registry 이벤트 피드를 워터마크로 폴링해 컨슈머에 넘긴다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RegistryOutboxPoller")
class RegistryOutboxPollerTest {

    @Mock QimClient qim;
    @Mock QimEventConsumer consumer;
    @Mock IdempotentEventStore idempotent;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    RegistryOutboxPoller sut;
    final Instant t1 = Instant.parse("2026-09-25T00:00:01Z");
    final Instant t2 = Instant.parse("2026-09-25T00:00:02Z");

    @BeforeEach
    void setUp() {
        given(redis.opsForValue()).willReturn(values);
        sut = new RegistryOutboxPoller(qim, consumer, idempotent, redis, mapper);
    }

    private QimUserEventRecord rec(String id, Instant at, String type) {
        UserEvent e = UserEvent.builder().eventId(id).eventType(type).qimUserId("u1").eventVersion(1L).userStatus("SUSPENDED").build();
        return new QimUserEventRecord(id, type, "u1", 1L, at, mapper.valueToTree(e));
    }

    @Test
    @DisplayName("피드의 이벤트를 순서대로 처리하고 마지막 (createdAt, eventId) 를 워터마크로 저장")
    void processesInOrderAndSavesWatermark() {
        given(values.get(RegistryOutboxPoller.WATERMARK_KEY)).willReturn(t1.minusSeconds(60) + "|");
        given(qim.fetchUserEvents(eq(t1.minusSeconds(60)), eq(""), anyInt(), anyString()))
                .willReturn(List.of(rec("e1", t1, UserEvent.TYPE_SUSPENDED), rec("e2", t2, UserEvent.TYPE_UPDATED)));

        int n = sut.pollOnce();

        assertThat(n).isEqualTo(2);
        ArgumentCaptor<UserEvent> ev = ArgumentCaptor.forClass(UserEvent.class);
        verify(consumer, times(2)).handle(ev.capture());
        assertThat(ev.getAllValues()).extracting(UserEvent::getEventId).containsExactly("e1", "e2");
        verify(values).set(RegistryOutboxPoller.WATERMARK_KEY, t2 + "|e2");
    }

    @Test
    @DisplayName("워터마크가 없으면 lookback 만큼 과거부터 시작한다 (중복은 processed_event 가 거른다)")
    void noWatermarkStartsFromLookback() {
        given(values.get(RegistryOutboxPoller.WATERMARK_KEY)).willReturn(null);
        ReflectionTestUtils.setField(sut, "initialLookbackHours", 1L);
        given(qim.fetchUserEvents(any(), eq(""), anyInt(), anyString())).willReturn(List.of());
        sut.pollOnce();
        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(qim).fetchUserEvents(since.capture(), eq(""), anyInt(), anyString());
        assertThat(since.getValue()).isBetween(Instant.now().minusSeconds(3700), Instant.now().minusSeconds(3500));
        verify(values, never()).set(anyString(), anyString());
    }

    @Test
    @DisplayName("한 이벤트가 실패하면 그 앞까지만 워터마크를 옮기고 멈춘다 — 다음 주기에 재시도")
    void failureStopsBeforeFailedEvent() {
        given(values.get(RegistryOutboxPoller.WATERMARK_KEY)).willReturn(null);
        given(qim.fetchUserEvents(any(), any(), anyInt(), anyString()))
                .willReturn(List.of(rec("e1", t1, UserEvent.TYPE_SUSPENDED), rec("e2", t2, UserEvent.TYPE_SUSPENDED), rec("e3", t2, UserEvent.TYPE_SUSPENDED)));
        // e1 은 성공, e2 부터 실패하도록
        org.mockito.Mockito.doNothing().doThrow(new IllegalStateException("db down")).when(consumer).handle(any());

        int n = sut.pollOnce();

        assertThat(n).isEqualTo(1);
        verify(consumer, times(2)).handle(any());   // e1 성공, e2 실패, e3 은 시도하지 않음
        verify(values).set(RegistryOutboxPoller.WATERMARK_KEY, t1 + "|e1");
        verify(idempotent, never()).markProcessed(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("같은 이벤트가 max-failures 번 실패하면 FAILED 로 기록하고 건너뛴다 (독약 이벤트가 전파를 막지 않게)")
    void poisonEventSkippedAfterMaxFailures() {
        ReflectionTestUtils.setField(sut, "maxFailures", 2);
        given(values.get(RegistryOutboxPoller.WATERMARK_KEY)).willReturn(null);
        given(qim.fetchUserEvents(any(), any(), anyInt(), anyString()))
                .willReturn(List.of(rec("bad", t1, UserEvent.TYPE_SUSPENDED), rec("e2", t2, UserEvent.TYPE_UPDATED)));
        willThrow(new IllegalStateException("boom")).given(consumer).handle(any());

        assertThat(sut.pollOnce()).isEqualTo(0);        // bad 1회 실패 — 멈춤, 워터마크 그대로
        verify(idempotent, never()).markProcessed(anyString(), anyString(), anyString(), anyString());
        assertThat(sut.pollOnce()).isEqualTo(1);        // bad 2회 실패 → FAILED 기록·건너뜀(1건), e2 는 첫 실패라 멈춤
        verify(idempotent).markProcessed("bad", RegistryOutboxPoller.CONSUMER_GROUP, UserEvent.TYPE_SUSPENDED, "FAILED");
        verify(values).set(RegistryOutboxPoller.WATERMARK_KEY, t1 + "|bad");
    }

    @Test
    void poisonEventMarkedFailed() {
        ReflectionTestUtils.setField(sut, "maxFailures", 1);
        given(values.get(RegistryOutboxPoller.WATERMARK_KEY)).willReturn(null);
        given(qim.fetchUserEvents(any(), any(), anyInt(), anyString()))
                .willReturn(List.of(rec("bad", t1, UserEvent.TYPE_SUSPENDED)));
        willThrow(new IllegalStateException("boom")).given(consumer).handle(any());

        assertThat(sut.pollOnce()).isEqualTo(1);
        verify(idempotent).markProcessed("bad", RegistryOutboxPoller.CONSUMER_GROUP, UserEvent.TYPE_SUSPENDED, "FAILED");
        verify(values).set(RegistryOutboxPoller.WATERMARK_KEY, t1 + "|bad");
    }

    @Test
    @DisplayName("피드 조회 실패는 pollOnce 밖으로 나가고 poll() 이 삼킨다 — 워터마크는 그대로")
    void feedFailureLeavesWatermark() {
        given(values.get(RegistryOutboxPoller.WATERMARK_KEY)).willReturn(t1 + "|e1");
        given(qim.fetchUserEvents(any(), any(), anyInt(), anyString())).willThrow(new RuntimeException("registry down"));
        sut.poll();
        verify(values, never()).set(anyString(), anyString());
        InOrder io = inOrder(qim);
        io.verify(qim).fetchUserEvents(eq(t1), eq("e1"), anyInt(), anyString());
    }
}

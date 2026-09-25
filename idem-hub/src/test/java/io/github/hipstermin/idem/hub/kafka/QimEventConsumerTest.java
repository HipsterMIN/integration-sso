package io.github.hipstermin.idem.hub.kafka;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.hipstermin.idem.common.event.UserEvent;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import io.github.hipstermin.idem.hub.infrastructure.LastEventVersionStore;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import io.github.hipstermin.idem.hub.infrastructure.UserStatusCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** D3: 정지·탈퇴 이벤트는 상태 캐시만이 아니라 살아 있는 FE 세션까지 끝낸다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("QimEventConsumer.handle — 상태 변경 전파")
class QimEventConsumerTest {

    @Mock LastEventVersionStore versions;
    @Mock UserStatusCache cache;
    @Mock IdempotentEventStore idempotent;
    @Mock QimClient qim;
    @Mock FeSessionService sessions;
    QimEventConsumer sut;

    @BeforeEach
    void setUp() {
        sut = new QimEventConsumer(versions, cache, idempotent, qim, sessions);
        given(idempotent.isAlreadyProcessed(anyString(), anyString())).willReturn(false);
        given(versions.get(anyString())).willReturn(null);
    }

    private static UserEvent event(String id, String type, String status, long version) {
        return UserEvent.builder().eventId(id).eventType(type).qimUserId("u1").eventVersion(version)
                .userStatus(status).needsSync(false).sourceSystem("q-im").build();
    }

    @Test
    void suspendedInvalidatesSessions() {
        sut.handle(event("e1", UserEvent.TYPE_SUSPENDED, "SUSPENDED", 2L));
        verify(cache).invalidate("u1");
        verify(sessions).invalidateByQimUserId("u1", UserEvent.TYPE_SUSPENDED);
        verify(idempotent).markProcessed("e1", "ido-qim-consumer", UserEvent.TYPE_SUSPENDED, "OK");
        verify(versions).put("ido-qim-consumer:u1", 2L);
    }

    @Test
    void withdrawnInvalidatesSessions() {
        sut.handle(event("e2", UserEvent.TYPE_WITHDRAWN, "WITHDRAWN", 3L));
        verify(sessions).invalidateByQimUserId("u1", UserEvent.TYPE_WITHDRAWN);
    }

    @Test
    @DisplayName("타입이 USER_UPDATED 여도 실린 상태가 정지·탈퇴(예정)면 세션을 끝낸다")
    void statusFieldAlsoCounts() {
        sut.handle(event("e3", UserEvent.TYPE_UPDATED, "WITHDRAWAL_SCHEDULED", 4L));
        verify(sessions).invalidateByQimUserId("u1", UserEvent.TYPE_UPDATED);
    }

    @Test
    void activeUpdateKeepsSessions() {
        sut.handle(event("e4", UserEvent.TYPE_UPDATED, "ACTIVE", 5L));
        verify(cache).invalidate("u1");
        verify(sessions, never()).invalidateByQimUserId(anyString(), anyString());
    }

    @Test
    void duplicateSkipped() {
        given(idempotent.isAlreadyProcessed("e1", "ido-qim-consumer")).willReturn(true);
        sut.handle(event("e1", UserEvent.TYPE_SUSPENDED, "SUSPENDED", 2L));
        verify(cache, never()).invalidate(anyString());
        verify(sessions, never()).invalidateByQimUserId(anyString(), anyString());
    }

    @Test
    void staleVersionSkipped() {
        given(versions.get("ido-qim-consumer:u1")).willReturn(9L);
        sut.handle(event("e5", UserEvent.TYPE_SUSPENDED, "SUSPENDED", 2L));
        verify(sessions, never()).invalidateByQimUserId(anyString(), anyString());
        verify(idempotent).markProcessed(eq("e5"), anyString(), anyString(), eq("SKIPPED"));
    }
}

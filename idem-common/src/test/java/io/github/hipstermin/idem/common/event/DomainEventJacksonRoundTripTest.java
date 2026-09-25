package io.github.hipstermin.idem.common.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.domain.AuthResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * D1-b 가드: 모든 도메인 이벤트는 Jackson 으로 왕복(직렬화 → 역직렬화)돼야 한다.
 * Kafka JsonDeserializer 와 hub 의 아웃박스 프로세스 내 배달({@code InProcessOutboxDispatcher})이 같은 경로를 쓴다.
 * eventId·occurredAt 이 보존되어야 컨슈머 멱등 처리(processed_event)가 유효하다.
 */
class DomainEventJacksonRoundTripTest {

    private final ObjectMapper om = Jackson2ObjectMapperBuilder.json().build();

    @Test
    void authEvent() throws Exception {
        AuthEvent e = new AuthEvent(AuthEvent.TYPE_AUTH_LOCKED, "idem-hub", "cid", "user", 1L,
                "ar", AuthResult.AuthLevel.L2, "MOCK", "tx", AuthResult.VerificationResult.FAIL);
        AuthEvent back = om.readValue(om.writeValueAsString(e), AuthEvent.class);
        assertThat(back.getEventId()).isEqualTo(e.getEventId());
        assertThat(back.getOccurredAt()).isEqualTo(e.getOccurredAt());
        assertThat(back.getEventType()).isEqualTo(AuthEvent.TYPE_AUTH_LOCKED);
        assertThat(back.getAuthLevel()).isEqualTo(AuthResult.AuthLevel.L2);
        assertThat(back.getVerificationResult()).isEqualTo(AuthResult.VerificationResult.FAIL);
        assertThat(back.getProviderTxId()).isEqualTo("tx");
    }

    @Test
    void sessionAdvisoryEvent_부분_payload도_읽힌다() throws Exception {
        SessionAdvisoryEvent e = new SessionAdvisoryEvent(SessionAdvisoryEvent.TYPE_MANDATORY_SECURITY, "idem-hub", "cid",
                "user", 1L, "MANDATORY", null, "AUTH_LOCKED", null);
        SessionAdvisoryEvent back = om.readValue(om.writeValueAsString(e), SessionAdvisoryEvent.class);
        assertThat(back.getEventId()).isEqualTo(e.getEventId());
        assertThat(back.getReason()).isEqualTo("AUTH_LOCKED");
        assertThat(back.getAgencyCode()).isNull();

        SessionAdvisoryEvent subset = om.readValue(
                "{\"eventId\":\"x\",\"eventType\":\"SESSION_LOGOUT_HINT\",\"qimUserId\":\"u\",\"reason\":\"r\"}",
                SessionAdvisoryEvent.class);
        assertThat(subset.getEventId()).isEqualTo("x");
        assertThat(subset.getEventType()).isEqualTo(SessionAdvisoryEvent.TYPE_SESSION_LOGOUT_HINT);
        assertThat(subset.getSeverity()).isNull();
    }

    @Test
    void handoffEvent() throws Exception {
        HandoffEvent e = new HandoffEvent(HandoffEvent.TYPE_HANDOFF_REVOKED, "idem-hub", "cid", "user", 1L,
                "ticket", "AGENCY001", "ar", "REVOKED", "SUSPICIOUS");
        HandoffEvent back = om.readValue(om.writeValueAsString(e), HandoffEvent.class);
        assertThat(back.getEventId()).isEqualTo(e.getEventId());
        assertThat(back.getTicketId()).isEqualTo("ticket");
        assertThat(back.getRevokeReason()).isEqualTo("SUSPICIOUS");
    }

    @Test
    void userEvent_and_others() throws Exception {
        for (Class<? extends DomainEvent> type : java.util.List.of(
                UserEvent.class, AuditLogEvent.class, WebhookDispatchEvent.class, AuthorizationEvent.class)) {
            // 빌더 기반 역직렬화가 구성됐는지만 확인 (필드 값은 타입별 테스트가 다룬다)
            DomainEvent back = om.readValue("{\"eventId\":\"e-1\",\"eventType\":\"T\",\"qimUserId\":\"u\"}", type);
            assertThat(back.getEventId()).as(type.getSimpleName()).isEqualTo("e-1");
            assertThat(back.getEventType()).isEqualTo("T");
        }
    }
}

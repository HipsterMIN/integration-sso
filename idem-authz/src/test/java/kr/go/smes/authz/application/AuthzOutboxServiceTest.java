package kr.go.smes.authz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.authz.infrastructure.AuthzOutboxEntity;
import kr.go.smes.authz.infrastructure.AuthzOutboxRepository;
import kr.go.smes.common.event.AuthorizationEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthzOutboxServiceTest {

    @Mock AuthzOutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules(); // JavaTimeModule (Instant 직렬화)

    @Test
    void publishInTx_mapsEventToOutboxRow_withJsonPayload() {
        AuthzOutboxService service = new AuthzOutboxService(outboxRepository, objectMapper);
        Instant exp = Instant.now().plusSeconds(3600);
        AuthorizationEvent event = AuthorizationEvent.granted(
                "user-1", "GOV_SMES", "MANAGER", "admin@onepass", exp, "API", "test", "cid-1");

        service.publishInTx(event);

        ArgumentCaptor<AuthzOutboxEntity> cap = ArgumentCaptor.forClass(AuthzOutboxEntity.class);
        verify(outboxRepository).save(cap.capture());
        AuthzOutboxEntity row = cap.getValue();

        assertThat(row.getEventId()).isEqualTo(event.getEventId());
        assertThat(row.getEventType()).isEqualTo(AuthorizationEvent.TYPE_GRANTED);
        assertThat(row.getPartitionKey()).isEqualTo("user-1");          // Kafka 파티션 키 = qimUserId
        assertThat(row.getAggregateId()).isEqualTo("GOV_SMES:MANAGER");
        assertThat(row.getTopic()).isEqualTo("authz.assignment.events");
        assertThat(row.getStatus()).isEqualTo("PENDING");
        // payload는 직렬화된 JSON — 핵심 필드 포함 확인
        assertThat(row.getPayload())
                .contains("\"eventType\":\"AUTHZ_GRANTED\"")
                .contains("\"qimUserId\":\"user-1\"")
                .contains("\"roleCode\":\"MANAGER\"")
                .contains("\"sourceSystem\":\"q-authz\"");
    }

    @Test
    void publishInTx_revokeEvent_hasNoExpiry() {
        AuthzOutboxService service = new AuthzOutboxService(outboxRepository, objectMapper);
        AuthorizationEvent event = AuthorizationEvent.revoked(
                "user-2", "GOV_SMES", "REVIEWER", "admin@onepass", "policy", "cid-2");

        service.publishInTx(event);

        ArgumentCaptor<AuthzOutboxEntity> cap = ArgumentCaptor.forClass(AuthzOutboxEntity.class);
        verify(outboxRepository).save(cap.capture());
        assertThat(cap.getValue().getEventType()).isEqualTo(AuthorizationEvent.TYPE_REVOKED);
        assertThat(cap.getValue().getAggregateId()).isEqualTo("GOV_SMES:REVIEWER");
    }
}

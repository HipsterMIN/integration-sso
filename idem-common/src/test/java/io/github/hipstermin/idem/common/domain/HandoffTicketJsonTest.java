package io.github.hipstermin.idem.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * HandoffTicket JSON 왕복 — TicketRepositoryImpl 이 Redis 에 JSON 으로 저장한 티켓을 다시 읽는 경로.
 *
 * <p>2026-09-08 idem-hub integrationTest 에서 {@code @Builder} 만 있는 불변 클래스라 Jackson 이 생성자를 찾지 못해
 * {@code findById} 가 항상 empty 를 돌려주던 결함을 잡았다 ({@code @Jacksonized} 로 수정).
 */
@DisplayName("HandoffTicket — JSON 직렬화/역직렬화 왕복")
class HandoffTicketJsonTest {

    private final ObjectMapper om = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    @Test
    @DisplayName("빌더로 만든 티켓을 JSON 으로 쓰고 다시 읽으면 모든 필드가 보존된다")
    void roundTrip_preservesAllFields() throws Exception {
        Instant issued  = Instant.parse("2026-09-08T00:00:00Z");
        Instant expires = issued.plusSeconds(60);
        HandoffTicket original = HandoffTicket.builder()
                .ticketId("t-1").correlationId("c-1").agencyCode("AGENCY").qimUserId("u-1")
                .authResultId("a-1").authLevel(AuthResult.AuthLevel.L1)
                .state(HandoffTicket.TicketState.ISSUED)
                .issuedAt(issued).expiresAt(expires)
                .encryptedPayload("v1.iv.ct").signature("sig")
                .build();

        String json = om.writeValueAsString(original);
        HandoffTicket restored = om.readValue(json, HandoffTicket.class);

        assertThat(json).doesNotContain("expired").doesNotContain("usable"); // @JsonIgnore 파생 필드 미출력
        assertThat(restored.getTicketId()).isEqualTo("t-1");
        assertThat(restored.getCorrelationId()).isEqualTo("c-1");
        assertThat(restored.getAgencyCode()).isEqualTo("AGENCY");
        assertThat(restored.getQimUserId()).isEqualTo("u-1");
        assertThat(restored.getAuthResultId()).isEqualTo("a-1");
        assertThat(restored.getAuthLevel()).isEqualTo(AuthResult.AuthLevel.L1);
        assertThat(restored.getState()).isEqualTo(HandoffTicket.TicketState.ISSUED);
        assertThat(restored.getIssuedAt()).isEqualTo(issued);
        assertThat(restored.getExpiresAt()).isEqualTo(expires);
        assertThat(restored.getEncryptedPayload()).isEqualTo("v1.iv.ct");
        assertThat(restored.getSignature()).isEqualTo("sig");
    }
}

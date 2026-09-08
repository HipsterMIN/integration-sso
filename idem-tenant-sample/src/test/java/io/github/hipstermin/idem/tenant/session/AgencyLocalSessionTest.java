package io.github.hipstermin.idem.tenant.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AgencyLocalSession — 단위 테스트
 *
 * <p>설계서 14.3절 세션 불변 객체 / 만료 판단 로직 검증
 */
@DisplayName("AgencyLocalSession 단위 테스트")
class AgencyLocalSessionTest {

    @Test
    @DisplayName("Builder로 세션 생성 — 모든 필드 정상 저장")
    void testBuildSession() {
        Instant now = Instant.now();
        AgencyLocalSession session = AgencyLocalSession.builder()
                .agencySessionId("sess-001")
                .agencyUserId("user-001")
                .agencySubjectId("subj-001")
                .qimUserId("qim-001")
                .authLevel("L2")
                .ticketId("ticket-001")
                .correlationId("corr-001")
                .createdAt(now)
                .lastActivityAt(now)
                .absoluteExpiresAt(now.plusSeconds(3600))
                .build();

        assertThat(session.getAgencySessionId()).isEqualTo("sess-001");
        assertThat(session.getAgencyUserId()).isEqualTo("user-001");
        assertThat(session.getAgencySubjectId()).isEqualTo("subj-001");
        assertThat(session.getQimUserId()).isEqualTo("qim-001");
        assertThat(session.getAuthLevel()).isEqualTo("L2");
        assertThat(session.getTicketId()).isEqualTo("ticket-001");
        assertThat(session.getCorrelationId()).isEqualTo("corr-001");
        assertThat(session.getCreatedAt()).isEqualTo(now);
        assertThat(session.getLastActivityAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("isAbsoluteExpired() — 만료 시각이 과거면 true 반환")
    void testIsExpired_whenPastExpiresAt() {
        AgencyLocalSession session = AgencyLocalSession.builder()
                .agencySessionId("sess-expired")
                .agencyUserId("user-001")
                .agencySubjectId("subj-001")
                .qimUserId("qim-001")
                .authLevel("L1")
                .ticketId("ticket-001")
                .correlationId("corr-001")
                .createdAt(Instant.now().minusSeconds(7200))
                .lastActivityAt(Instant.now().minusSeconds(3600))
                .absoluteExpiresAt(Instant.now().minusSeconds(1)) // 1초 전 만료
                .build();

        assertThat(session.isAbsoluteExpired()).isTrue();
    }

    @Test
    @DisplayName("isAbsoluteExpired() — 만료 시각이 미래면 false 반환")
    void testIsExpired_whenFutureExpiresAt() {
        AgencyLocalSession session = AgencyLocalSession.builder()
                .agencySessionId("sess-valid")
                .agencyUserId("user-001")
                .agencySubjectId("subj-001")
                .qimUserId("qim-001")
                .authLevel("L3")
                .ticketId("ticket-001")
                .correlationId("corr-001")
                .createdAt(Instant.now())
                .lastActivityAt(Instant.now())
                .absoluteExpiresAt(Instant.now().plusSeconds(3600)) // 1시간 후 만료
                .build();

        assertThat(session.isAbsoluteExpired()).isFalse();
    }

    @Test
    @DisplayName("isAbsoluteExpired() — 정확히 현재 시각 만료는 expired 처리")
    void testIsExpired_boundary() {
        // 미래 시각(+1ms)은 유효
        AgencyLocalSession validSession = AgencyLocalSession.builder()
                .agencySessionId("sess-boundary")
                .agencyUserId("user-001")
                .agencySubjectId("subj-001")
                .qimUserId("qim-001")
                .authLevel("L2")
                .ticketId("t1")
                .correlationId("c1")
                .createdAt(Instant.now())
                .lastActivityAt(Instant.now())
                .absoluteExpiresAt(Instant.now().plusMillis(5000)) // 5초 후
                .build();

        assertThat(validSession.isAbsoluteExpired()).isFalse();
    }

    @Test
    @DisplayName("Builder — authLevel L1/L2/L3 모두 허용")
    void testAuthLevels() {
        for (String level : new String[]{"L1", "L2", "L3"}) {
            AgencyLocalSession session = AgencyLocalSession.builder()
                    .agencySessionId("sess-" + level)
                    .agencyUserId("user")
                    .agencySubjectId("subj")
                    .qimUserId("qim")
                    .authLevel(level)
                    .ticketId("t")
                    .correlationId("c")
                    .createdAt(Instant.now())
                    .lastActivityAt(Instant.now())
                    .absoluteExpiresAt(Instant.now().plusSeconds(1800))
                    .build();

            assertThat(session.getAuthLevel()).isEqualTo(level);
            assertThat(session.isAbsoluteExpired()).isFalse();
        }
    }
}

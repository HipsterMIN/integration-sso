package kr.go.smes.qim.withdrawal;

import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.common.event.UserEvent;
import kr.go.smes.qim.infrastructure.jpa.entity.QimUserJpaEntity;
import kr.go.smes.qim.infrastructure.jpa.repository.QimUserJpaRepository;
import kr.go.smes.qim.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * WithdrawalServiceImpl 단위 테스트 — 탈퇴 4종 흐름 검증
 *
 * <p>커버 케이스 (14개):
 * <ul>
 *   <li>IMMEDIATE — PII 삭제, status=WITHDRAWN, Outbox TYPE_WITHDRAWN 발행</li>
 *   <li>IMMEDIATE — 존재하지 않는 사용자 → IM_USER_NOT_FOUND</li>
 *   <li>IMMEDIATE — 이미 탈퇴된 사용자 → IM_WITHDRAWAL_ALREADY</li>
 *   <li>IMMEDIATE — WITHDRAWAL_SCHEDULED 상태 → IM_WITHDRAWAL_ALREADY</li>
 *   <li>SCHEDULED — 기본 30일 유예 예약</li>
 *   <li>SCHEDULED — scheduledAt 명시 시 해당 일시 사용</li>
 *   <li>SCHEDULED — 취소 (cancelScheduledWithdrawal) → ACTIVE 복원</li>
 *   <li>SCHEDULED — 비WITHDRAWAL_SCHEDULED 상태 취소 → IM_CONVERSION_INVALID_STATE</li>
 *   <li>AGENCY_REQUESTED — reason에 기관코드 포함</li>
 *   <li>ADMIN_FORCED — reason에 관리자ID 포함</li>
 *   <li>processExpiredScheduledWithdrawals — 만료 사용자 일괄 처리</li>
 *   <li>processExpiredScheduledWithdrawals — 만료 없음 시 0 반환</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WithdrawalServiceImpl — 탈퇴 4종 단위 테스트")
class WithdrawalServiceImplTest {

    @Mock private QimUserJpaRepository userRepository;
    @Mock private OutboxService        outboxService;
    @Mock private JdbcTemplate         jdbcTemplate;

    @InjectMocks
    private WithdrawalServiceImpl sut;

    private static final String QIM_USER_ID   = "user-001";
    private static final String CORRELATION_ID = "corr-test-001";

    // ── 공통 헬퍼 ──────────────────────────────────────────────────────────

    private QimUserJpaEntity activeUser() {
        QimUserJpaEntity u = new QimUserJpaEntity();
        u.setQimUserId(QIM_USER_ID);
        u.setStatus("ACTIVE");
        u.setEventVersion(1L);
        return u;
    }

    // ── IMMEDIATE 탈퇴 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("IMMEDIATE 즉시 탈퇴")
    class ImmediateWithdrawal {

        @Test
        @DisplayName("정상 즉시 탈퇴 — PII 삭제 + status=WITHDRAWN + Outbox TYPE_WITHDRAWN")
        void immediate_success() {
            given(userRepository.findById(QIM_USER_ID)).willReturn(Optional.of(activeUser()));
            given(userRepository.markWithdrawn(
                    anyString(), anyString(), any(), anyString(), anyString(), any()))
                    .willReturn(1);

            WithdrawalRequest req = WithdrawalRequest.builder()
                    .type(WithdrawalType.IMMEDIATE)
                    .reason("사용자 요청")
                    .correlationId(CORRELATION_ID)
                    .build();

            WithdrawalResponse result = sut.withdraw(QIM_USER_ID, req);

            assertThat(result.getType()).isEqualTo(WithdrawalType.IMMEDIATE);
            assertThat(result.getResultStatus()).isEqualTo("WITHDRAWN");
            assertThat(result.getQimUserId()).isEqualTo(QIM_USER_ID);

            // PII 삭제 SQL 호출 확인
            then(jdbcTemplate).should().update(anyString(), eq(QIM_USER_ID));

            // markWithdrawn 호출 확인 (status=WITHDRAWN, type=IMMEDIATE)
            then(userRepository).should().markWithdrawn(
                    eq(QIM_USER_ID), eq("WITHDRAWN"), any(), anyString(),
                    eq("IMMEDIATE"), any());

            // Outbox TYPE_WITHDRAWN 발행 확인
            ArgumentCaptor<UserEvent> eventCaptor = ArgumentCaptor.forClass(UserEvent.class);
            then(outboxService).should().publishInTx(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getEventType())
                    .isEqualTo(UserEvent.TYPE_WITHDRAWN);
        }

        @Test
        @DisplayName("존재하지 않는 사용자 → IM_USER_NOT_FOUND 예외")
        void immediate_userNotFound() {
            given(userRepository.findById(QIM_USER_ID)).willReturn(Optional.empty());

            WithdrawalRequest req = WithdrawalRequest.builder()
                    .type(WithdrawalType.IMMEDIATE).reason("요청").build();

            assertThatThrownBy(() -> sut.withdraw(QIM_USER_ID, req))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_USER_NOT_FOUND);
        }

        @Test
        @DisplayName("이미 탈퇴된 사용자 → IM_WITHDRAWAL_ALREADY 예외")
        void immediate_alreadyWithdrawn() {
            QimUserJpaEntity withdrawn = activeUser();
            withdrawn.setStatus("WITHDRAWN");
            given(userRepository.findById(QIM_USER_ID)).willReturn(Optional.of(withdrawn));

            WithdrawalRequest req = WithdrawalRequest.builder()
                    .type(WithdrawalType.IMMEDIATE).reason("요청").build();

            assertThatThrownBy(() -> sut.withdraw(QIM_USER_ID, req))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_WITHDRAWAL_ALREADY);
        }

        @Test
        @DisplayName("WITHDRAWAL_SCHEDULED 상태에서 IMMEDIATE 요청 → IM_WITHDRAWAL_ALREADY")
        void immediate_scheduledState_blocked() {
            QimUserJpaEntity scheduled = activeUser();
            scheduled.setStatus("WITHDRAWAL_SCHEDULED");
            given(userRepository.findById(QIM_USER_ID)).willReturn(Optional.of(scheduled));

            WithdrawalRequest req = WithdrawalRequest.builder()
                    .type(WithdrawalType.IMMEDIATE).reason("요청").build();

            assertThatThrownBy(() -> sut.withdraw(QIM_USER_ID, req))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_WITHDRAWAL_ALREADY);
        }
    }

    // ── SCHEDULED 예약 탈퇴 ───────────────────────────────────────────────

    @Nested
    @DisplayName("SCHEDULED 예약 탈퇴")
    class ScheduledWithdrawal {

        @Test
        @DisplayName("기본 30일 유예 예약 — scheduledAt null이면 +30일 설정")
        void scheduled_defaultTtl() {
            given(userRepository.findById(QIM_USER_ID)).willReturn(Optional.of(activeUser()));
            given(userRepository.save(any())).willReturn(null);

            WithdrawalRequest req = WithdrawalRequest.builder()
                    .type(WithdrawalType.SCHEDULED)
                    .reason("30일 후 탈퇴")
                    .build();

            Instant before = Instant.now().plus(29, ChronoUnit.DAYS);
            WithdrawalResponse result = sut.withdraw(QIM_USER_ID, req);
            Instant after = Instant.now().plus(31, ChronoUnit.DAYS);

            assertThat(result.getResultStatus()).isEqualTo("WITHDRAWAL_SCHEDULED");
            assertThat(result.getScheduledAt()).isBetween(before, after);
        }

        @Test
        @DisplayName("scheduledAt 명시 시 해당 일시 사용")
        void scheduled_explicitDate() {
            given(userRepository.findById(QIM_USER_ID)).willReturn(Optional.of(activeUser()));
            given(userRepository.save(any())).willReturn(null);

            Instant target = Instant.now().plus(10, ChronoUnit.DAYS);
            WithdrawalRequest req = WithdrawalRequest.builder()
                    .type(WithdrawalType.SCHEDULED)
                    .reason("10일 후 탈퇴")
                    .scheduledAt(target)
                    .build();

            WithdrawalResponse result = sut.withdraw(QIM_USER_ID, req);
            assertThat(result.getScheduledAt()).isEqualTo(target);
        }

        @Test
        @DisplayName("예약 탈퇴 취소 → status=ACTIVE 복원, resultStatus=ACTIVE 반환")
        void cancelScheduled_success() {
            QimUserJpaEntity scheduled = activeUser();
            scheduled.setStatus("WITHDRAWAL_SCHEDULED");
            given(userRepository.findById(QIM_USER_ID)).willReturn(Optional.of(scheduled));
            given(userRepository.save(any())).willReturn(scheduled);

            WithdrawalResponse result =
                    sut.cancelScheduledWithdrawal(QIM_USER_ID, CORRELATION_ID);

            assertThat(result.getResultStatus()).isEqualTo("ACTIVE");
            assertThat(scheduled.getStatus()).isEqualTo("ACTIVE");
            assertThat(scheduled.getWithdrawalScheduledAt()).isNull();
        }

        @Test
        @DisplayName("WITHDRAWAL_SCHEDULED 아닌 상태에서 취소 → IM_CONVERSION_INVALID_STATE")
        void cancelScheduled_wrongState() {
            given(userRepository.findById(QIM_USER_ID)).willReturn(Optional.of(activeUser()));

            assertThatThrownBy(() ->
                    sut.cancelScheduledWithdrawal(QIM_USER_ID, CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_CONVERSION_INVALID_STATE);
        }
    }

    // ── AGENCY_REQUESTED / ADMIN_FORCED ───────────────────────────────────

    @Nested
    @DisplayName("AGENCY_REQUESTED / ADMIN_FORCED 탈퇴")
    class AgencyAdminWithdrawal {

        @Test
        @DisplayName("AGENCY_REQUESTED — reason에 [AGENCY:기관코드] 접두사 포함")
        void agencyRequested_reasonContainsAgencyCode() {
            given(userRepository.findById(QIM_USER_ID)).willReturn(Optional.of(activeUser()));
            given(userRepository.markWithdrawn(
                    anyString(), anyString(), any(), anyString(), anyString(), any()))
                    .willReturn(1);

            WithdrawalRequest req = WithdrawalRequest.builder()
                    .type(WithdrawalType.AGENCY_REQUESTED)
                    .reason("기관 회원 탈퇴")
                    .requestedBy("GOV_AGENCY_01")
                    .build();

            sut.withdraw(QIM_USER_ID, req);

            ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
            then(userRepository).should().markWithdrawn(
                    anyString(), anyString(), any(), reasonCaptor.capture(),
                    anyString(), any());
            assertThat(reasonCaptor.getValue()).contains("[AGENCY:GOV_AGENCY_01]");
        }

        @Test
        @DisplayName("ADMIN_FORCED — reason에 [ADMIN_FORCED:관리자ID] 접두사 포함")
        void adminForced_reasonContainsAdminId() {
            given(userRepository.findById(QIM_USER_ID)).willReturn(Optional.of(activeUser()));
            given(userRepository.markWithdrawn(
                    anyString(), anyString(), any(), anyString(), anyString(), any()))
                    .willReturn(1);

            WithdrawalRequest req = WithdrawalRequest.builder()
                    .type(WithdrawalType.ADMIN_FORCED)
                    .reason("이용약관 위반")
                    .requestedBy("admin@smes.go.kr")
                    .build();

            sut.withdraw(QIM_USER_ID, req);

            ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
            then(userRepository).should().markWithdrawn(
                    anyString(), anyString(), any(), reasonCaptor.capture(),
                    anyString(), any());
            assertThat(reasonCaptor.getValue()).contains("[ADMIN_FORCED:admin@smes.go.kr]");
        }
    }

    // ── 스케줄러 — 예약 탈퇴 만료 처리 ──────────────────────────────────

    @Nested
    @DisplayName("processExpiredScheduledWithdrawals — 만료 처리")
    class ScheduledExpiry {

        @Test
        @DisplayName("만료 사용자 있음 — PII 삭제 + 상태 갱신 호출, 처리 건수 반환")
        void expiry_processesUsers() {
            QimUserJpaEntity expired = activeUser();
            expired.setStatus("WITHDRAWAL_SCHEDULED");
            expired.setWithdrawalScheduledAt(Instant.now().minus(1, ChronoUnit.HOURS));

            given(userRepository.findExpiredScheduledWithdrawals(any()))
                    .willReturn(List.of(expired));
            given(userRepository.markWithdrawn(
                    anyString(), anyString(), any(), anyString(), anyString(), any()))
                    .willReturn(1);

            int count = sut.processExpiredScheduledWithdrawals();
            assertThat(count).isEqualTo(1);
            then(jdbcTemplate).should().update(anyString(), eq(QIM_USER_ID));
        }

        @Test
        @DisplayName("만료 사용자 없음 → 0 반환, 부수 효과 없음")
        void expiry_noExpired_returnsZero() {
            given(userRepository.findExpiredScheduledWithdrawals(any()))
                    .willReturn(List.of());

            int count = sut.processExpiredScheduledWithdrawals();
            assertThat(count).isZero();
            then(jdbcTemplate).shouldHaveNoInteractions();
        }
    }
}

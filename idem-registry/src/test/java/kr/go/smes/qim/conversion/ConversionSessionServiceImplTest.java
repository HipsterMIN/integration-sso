package kr.go.smes.qim.conversion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.qim.infrastructure.jpa.entity.ConversionSessionJpaEntity;
import kr.go.smes.qim.infrastructure.jpa.repository.AuthMeanMappingJpaRepository;
import kr.go.smes.qim.infrastructure.jpa.repository.ConversionSessionJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * ConversionSessionServiceImpl 단위 테스트 — 상태 기계 전이 검증
 *
 * <p>커버 케이스 (12개):
 * <ul>
 *   <li>initiate — 신규 세션 생성 (INITIATED)</li>
 *   <li>initiate — 기존 활성 세션 재사용</li>
 *   <li>fetchCandidates — INITIATED → MEMBERS_FETCHED 전이</li>
 *   <li>fetchCandidates — 잘못된 상태 → IM_CONVERSION_INVALID_STATE</li>
 *   <li>selectAccounts — MEMBERS_FETCHED → ACCOUNT_SELECTED 전이</li>
 *   <li>link — ACCOUNT_SELECTED → COMPLETED 전이</li>
 *   <li>cancel — any → CANCELLED 전이</li>
 *   <li>cancel — terminal 상태(COMPLETED) → IM_CONVERSION_INVALID_STATE</li>
 *   <li>getSession — 존재하지 않는 세션 → IM_CONVERSION_NOT_FOUND</li>
 *   <li>getSession — 만료된 세션 → IM_CONVERSION_EXPIRED</li>
 *   <li>expireStale — 만료 세션 일괄 처리</li>
 *   <li>ConversionSessionState.canTransitionTo — 유효/무효 전이 검증</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConversionSessionServiceImpl — 상태 기계 단위 테스트")
class ConversionSessionServiceImplTest {

    @Mock
    private ConversionSessionJpaRepository sessionRepository;

    @Mock
    private AgencyMemberLookupService agencyMemberLookupService;

    /** M-02: AuthMeanMappingJpaRepository Mock — 기본적으로 empty 반환(Fallback 동작 확인) */
    @Mock
    private AuthMeanMappingJpaRepository authMeanMappingRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @InjectMocks
    private ConversionSessionServiceImpl sut;

    private static final String QIM_USER_ID    = "user-conv-001";
    private static final String SESSION_ID     = "session-001";
    private static final String CORRELATION_ID = "corr-conv-001";

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // M-02: identifierHash DB 조회 Fallback 시 오류 없도록 empty 반환 설정
        given(authMeanMappingRepository.findActivePassCiHash(anyString()))
                .willReturn(Optional.empty());
    }

    private ConversionSessionJpaEntity session(String status) {
        ConversionSessionJpaEntity e = new ConversionSessionJpaEntity();
        e.setSessionId(SESSION_ID);
        e.setQimUserId(QIM_USER_ID);
        e.setStatus(status);
        e.setExpiresAt(Instant.now().plus(30, ChronoUnit.MINUTES));
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return e;
    }

    private ConversionSessionJpaEntity expiredSession(String status) {
        ConversionSessionJpaEntity e = session(status);
        e.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        return e;
    }

    // ── initiate ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("initiate — 세션 시작")
    class Initiate {

        @Test
        @DisplayName("신규 세션 생성 — INITIATED 상태, qimUserId 설정")
        void initiate_createNew() {
            given(sessionRepository.findActiveByUser(eq(QIM_USER_ID), any()))
                    .willReturn(Optional.empty());
            given(sessionRepository.save(any()))
                    .willAnswer(inv -> inv.getArgument(0));

            ConversionSessionResult result = sut.initiate(QIM_USER_ID, CORRELATION_ID);

            assertThat(result.getState()).isEqualTo(ConversionSessionState.INITIATED);
            assertThat(result.getQimUserId()).isEqualTo(QIM_USER_ID);
            assertThat(result.getExpiresAt()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("기존 활성 세션 존재 — 기존 세션 재사용 (save 미호출)")
        void initiate_reuseExisting() {
            given(sessionRepository.findActiveByUser(eq(QIM_USER_ID), any()))
                    .willReturn(Optional.of(session("INITIATED")));

            ConversionSessionResult result = sut.initiate(QIM_USER_ID, CORRELATION_ID);

            assertThat(result.getSessionId()).isEqualTo(SESSION_ID);
            then(sessionRepository).should(never()).save(any());
        }
    }

    // ── fetchCandidates ───────────────────────────────────────────────────

    @Nested
    @DisplayName("fetchCandidates — 후보 회원 조회")
    class FetchCandidates {

        @Test
        @DisplayName("INITIATED → MEMBERS_FETCHED 전이 성공 (AgencyMemberLookupService 호출)")
        void fetch_initiated_to_membersFetched() {
            given(sessionRepository.findById(SESSION_ID))
                    .willReturn(Optional.of(session("INITIATED")));
            given(sessionRepository.save(any()))
                    .willAnswer(inv -> inv.getArgument(0));
            given(agencyMemberLookupService.lookupByIdentifierHash(anyString(), anyString(), anyString()))
                    .willReturn(List.of(
                            CandidateMember.builder()
                                    .agencyCode("GOV_SMES").agencyName("소상공인시장진흥공단")
                                    .memberId("m-001").nameMasked("홍*동")
                                    .build()));

            ConversionSessionResult result = sut.fetchCandidates(SESSION_ID, CORRELATION_ID);
            assertThat(result.getState()).isEqualTo(ConversionSessionState.MEMBERS_FETCHED);
            // AgencyMemberLookupService 실제 호출 확인
            then(agencyMemberLookupService).should()
                    .lookupByIdentifierHash(eq(QIM_USER_ID), anyString(), eq(CORRELATION_ID));
        }

        @Test
        @DisplayName("MEMBERS_FETCHED 상태에서 fetchCandidates 재시도 → IM_CONVERSION_INVALID_STATE")
        void fetch_wrongState_throws() {
            given(sessionRepository.findById(SESSION_ID))
                    .willReturn(Optional.of(session("MEMBERS_FETCHED")));

            assertThatThrownBy(() -> sut.fetchCandidates(SESSION_ID, CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_CONVERSION_INVALID_STATE);
        }
    }

    // ── selectAccounts ────────────────────────────────────────────────────

    @Nested
    @DisplayName("selectAccounts — 계정 선택")
    class SelectAccounts {

        @Test
        @DisplayName("MEMBERS_FETCHED → ACCOUNT_SELECTED, 선택 코드 저장")
        void select_success() {
            given(sessionRepository.findById(SESSION_ID))
                    .willReturn(Optional.of(session("MEMBERS_FETCHED")));
            given(sessionRepository.save(any()))
                    .willAnswer(inv -> inv.getArgument(0));

            List<String> codes = List.of("GOV_A", "GOV_B");
            ConversionSessionResult result =
                    sut.selectAccounts(SESSION_ID, codes, CORRELATION_ID);

            assertThat(result.getState()).isEqualTo(ConversionSessionState.ACCOUNT_SELECTED);
            assertThat(result.getSelectedAgencyCodes()).containsExactlyElementsOf(codes);
        }
    }

    // ── link ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("link — 계정 연결")
    class Link {

        @Test
        @DisplayName("ACCOUNT_SELECTED → COMPLETED, AgencyMemberLookupService.performLinking 호출")
        void link_success() {
            ConversionSessionJpaEntity s = session("ACCOUNT_SELECTED");
            s.setSelectedAgencyCodesJson("[\"GOV_A\",\"GOV_B\"]");
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(s));
            given(sessionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(agencyMemberLookupService.performLinking(anyString(), anyString(),
                    anyList(), anyString()))
                    .willReturn(List.of("GOV_A", "GOV_B"));

            ConversionSessionResult result = sut.link(SESSION_ID, CORRELATION_ID);
            assertThat(result.getState()).isEqualTo(ConversionSessionState.COMPLETED);
            assertThat(result.getLinkedAgencyCodes()).containsExactly("GOV_A", "GOV_B");
            // AgencyMemberLookupService 실제 호출 확인
            then(agencyMemberLookupService).should()
                    .performLinking(eq(QIM_USER_ID), anyString(),
                            eq(List.of("GOV_A", "GOV_B")), eq(CORRELATION_ID));
        }
    }

    // ── cancel ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancel — 세션 취소")
    class Cancel {

        @Test
        @DisplayName("INITIATED 상태 취소 → CANCELLED")
        void cancel_fromInitiated() {
            given(sessionRepository.findById(SESSION_ID))
                    .willReturn(Optional.of(session("INITIATED")));
            given(sessionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            ConversionSessionResult result =
                    sut.cancel(SESSION_ID, "사용자 취소", CORRELATION_ID);
            assertThat(result.getState()).isEqualTo(ConversionSessionState.CANCELLED);
        }

        @Test
        @DisplayName("COMPLETED terminal 상태 취소 → IM_CONVERSION_INVALID_STATE")
        void cancel_fromCompleted_throws() {
            given(sessionRepository.findById(SESSION_ID))
                    .willReturn(Optional.of(session("COMPLETED")));

            assertThatThrownBy(() ->
                    sut.cancel(SESSION_ID, "취소 시도", CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_CONVERSION_INVALID_STATE);
        }
    }

    // ── getSession ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getSession — 세션 조회")
    class GetSession {

        @Test
        @DisplayName("존재하지 않는 sessionId → IM_CONVERSION_NOT_FOUND")
        void get_notFound() {
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> sut.getSession(SESSION_ID, CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_CONVERSION_NOT_FOUND);
        }

        @Test
        @DisplayName("만료된 세션 fetchCandidates 호출 → IM_CONVERSION_EXPIRED")
        void fetch_expired_throws() {
            given(sessionRepository.findById(SESSION_ID))
                    .willReturn(Optional.of(expiredSession("INITIATED")));

            assertThatThrownBy(() -> sut.fetchCandidates(SESSION_ID, CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_CONVERSION_EXPIRED);
        }
    }

    // ── expireStale ───────────────────────────────────────────────────────

    @Test
    @DisplayName("expireStale — 만료 세션 일괄 처리")
    void expireStale_callsBatchUpdate() {
        given(sessionRepository.markExpiredBatch(any())).willReturn(3);

        int count = sut.expireStale();
        assertThat(count).isEqualTo(3);
        then(sessionRepository).should().markExpiredBatch(any());
    }

    // ── ConversionSessionState 전이 규칙 ──────────────────────────────────

    @Nested
    @DisplayName("ConversionSessionState.canTransitionTo — 상태 전이 규칙")
    class StateTransition {

        @Test
        @DisplayName("INITIATED → MEMBERS_FETCHED/CANCELLED/EXPIRED 허용")
        void initiated_allowedTransitions() {
            ConversionSessionState s = ConversionSessionState.INITIATED;
            assertThat(s.canTransitionTo(ConversionSessionState.MEMBERS_FETCHED)).isTrue();
            assertThat(s.canTransitionTo(ConversionSessionState.CANCELLED)).isTrue();
            assertThat(s.canTransitionTo(ConversionSessionState.EXPIRED)).isTrue();
        }

        @Test
        @DisplayName("COMPLETED(terminal) → 어떤 상태로도 전이 불가")
        void completed_noTransitions() {
            ConversionSessionState s = ConversionSessionState.COMPLETED;
            for (ConversionSessionState next : ConversionSessionState.values()) {
                assertThat(s.canTransitionTo(next)).isFalse();
            }
        }

        @Test
        @DisplayName("isTerminal() — COMPLETED/CANCELLED/EXPIRED만 terminal")
        void isTerminal_check() {
            assertThat(ConversionSessionState.COMPLETED.isTerminal()).isTrue();
            assertThat(ConversionSessionState.CANCELLED.isTerminal()).isTrue();
            assertThat(ConversionSessionState.EXPIRED.isTerminal()).isTrue();
            assertThat(ConversionSessionState.INITIATED.isTerminal()).isFalse();
            assertThat(ConversionSessionState.LINKING.isTerminal()).isFalse();
        }
    }
}

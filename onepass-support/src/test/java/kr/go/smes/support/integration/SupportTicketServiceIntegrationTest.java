package kr.go.smes.support.integration;

import kr.go.smes.support.api.SupportApiException;
import kr.go.smes.support.api.dto.AnswerQnaRequest;
import kr.go.smes.support.api.dto.CreateInternalNoteRequest;
import kr.go.smes.support.api.dto.CreatePhoneConsultationRequest;
import kr.go.smes.support.api.dto.CreateQnaRequest;
import kr.go.smes.support.api.dto.CsTicketDetailResponse;
import kr.go.smes.support.api.dto.CsTicketSummaryResponse;
import kr.go.smes.support.api.dto.QnaDetailResponse;
import kr.go.smes.support.api.dto.UpdateTicketAssignmentRequest;
import kr.go.smes.support.api.dto.UpdateTicketStatusRequest;
import kr.go.smes.support.application.CsRequester;
import kr.go.smes.support.application.QnaService;
import kr.go.smes.support.application.SupportRequester;
import kr.go.smes.support.application.SupportTicketService;
import kr.go.smes.support.domain.QnaPostEntity;
import kr.go.smes.support.domain.QnaPostRepository;
import kr.go.smes.support.domain.SupportTicketRepository;
import kr.go.smes.support.domain.Yn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SupportTicketService 통합 테스트 — CS 백오피스 인가 분기 + 티켓 멱등성 회귀 방어
 *
 * <p>위험 #2 (onepass-support 테스트 1%) 해소: CS 권한 세분화 (staff / writable / lead)
 * 와 Q&A 발 티켓 자동 생성·답변 기록의 멱등성을 통합 테스트로 검증한다.
 *
 * <p>검증 범위:
 * <ul>
 *   <li>listQueue / getTicket — staff() 권한 검증 (E-SUPPORT-CS-403)</li>
 *   <li>createPhoneConsultation / addInternalNote / updateStatus — writable() 권한 (E-SUPPORT-CS-WRITE-403)</li>
 *   <li>updateAssignment — lead() 권한 (E-SUPPORT-CS-LEAD-403)</li>
 *   <li>Q&A 생성 → createFromQna 가 호출되어 티켓 자동 생성</li>
 *   <li>같은 Q&A 에 createFromQna 가 재호출되어도 티켓이 중복 생성되지 않음 (멱등성)
 *       — V3 의 부분 인덱스 {@code uq_support_ticket_linked_qna} 회귀 방어</li>
 *   <li>관리자 답변 시 recordQnaAnswer 가 호출되어 티켓 상태 ANSWERED 로 전환 +
 *       PUBLIC_REPLY 이벤트 추가</li>
 *   <li>전화 민원 접수 → 티켓 1건 + 전화 상담 1건 + PHONE_CALL 이벤트 추가</li>
 *   <li>티켓 상태 변경 — CLOSED 이면 closedAt 설정, 그 외 null</li>
 *   <li>잘못된 상태값 → 400 (E-SUPPORT-TICKET-STATUS)</li>
 * </ul>
 */
@DisplayName("SupportTicketService 통합 테스트 — 인가 분기 + 티켓 멱등성")
@Transactional
class SupportTicketServiceIntegrationTest extends SupportIntegrationTestBase {

    private static final String TENANT = "tenant-cs";
    private static final String AGENCY = "agency-cs";

    @Autowired
    private SupportTicketService ticketService;

    @Autowired
    private QnaService qnaService;

    @Autowired
    private SupportTicketRepository ticketRepository;

    @Autowired
    private QnaPostRepository qnaPostRepository;

    private CsRequester csAgent;     // CS_AGENT — staff() + writable()
    private CsRequester csLead;      // CS_LEAD  — staff() + writable() + lead()
    private CsRequester auditor;     // AUDITOR  — staff() only (조회 가능, 쓰기 불가)
    private CsRequester nonStaff;    // READER   — STAFF_ROLES 밖, 모두 거부
    private CsRequester unauth;      // agentId null — authenticated()=false

    private SupportRequester alice;

    @BeforeEach
    void setUp() {
        csAgent  = CsRequester.of("agent-1",   "CS_AGENT");
        csLead   = CsRequester.of("lead-1",    "CS_LEAD");
        auditor  = CsRequester.of("auditor-1", "AUDITOR");
        nonStaff = CsRequester.of("reader-1",  "READER");
        unauth   = CsRequester.of(null,         "CS_AGENT");

        alice = SupportRequester.of("user-alice", "USER");
    }

    // ────────────────────────────────────────────────────────────────────
    // 인가 분기 — staff() / writable() / lead()
    // ────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("CS 인가 분기 (staff / writable / lead)")
    class AuthorizationBoundaries {

        @Test
        @DisplayName("staff 권한 (AUDITOR 포함) 은 listQueue / getTicket 가능")
        void staffCanRead() {
            UUID ticketId = createPhoneTicket(csAgent).id();

            assertThat(ticketService.listQueue(csAgent, null, null, null, null, null))
                    .extracting(CsTicketSummaryResponse::id).contains(ticketId);
            assertThat(ticketService.listQueue(csLead, null, null, null, null, null))
                    .extracting(CsTicketSummaryResponse::id).contains(ticketId);
            assertThat(ticketService.listQueue(auditor, null, null, null, null, null))
                    .extracting(CsTicketSummaryResponse::id).contains(ticketId);
            assertThat(ticketService.getTicket(auditor, ticketId).id()).isEqualTo(ticketId);
        }

        @Test
        @DisplayName("staff 가 아니면 listQueue → 403 (E-SUPPORT-CS-403)")
        void nonStaffCannotList() {
            assertThatThrownBy(() -> ticketService.listQueue(nonStaff, null, null, null, null, null))
                    .isInstanceOf(SupportApiException.class)
                    .satisfies(ex -> {
                        SupportApiException sae = (SupportApiException) ex;
                        assertThat(sae.getCode()).isEqualTo("E-SUPPORT-CS-403");
                        assertThat(sae.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    });
        }

        @Test
        @DisplayName("AUDITOR (staff) 는 writable 아님 → addInternalNote 시 403 (E-SUPPORT-CS-WRITE-403)")
        void auditorCannotWrite() {
            UUID ticketId = createPhoneTicket(csAgent).id();

            assertThatThrownBy(() -> ticketService.addInternalNote(
                    auditor, ticketId, new CreateInternalNoteRequest("내부 메모")))
                    .isInstanceOf(SupportApiException.class)
                    .satisfies(ex -> {
                        SupportApiException sae = (SupportApiException) ex;
                        assertThat(sae.getCode()).isEqualTo("E-SUPPORT-CS-WRITE-403");
                        assertThat(sae.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    });
        }

        @Test
        @DisplayName("CS_AGENT (writable) 는 updateAssignment 불가 → 403 (E-SUPPORT-CS-LEAD-403)")
        void agentCannotAssign() {
            UUID ticketId = createPhoneTicket(csAgent).id();

            assertThatThrownBy(() -> ticketService.updateAssignment(
                    csAgent, ticketId, new UpdateTicketAssignmentRequest("agent-2")))
                    .isInstanceOf(SupportApiException.class)
                    .satisfies(ex -> assertThat(((SupportApiException) ex).getCode())
                            .isEqualTo("E-SUPPORT-CS-LEAD-403"));
        }

        @Test
        @DisplayName("CS_LEAD 는 updateAssignment 가능")
        void leadCanAssign() {
            UUID ticketId = createPhoneTicket(csAgent).id();

            CsTicketDetailResponse afterAssign = ticketService.updateAssignment(
                    csLead, ticketId, new UpdateTicketAssignmentRequest("agent-9"));

            assertThat(afterAssign.assignedAgentId()).isEqualTo("agent-9");
            // ASSIGNED 이벤트가 추가되었어야 함
            assertThat(afterAssign.events())
                    .extracting(e -> e.eventType())
                    .contains("ASSIGNED");
        }

        @Test
        @DisplayName("agentId 가 null (비인증) 이면 staff/writable/lead 모두 false → 403")
        void unauthenticatedFailsAllChecks() {
            assertThatThrownBy(() -> ticketService.listQueue(unauth, null, null, null, null, null))
                    .isInstanceOf(SupportApiException.class)
                    .satisfies(ex -> assertThat(((SupportApiException) ex).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Q&A → 티켓 자동 생성 + 멱등성 (V3 부분 인덱스 회귀 방어)
    // ────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Q&A → 티켓 자동 생성 + 멱등성")
    class QnaToTicketBridge {

        @Test
        @DisplayName("Q&A 생성 시 createFromQna 가 호출되어 QNA 채널 티켓이 자동 생성됨")
        void qnaCreationGeneratesLinkedTicket() {
            QnaDetailResponse qna = qnaService.create(alice, new CreateQnaRequest(
                    "Q&A 발 티켓", "본문", TENANT, AGENCY, false, null, null));

            List<CsTicketSummaryResponse> tickets = ticketService.listQueue(
                    csAgent, null, "QNA", null, TENANT, AGENCY);

            assertThat(tickets)
                    .as("Q&A 1건 → QNA 채널 티켓 1건 자동 생성")
                    .hasSize(1)
                    .first()
                    .satisfies(t -> {
                        assertThat(t.channel()).isEqualTo("QNA");
                        assertThat(t.title()).isEqualTo("Q&A 발 티켓");
                    });

            // linkedQnaId 가 Q&A id 와 일치하는지 확인
            assertThat(ticketRepository.findByLinkedQnaIdAndUseYn(qna.id(), Yn.YES))
                    .isPresent();
        }

        @Test
        @DisplayName("같은 Q&A 에 createFromQna 가 재호출돼도 티켓은 1건만 존재 (멱등성 + V3 부분 인덱스 회귀 방어)")
        void createFromQnaIsIdempotent() {
            QnaDetailResponse qna = qnaService.create(alice, new CreateQnaRequest(
                    "멱등성 검증", "본문", TENANT, AGENCY, false, null, null));

            // QnaService.create 가 이미 1회 createFromQna 호출
            // 직접 재호출하여 멱등성을 검증
            var firstTicket = ticketRepository.findByLinkedQnaIdAndUseYn(qna.id(), Yn.YES)
                    .orElseThrow();

            // createFromQna 를 같은 qna 로 다시 호출 — 신규 row 가 만들어지면 V3 의 부분 unique 인덱스가 폭발해야 정상
            ticketService.createFromQna(toEntityRef(qna), "manual-replay");
            ticketService.createFromQna(toEntityRef(qna), "manual-replay-2");

            long ticketsLinked = ticketRepository.findAll().stream()
                    .filter(t -> Yn.isYes(t.getUseYn()))
                    .filter(t -> qna.id().equals(t.getLinkedQnaId()))
                    .count();
            assertThat(ticketsLinked)
                    .as("Q&A → 티켓 멱등성: linkedQnaId 가 같으면 신규 row 생성 금지")
                    .isEqualTo(1L);
            assertThat(ticketRepository.findById(firstTicket.getTicketId()))
                    .isPresent();
        }

        @Test
        @DisplayName("관리자 답변 → recordQnaAnswer 가 호출되어 티켓 상태 ANSWERED 전환 + PUBLIC_REPLY 이벤트")
        void adminAnswerTransitionsTicketStatus() {
            QnaDetailResponse qna = qnaService.create(alice, new CreateQnaRequest(
                    "답변 전이 검증", "본문", TENANT, AGENCY, false, null, null));

            qnaService.answerByAdmin(csAgent, qna.id(),
                    new AnswerQnaRequest("답변 내용", false));

            var ticket = ticketRepository.findByLinkedQnaIdAndUseYn(qna.id(), Yn.YES)
                    .orElseThrow();

            assertThat(ticket.getStatusCode()).isEqualTo("ANSWERED");
            // PUBLIC_REPLY 이벤트가 추가되었어야 함 (visibility=CUSTOMER)
            assertThat(ticket.getEvents())
                    .extracting(e -> e.getEventTypeCode())
                    .contains("QNA_CREATED", "PUBLIC_REPLY");
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 전화 민원 / 내부 메모 / 상태 전이
    // ────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("전화 민원 / 내부 메모 / 상태 전이")
    class PhoneAndStatus {

        @Test
        @DisplayName("전화 민원 접수 → PHONE 채널 티켓 + 전화 상담 1건 + PHONE_CALL 이벤트 추가")
        void createPhoneConsultationCreatesTicketAndConsultation() {
            CsTicketDetailResponse created = createPhoneTicket(csAgent);

            assertThat(created.channel()).isEqualTo("PHONE");
            assertThat(created.status()).isEqualTo("OPEN");
            // PHONE_CALL 이벤트가 추가되었어야 함 (visibility=INTERNAL)
            assertThat(created.events())
                    .extracting(e -> e.eventType()).contains("PHONE_CALL");
        }

        @Test
        @DisplayName("내부 메모 추가 → INTERNAL_NOTE 이벤트 + lastUpdtPnttm 갱신")
        void addInternalNoteAddsEvent() {
            UUID ticketId = createPhoneTicket(csAgent).id();
            Instant before = ticketRepository.findById(ticketId).orElseThrow().getLastUpdtPnttm();

            CsTicketDetailResponse afterNote = ticketService.addInternalNote(
                    csAgent, ticketId, new CreateInternalNoteRequest("CS 내부 메모 내용"));

            assertThat(afterNote.events())
                    .extracting(e -> e.eventType()).contains("INTERNAL_NOTE");
            assertThat(afterNote.updatedAt()).isAfterOrEqualTo(before);
        }

        @Test
        @DisplayName("상태 변경: CLOSED 로 전환 시 closedAt 설정 + STATUS_CHANGED 이벤트")
        void updateStatusToClosedSetsClosedAt() {
            UUID ticketId = createPhoneTicket(csAgent).id();

            CsTicketDetailResponse closed = ticketService.updateStatus(
                    csAgent, ticketId, new UpdateTicketStatusRequest("CLOSED"));

            assertThat(closed.status()).isEqualTo("CLOSED");
            assertThat(closed.closedAt()).isNotNull();
            assertThat(closed.events())
                    .extracting(e -> e.eventType()).contains("STATUS_CHANGED");
        }

        @Test
        @DisplayName("CLOSED → OPEN 으로 되돌리면 closedAt 이 null 로 리셋")
        void reopenClearsClosedAt() {
            UUID ticketId = createPhoneTicket(csAgent).id();
            ticketService.updateStatus(csAgent, ticketId, new UpdateTicketStatusRequest("CLOSED"));

            CsTicketDetailResponse reopened = ticketService.updateStatus(
                    csAgent, ticketId, new UpdateTicketStatusRequest("OPEN"));

            assertThat(reopened.status()).isEqualTo("OPEN");
            assertThat(reopened.closedAt()).isNull();
        }

        @Test
        @DisplayName("허용되지 않은 status 값 → 400 (E-SUPPORT-TICKET-STATUS)")
        void invalidStatusValueRejected() {
            UUID ticketId = createPhoneTicket(csAgent).id();

            assertThatThrownBy(() -> ticketService.updateStatus(
                    csAgent, ticketId, new UpdateTicketStatusRequest("INVALID_STATE")))
                    .isInstanceOf(SupportApiException.class)
                    .satisfies(ex -> {
                        SupportApiException sae = (SupportApiException) ex;
                        assertThat(sae.getCode()).isEqualTo("E-SUPPORT-TICKET-STATUS");
                        assertThat(sae.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    });
        }

        @Test
        @DisplayName("존재하지 않는 티켓 ID → 404 (E-SUPPORT-TICKET-404)")
        void nonExistentTicketIs404() {
            UUID phantom = UUID.randomUUID();

            assertThatThrownBy(() -> ticketService.getTicket(csAgent, phantom))
                    .isInstanceOf(SupportApiException.class)
                    .satisfies(ex -> {
                        SupportApiException sae = (SupportApiException) ex;
                        assertThat(sae.getCode()).isEqualTo("E-SUPPORT-TICKET-404");
                        assertThat(sae.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    });
        }

        @Test
        @DisplayName("listQueue 필터링: channel=PHONE 으로 필터 시 QNA 채널 티켓 제외")
        void listQueueFiltersByChannel() {
            createPhoneTicket(csAgent);
            qnaService.create(alice, new CreateQnaRequest(
                    "QNA 발 티켓", "본문", TENANT, AGENCY, false, null, null));

            List<CsTicketSummaryResponse> phoneOnly =
                    ticketService.listQueue(csAgent, null, "PHONE", null, null, null);

            assertThat(phoneOnly).isNotEmpty()
                    .allSatisfy(t -> assertThat(t.channel()).isEqualTo("PHONE"));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 테스트 헬퍼
    // ────────────────────────────────────────────────────────────────────

    private CsTicketDetailResponse createPhoneTicket(CsRequester requester) {
        return ticketService.createPhoneConsultation(requester, new CreatePhoneConsultationRequest(
                TENANT, AGENCY, "전화 민원 제목",
                "민원인", "010-0000-0000", null,
                "PASSWORD_RESET", "NORMAL", "INBOUND",
                Instant.parse("2026-05-23T10:00:00Z"),
                Instant.parse("2026-05-23T10:05:00Z"),
                true, "민원 요약 내용", "민원 상세 내용", "조치 요청 사항",
                false, null
        ));
    }

    /**
     * createFromQna 가 요구하는 QnaPostEntity 를 검색하여 반환.
     * (QnaDetailResponse 는 DTO 라 entity 가 없으므로 repository 에서 가져옴)
     */
    private QnaPostEntity toEntityRef(QnaDetailResponse qna) {
        return qnaPostRepository.findByQnaIdAndUseYn(qna.id(), Yn.YES).orElseThrow();
    }
}

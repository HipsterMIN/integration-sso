package kr.go.smes.support.integration;

import kr.go.smes.support.api.SupportApiException;
import kr.go.smes.support.api.dto.AnswerQnaRequest;
import kr.go.smes.support.api.dto.CreateQnaRequest;
import kr.go.smes.support.api.dto.QnaDetailResponse;
import kr.go.smes.support.api.dto.QnaSummaryResponse;
import kr.go.smes.support.application.CsRequester;
import kr.go.smes.support.application.QnaService;
import kr.go.smes.support.application.SupportRequester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QnaService 통합 테스트 — 실제 PostgreSQL (Testcontainers) + Flyway V1/V2/V3 마이그레이션
 *
 * <p>위험 #2 (onepass-support 테스트 1%) 해소: 인가 정책의 정확성을 회귀 방어한다.
 *
 * <p>검증 범위:
 * <ul>
 *   <li>공개 Q&A: 비로그인/타인/작성자/관리자 모두 조회 가능</li>
 *   <li>비밀 Q&A: 작성자 본인 + ADMIN만 조회 — 타인은 403 (E-SUPPORT-QNA-403)</li>
 *   <li>익명 사용자: 공개글만 작성 가능 + anonymousDisplayName 필수</li>
 *   <li>리스트 조회: secret 글은 작성자 자신에게만 노출, ADMIN 채널은 listForAdmin 사용</li>
 *   <li>관리자 답변: 비밀글 → 비밀 답변 자동, 익명 문의 → 비밀 답변 거부</li>
 *   <li>CsRequester 역할 정책: USER/ADMIN 헤더 → SupportRequester.admin() 분기</li>
 * </ul>
 *
 * <p>{@code @Transactional} 을 클래스 레벨에 두어 각 테스트 종료 시 자동 롤백 → 격리 보장.
 */
@DisplayName("QnaService 통합 테스트 — 비밀글 인가 정책 + 익명 작성 규칙")
@Transactional
class QnaServiceIntegrationTest extends SupportIntegrationTestBase {

    private static final String TENANT = "tenant-it";
    private static final String AGENCY = "agency-it";

    private static final String USER_ALICE = "user-alice";
    private static final String USER_BOB   = "user-bob";

    @Autowired
    private QnaService qnaService;

    private SupportRequester alice;
    private SupportRequester bob;
    private SupportRequester admin;
    private SupportRequester anonymous;
    private CsRequester csAgent;
    private CsRequester csLead;
    private CsRequester nonStaff;

    @BeforeEach
    void setUp() {
        // @Transactional (클래스 레벨) 이 각 테스트 종료 시 롤백을 수행 → 자동 격리 보장.
        // deleteAll() 은 같은 트랜잭션 내에서 어차피 롤백되므로 호출 불필요.

        alice     = SupportRequester.of(USER_ALICE, "USER");
        bob       = SupportRequester.of(USER_BOB,   "USER");
        admin     = SupportRequester.of("admin-1",  "ADMIN");
        anonymous = SupportRequester.of(null,       null);

        csAgent   = CsRequester.of("agent-1", "CS_AGENT");
        csLead    = CsRequester.of("lead-1",  "CS_LEAD");
        nonStaff  = CsRequester.of("auditor-1", "READER"); // STAFF_ROLES 밖
    }

    // ────────────────────────────────────────────────────────────────────
    // 공개 Q&A 조회 인가
    // ────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("공개 Q&A 조회")
    class PublicQna {

        @Test
        @DisplayName("공개글은 비로그인 / 타인 / 작성자 / 관리자 모두 조회 가능")
        void publicPostVisibleToEveryone() {
            QnaDetailResponse created = qnaService.create(alice, publicQnaRequest());

            assertThat(qnaService.getForUser(anonymous, created.id()).id()).isEqualTo(created.id());
            assertThat(qnaService.getForUser(bob,       created.id()).id()).isEqualTo(created.id());
            assertThat(qnaService.getForUser(alice,     created.id()).id()).isEqualTo(created.id());
            assertThat(qnaService.getForUser(admin,     created.id()).id()).isEqualTo(created.id());
        }

        @Test
        @DisplayName("공개글은 리스트 조회 시 mine 플래그 정확 (작성자=true, 타인=false)")
        void publicPostListMineFlagIsAccurate() {
            QnaDetailResponse created = qnaService.create(alice, publicQnaRequest());

            List<QnaSummaryResponse> forAlice = qnaService.listForUser(alice, TENANT, AGENCY);
            List<QnaSummaryResponse> forBob   = qnaService.listForUser(bob,   TENANT, AGENCY);

            assertThat(forAlice).hasSize(1).first()
                    .satisfies(s -> {
                        assertThat(s.id()).isEqualTo(created.id());
                        assertThat(s.mine()).isTrue();
                        assertThat(s.secret()).isFalse();
                    });
            assertThat(forBob).hasSize(1).first()
                    .satisfies(s -> assertThat(s.mine()).isFalse());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 비밀 Q&A 조회 인가 — 핵심 회귀 방어
    // ────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("비밀 Q&A 조회 인가")
    class SecretQna {

        @Test
        @DisplayName("작성자 본인은 비밀글 조회 가능")
        void writerCanReadOwnSecretPost() {
            QnaDetailResponse created = qnaService.create(alice, secretQnaRequest());

            QnaDetailResponse readByOwner = qnaService.getForUser(alice, created.id());

            assertThat(readByOwner.secret()).isTrue();
            assertThat(readByOwner.contentMasked()).isFalse();
            assertThat(readByOwner.content()).isNotBlank();
        }

        @Test
        @DisplayName("ADMIN 은 모든 비밀글 조회 가능")
        void adminCanReadAnySecretPost() {
            QnaDetailResponse created = qnaService.create(alice, secretQnaRequest());

            QnaDetailResponse readByAdmin = qnaService.getForUser(admin, created.id());

            assertThat(readByAdmin.secret()).isTrue();
            assertThat(readByAdmin.contentMasked()).isFalse();
            assertThat(readByAdmin.content()).isNotBlank();
        }

        @Test
        @DisplayName("다른 USER 는 비밀글 조회 시 403 (E-SUPPORT-QNA-403)")
        void otherUserCannotReadSecretPost() {
            QnaDetailResponse created = qnaService.create(alice, secretQnaRequest());

            assertThatThrownBy(() -> qnaService.getForUser(bob, created.id()))
                    .isInstanceOf(SupportApiException.class)
                    .satisfies(ex -> {
                        SupportApiException sae = (SupportApiException) ex;
                        assertThat(sae.getCode()).isEqualTo("E-SUPPORT-QNA-403");
                        assertThat(sae.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    });
        }

        @Test
        @DisplayName("비로그인 사용자는 비밀글 조회 시 403")
        void anonymousCannotReadSecretPost() {
            QnaDetailResponse created = qnaService.create(alice, secretQnaRequest());

            assertThatThrownBy(() -> qnaService.getForUser(anonymous, created.id()))
                    .isInstanceOf(SupportApiException.class)
                    .extracting(ex -> ((SupportApiException) ex).getStatus())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("리스트 조회 시 비밀글은 작성자에게만 노출 (다른 USER 의 리스트에는 비포함)")
        void secretPostListedOnlyToWriter() {
            QnaDetailResponse aliceSecret = qnaService.create(alice, secretQnaRequest());
            QnaDetailResponse alicePublic = qnaService.create(alice, publicQnaRequest());

            List<UUID> forBob = qnaService.listForUser(bob, TENANT, AGENCY).stream()
                    .map(QnaSummaryResponse::id).toList();

            assertThat(forBob)
                    .doesNotContain(aliceSecret.id())   // bob 은 alice 의 비밀글 못 봄
                    .contains(alicePublic.id());        // 공개글은 보임
        }

        @Test
        @DisplayName("관리자 채널 (listForAdmin) 은 비밀 여부 무관하게 모두 조회")
        void listForAdminReturnsAllPosts() {
            qnaService.create(alice, secretQnaRequest());
            qnaService.create(bob,   publicQnaRequest());

            List<QnaSummaryResponse> all = qnaService.listForAdmin(csAgent, TENANT, AGENCY);

            assertThat(all).hasSize(2);
        }

        @Test
        @DisplayName("listForAdmin 은 CS 권한 없으면 403 (E-SUPPORT-CS-403)")
        void listForAdminRequiresStaff() {
            qnaService.create(alice, publicQnaRequest());

            assertThatThrownBy(() -> qnaService.listForAdmin(nonStaff, TENANT, AGENCY))
                    .isInstanceOf(SupportApiException.class)
                    .satisfies(ex -> assertThat(((SupportApiException) ex).getCode())
                            .isEqualTo("E-SUPPORT-CS-403"));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 익명 작성 규칙
    // ────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("익명 작성 규칙")
    class AnonymousWriteRules {

        @Test
        @DisplayName("익명 사용자가 비밀글 작성 시도 → 400 (E-SUPPORT-QNA-ANON-SECRET)")
        void anonymousCannotCreateSecretPost() {
            CreateQnaRequest req = new CreateQnaRequest(
                    "익명 비밀글", "내용", TENANT, AGENCY, true,
                    "익명사용자", "anon@example.com");

            assertThatThrownBy(() -> qnaService.create(anonymous, req))
                    .isInstanceOf(SupportApiException.class)
                    .satisfies(ex -> {
                        SupportApiException sae = (SupportApiException) ex;
                        assertThat(sae.getCode()).isEqualTo("E-SUPPORT-QNA-ANON-SECRET");
                        assertThat(sae.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    });
        }

        @Test
        @DisplayName("익명 사용자가 표시 이름 누락 → 400 (E-SUPPORT-QNA-ANON-NAME)")
        void anonymousRequiresDisplayName() {
            CreateQnaRequest req = new CreateQnaRequest(
                    "익명 공개글", "내용", TENANT, AGENCY, false,
                    null, "anon@example.com");

            assertThatThrownBy(() -> qnaService.create(anonymous, req))
                    .isInstanceOf(SupportApiException.class)
                    .satisfies(ex -> assertThat(((SupportApiException) ex).getCode())
                            .isEqualTo("E-SUPPORT-QNA-ANON-NAME"));
        }

        @Test
        @DisplayName("익명 공개글 작성 시 writerUserId=null, anonymousDisplayName 저장")
        void anonymousPublicPostStoresAnonymousFields() {
            CreateQnaRequest req = new CreateQnaRequest(
                    "익명 공개글", "내용", TENANT, AGENCY, false,
                    "이름표시", "anon@example.com");

            QnaDetailResponse created = qnaService.create(anonymous, req);

            assertThat(created.anonymous()).isTrue();
            assertThat(created.writerUserId()).isNull();
            assertThat(created.anonymousDisplayName()).isEqualTo("이름표시");
        }

        @Test
        @DisplayName("로그인 사용자는 비밀글 작성 가능 + writerUserId 저장")
        void authenticatedUserCanCreateSecretPost() {
            QnaDetailResponse created = qnaService.create(alice, secretQnaRequest());

            assertThat(created.secret()).isTrue();
            assertThat(created.anonymous()).isFalse();
            assertThat(created.writerUserId()).isEqualTo(USER_ALICE);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 관리자 답변
    // ────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("관리자 답변 정책")
    class AdminAnswer {

        @Test
        @DisplayName("CS_AGENT 답변 → 정상 등록 + 상태 ANSWERED 전환")
        void csAgentCanAnswer() {
            QnaDetailResponse post = qnaService.create(alice, publicQnaRequest());

            QnaDetailResponse afterAnswer = qnaService.answerByAdmin(
                    csAgent, post.id(),
                    new AnswerQnaRequest("답변 내용입니다.", false));

            assertThat(afterAnswer.status()).isEqualTo("ANSWERED");
            assertThat(afterAnswer.answers()).hasSize(1);
            assertThat(afterAnswer.answers().get(0).contentMasked()).isFalse();
        }

        @Test
        @DisplayName("CS 권한 없으면 답변 등록 시 403 (E-SUPPORT-CS-WRITE-403)")
        void nonStaffCannotAnswer() {
            QnaDetailResponse post = qnaService.create(alice, publicQnaRequest());

            assertThatThrownBy(() -> qnaService.answerByAdmin(
                    nonStaff, post.id(),
                    new AnswerQnaRequest("답변", false)))
                    .isInstanceOf(SupportApiException.class)
                    .satisfies(ex -> assertThat(((SupportApiException) ex).getCode())
                            .isEqualTo("E-SUPPORT-CS-WRITE-403"));
        }

        @Test
        @DisplayName("비밀 Q&A 에 대한 답변은 secret=false 로 보내도 자동 비밀 답변으로 저장")
        void secretQnaAnswerIsAutoSecret() {
            QnaDetailResponse secretPost = qnaService.create(alice, secretQnaRequest());

            QnaDetailResponse afterAnswer = qnaService.answerByAdmin(
                    csAgent, secretPost.id(),
                    new AnswerQnaRequest("비밀 답변", false));

            assertThat(afterAnswer.answers()).hasSize(1);
            // 작성자가 아닌 csAgent (=ADMIN 역할) 시점이지만, toDetailResponse 호출자가 admin role 이므로
            // contentMasked=false. 비밀 답변 여부는 secret=true 로 표기되어야 한다.
            assertThat(afterAnswer.answers().get(0).secret()).isTrue();
        }

        @Test
        @DisplayName("익명 Q&A 에 비밀 답변 요청 → 400 (E-SUPPORT-QNA-ANON-SECRET-ANSWER)")
        void anonymousQnaRejectsSecretAnswer() {
            CreateQnaRequest anonReq = new CreateQnaRequest(
                    "익명 공개글", "내용", TENANT, AGENCY, false,
                    "익명표시", "anon@example.com");
            QnaDetailResponse anonPost = qnaService.create(anonymous, anonReq);

            assertThatThrownBy(() -> qnaService.answerByAdmin(
                    csAgent, anonPost.id(),
                    new AnswerQnaRequest("비밀 답변 시도", true)))
                    .isInstanceOf(SupportApiException.class)
                    .satisfies(ex -> {
                        SupportApiException sae = (SupportApiException) ex;
                        assertThat(sae.getCode()).isEqualTo("E-SUPPORT-QNA-ANON-SECRET-ANSWER");
                        assertThat(sae.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    });
        }

        @Test
        @DisplayName("비밀 답변은 작성자 본인에게는 노출, 다른 USER 에게는 답변 자체 조회 차단 (403)")
        void secretAnswerVisibilityFollowsSecretPostPolicy() {
            QnaDetailResponse secretPost = qnaService.create(alice, secretQnaRequest());
            qnaService.answerByAdmin(csAgent, secretPost.id(),
                    new AnswerQnaRequest("비밀 답변", true));

            // 비밀글 자체가 다른 USER 에게 403 이므로 답변도 도달 불가
            assertThatThrownBy(() -> qnaService.getForUser(bob, secretPost.id()))
                    .isInstanceOf(SupportApiException.class)
                    .satisfies(ex -> assertThat(((SupportApiException) ex).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));

            // 작성자 본인은 비밀 답변 내용 조회 가능
            QnaDetailResponse forOwner = qnaService.getForUser(alice, secretPost.id());
            assertThat(forOwner.answers()).hasSize(1);
            assertThat(forOwner.answers().get(0).contentMasked()).isFalse();
            assertThat(forOwner.answers().get(0).content()).isEqualTo("비밀 답변");
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 테스트 헬퍼
    // ────────────────────────────────────────────────────────────────────

    private CreateQnaRequest publicQnaRequest() {
        return new CreateQnaRequest(
                "공개 질문 " + UUID.randomUUID(), "공개 본문",
                TENANT, AGENCY, false, null, null);
    }

    private CreateQnaRequest secretQnaRequest() {
        return new CreateQnaRequest(
                "비밀 질문 " + UUID.randomUUID(), "비밀 본문",
                TENANT, AGENCY, true, null, null);
    }
}

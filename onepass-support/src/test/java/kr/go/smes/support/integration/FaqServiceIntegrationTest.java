package kr.go.smes.support.integration;

import kr.go.smes.support.api.SupportApiException;
import kr.go.smes.support.api.dto.FaqGroupResponse;
import kr.go.smes.support.api.dto.FaqResponse;
import kr.go.smes.support.application.FaqService;
import kr.go.smes.support.domain.FaqEntity;
import kr.go.smes.support.domain.FaqGroupEntity;
import kr.go.smes.support.domain.FaqGroupRepository;
import kr.go.smes.support.domain.FaqRepository;
import kr.go.smes.support.domain.Yn;
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
 * FaqService 통합 테스트 — FAQ/그룹 조회 회귀 방어
 *
 * <p>검증 범위:
 * <ul>
 *   <li>FAQ 그룹 목록 — sortSn ASC + faqGroupName ASC 정렬, use_yn=N 은 제외</li>
 *   <li>FAQ 목록 — groupCode 필터 / 미지정 시 전체, expsr_yn=N 또는 use_yn=N 은 제외</li>
 *   <li>단건 조회 — 존재하지 않거나 use_yn/expsr_yn=N 이면 404 (E-SUPPORT-FAQ-404)</li>
 *   <li>정렬: sortSn ASC + lastUpdtPnttm DESC</li>
 * </ul>
 */
@DisplayName("FaqService 통합 테스트 — FAQ 그룹/단건 조회 회귀 방어")
@Transactional
class FaqServiceIntegrationTest extends SupportIntegrationTestBase {

    @Autowired
    private FaqService faqService;

    @Autowired
    private FaqGroupRepository faqGroupRepository;

    @Autowired
    private FaqRepository faqRepository;

    @Nested
    @DisplayName("그룹 목록 조회")
    class GroupList {

        @Test
        @DisplayName("use_yn=Y 그룹만 노출 + sortSn ASC 정렬")
        void onlyActiveGroupsReturnedSortedBySortSn() {
            insertGroup("GRP_FAQ_A", "그룹 A (sort 20)", 20, Yn.YES);
            insertGroup("GRP_FAQ_B", "그룹 B (sort 10)", 10, Yn.YES);
            insertGroup("GRP_FAQ_C_DISABLED", "비활성 그룹", 5, Yn.NO);

            List<FaqGroupResponse> groups = faqService.listGroups();

            assertThat(groups)
                    .extracting(FaqGroupResponse::groupCode)
                    .containsExactly("GRP_FAQ_B", "GRP_FAQ_A"); // 정렬: 10, 20
            assertThat(groups)
                    .extracting(FaqGroupResponse::groupCode)
                    .doesNotContain("GRP_FAQ_C_DISABLED"); // use_yn=N 제외
        }
    }

    @Nested
    @DisplayName("FAQ 목록 조회 — groupCode 필터 + 노출 정책")
    class FaqList {

        @Test
        @DisplayName("groupCode 미지정 시 모든 활성 FAQ 반환")
        void noGroupCodeReturnsAllActiveFaqs() {
            FaqGroupEntity grp = insertGroup("GRP_LIST_ALL", "전체 조회 그룹", 1, Yn.YES);
            insertFaq(grp, "질문 1", "답변 1", 1, Yn.YES, Yn.YES);
            insertFaq(grp, "질문 2", "답변 2", 2, Yn.YES, Yn.YES);

            List<FaqResponse> faqs = faqService.listFaqs(null);

            assertThat(faqs).extracting(FaqResponse::question)
                    .contains("질문 1", "질문 2");
        }

        @Test
        @DisplayName("groupCode 필터 → 해당 그룹 FAQ 만 반환")
        void groupCodeFilterReturnsOnlyMatching() {
            FaqGroupEntity grpA = insertGroup("GRP_A", "A 그룹", 1, Yn.YES);
            FaqGroupEntity grpB = insertGroup("GRP_B", "B 그룹", 2, Yn.YES);
            insertFaq(grpA, "A-Q1", "A-A1", 1, Yn.YES, Yn.YES);
            insertFaq(grpA, "A-Q2", "A-A2", 2, Yn.YES, Yn.YES);
            insertFaq(grpB, "B-Q1", "B-A1", 1, Yn.YES, Yn.YES);

            List<FaqResponse> aFaqs = faqService.listFaqs("GRP_A");

            assertThat(aFaqs)
                    .extracting(FaqResponse::question)
                    .containsExactlyInAnyOrder("A-Q1", "A-Q2");
            assertThat(aFaqs)
                    .extracting(FaqResponse::groupCode)
                    .allMatch("GRP_A"::equals);
        }

        @Test
        @DisplayName("expsr_yn=N 또는 use_yn=N FAQ 는 목록에서 제외")
        void hiddenFaqsExcluded() {
            FaqGroupEntity grp = insertGroup("GRP_HIDDEN", "숨김 정책", 1, Yn.YES);
            insertFaq(grp, "노출", "답변", 1, Yn.YES, Yn.YES);
            insertFaq(grp, "미노출", "답변", 2, Yn.YES, Yn.NO);   // expsr_yn=N
            insertFaq(grp, "삭제됨", "답변", 3, Yn.NO,  Yn.YES);  // use_yn=N

            List<FaqResponse> faqs = faqService.listFaqs("GRP_HIDDEN");

            assertThat(faqs).extracting(FaqResponse::question)
                    .containsExactly("노출")
                    .doesNotContain("미노출", "삭제됨");
        }

        @Test
        @DisplayName("sortSn ASC 정렬 — 작은 값이 앞")
        void sortBySortSnAscending() {
            FaqGroupEntity grp = insertGroup("GRP_SORT", "정렬 검증", 1, Yn.YES);
            insertFaq(grp, "sort=30", "a", 30, Yn.YES, Yn.YES);
            insertFaq(grp, "sort=10", "a", 10, Yn.YES, Yn.YES);
            insertFaq(grp, "sort=20", "a", 20, Yn.YES, Yn.YES);

            List<FaqResponse> faqs = faqService.listFaqs("GRP_SORT");

            assertThat(faqs).extracting(FaqResponse::question)
                    .containsExactly("sort=10", "sort=20", "sort=30");
        }

        @Test
        @DisplayName("빈 groupCode (\"   \") 도 null 과 동일하게 전체 조회")
        void blankGroupCodeIsTreatedAsNull() {
            FaqGroupEntity grp = insertGroup("GRP_BLANK", "blank 검증", 1, Yn.YES);
            insertFaq(grp, "전체 조회 대상", "a", 1, Yn.YES, Yn.YES);

            List<FaqResponse> faqs = faqService.listFaqs("   ");

            assertThat(faqs).extracting(FaqResponse::question)
                    .contains("전체 조회 대상");
        }
    }

    @Nested
    @DisplayName("FAQ 단건 조회")
    class FaqDetail {

        @Test
        @DisplayName("존재하는 활성 FAQ → 200")
        void existingActiveFaq() {
            FaqGroupEntity grp = insertGroup("GRP_GET", "단건 조회", 1, Yn.YES);
            FaqEntity faq = insertFaq(grp, "Q?", "A!", 1, Yn.YES, Yn.YES);

            FaqResponse response = faqService.getFaq(faq.getFaqId());

            assertThat(response.id()).isEqualTo(faq.getFaqId());
            assertThat(response.question()).isEqualTo("Q?");
            assertThat(response.answer()).isEqualTo("A!");
        }

        @Test
        @DisplayName("존재하지 않는 ID → 404 (E-SUPPORT-FAQ-404)")
        void nonExistentFaq_returns404() {
            assertThatThrownBy(() -> faqService.getFaq(UUID.randomUUID()))
                    .isInstanceOf(SupportApiException.class)
                    .satisfies(ex -> {
                        SupportApiException sae = (SupportApiException) ex;
                        assertThat(sae.getCode()).isEqualTo("E-SUPPORT-FAQ-404");
                        assertThat(sae.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    });
        }

        @Test
        @DisplayName("use_yn=N 인 FAQ → 단건 조회 시 404")
        void useYnNoFaq_returns404() {
            FaqGroupEntity grp = insertGroup("GRP_NO_USE", "use_yn=N", 1, Yn.YES);
            FaqEntity faq = insertFaq(grp, "삭제됨", "내용", 1, Yn.NO, Yn.YES);

            assertThatThrownBy(() -> faqService.getFaq(faq.getFaqId()))
                    .isInstanceOf(SupportApiException.class)
                    .satisfies(ex -> assertThat(((SupportApiException) ex).getCode())
                            .isEqualTo("E-SUPPORT-FAQ-404"));
        }

        @Test
        @DisplayName("expsr_yn=N 인 FAQ → 단건 조회 시 404 (내부 노출 정책)")
        void exposureYnNoFaq_returns404() {
            FaqGroupEntity grp = insertGroup("GRP_HIDDEN_2", "expsr_yn=N", 1, Yn.YES);
            FaqEntity faq = insertFaq(grp, "비공개", "내용", 1, Yn.YES, Yn.NO);

            assertThatThrownBy(() -> faqService.getFaq(faq.getFaqId()))
                    .isInstanceOf(SupportApiException.class)
                    .satisfies(ex -> assertThat(((SupportApiException) ex).getCode())
                            .isEqualTo("E-SUPPORT-FAQ-404"));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 테스트 헬퍼 — 직접 entity 를 영속화하여 시드 데이터 구성
    // ────────────────────────────────────────────────────────────────────

    private FaqGroupEntity insertGroup(String code, String name, int sortSn, String useYn) {
        Instant now = Instant.now();
        FaqGroupEntity grp = FaqGroupEntity.builder()
                .faqGroupId(UUID.randomUUID())
                .faqGroupCode(code)
                .faqGroupName(name)
                .sortSn(sortSn)
                .useYn(useYn)
                .frstRegistPnttm(now)
                .frstRegisterId("test")
                .lastUpdtPnttm(now)
                .lastUpdusrId("test")
                .build();
        return faqGroupRepository.save(grp);
    }

    private FaqEntity insertFaq(
            FaqGroupEntity group, String question, String answer, int sortSn, String useYn, String exposureYn) {
        Instant now = Instant.now();
        FaqEntity faq = FaqEntity.builder()
                .faqId(UUID.randomUUID())
                .faqGroup(group)
                .faqQuestionContent(question)
                .faqAnswerContent(answer)
                .sortSn(sortSn)
                .useYn(useYn)
                .exposureYn(exposureYn)
                .frstRegistPnttm(now)
                .frstRegisterId("test")
                .lastUpdtPnttm(now)
                .lastUpdusrId("test")
                .build();
        return faqRepository.save(faq);
    }
}

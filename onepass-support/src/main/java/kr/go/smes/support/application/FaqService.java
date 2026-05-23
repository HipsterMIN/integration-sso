package kr.go.smes.support.application;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.go.smes.support.api.SupportApiException;
import kr.go.smes.support.api.dto.FaqGroupResponse;
import kr.go.smes.support.api.dto.FaqResponse;
import kr.go.smes.support.domain.FaqEntity;
import kr.go.smes.support.domain.FaqGroupEntity;
import kr.go.smes.support.domain.FaqGroupRepository;
import kr.go.smes.support.domain.FaqRepository;
import kr.go.smes.support.domain.Yn;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FaqService {

    private final FaqGroupRepository faqGroupRepository;
    private final FaqRepository faqRepository;

    public List<FaqGroupResponse> listGroups() {
        return faqGroupRepository.findByUseYnOrderBySortSnAscFaqGroupNameAsc(Yn.YES).stream()
                .map(this::toGroupResponse)
                .toList();
    }

    public List<FaqResponse> listFaqs(String groupCode) {
        List<FaqEntity> entities = (groupCode == null || groupCode.isBlank())
                ? faqRepository.findByUseYnAndExposureYnOrderBySortSnAscLastUpdtPnttmDesc(Yn.YES, Yn.YES)
                : faqRepository.findByFaqGroupFaqGroupCodeAndUseYnAndExposureYnOrderBySortSnAscLastUpdtPnttmDesc(
                        groupCode, Yn.YES, Yn.YES);
        return entities.stream().map(this::toFaqResponse).toList();
    }

    public FaqResponse getFaq(UUID faqId) {
        FaqEntity faq = faqRepository.findById(faqId)
                .filter(it -> Yn.YES.equals(it.getUseYn()) && Yn.YES.equals(it.getExposureYn()))
                .orElseThrow(() -> new SupportApiException(
                        "E-SUPPORT-FAQ-404",
                        "FAQ를 찾을 수 없습니다.",
                        HttpStatus.NOT_FOUND));
        return toFaqResponse(faq);
    }

    private FaqGroupResponse toGroupResponse(FaqGroupEntity entity) {
        return new FaqGroupResponse(
                entity.getFaqGroupId(),
                entity.getFaqGroupCode(),
                entity.getFaqGroupName(),
                entity.getFaqGroupDescription(),
                entity.getSortSn(),
                entity.getLastUpdtPnttm()
        );
    }

    private FaqResponse toFaqResponse(FaqEntity entity) {
        return new FaqResponse(
                entity.getFaqId(),
                entity.getFaqGroup().getFaqGroupCode(),
                entity.getFaqGroup().getFaqGroupName(),
                entity.getFaqQuestionContent(),
                entity.getFaqAnswerContent(),
                entity.getSortSn(),
                entity.getLastUpdtPnttm()
        );
    }
}


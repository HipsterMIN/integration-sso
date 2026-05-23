package kr.go.smes.support.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FaqRepository extends JpaRepository<FaqEntity, UUID> {

    List<FaqEntity> findByUseYnAndExposureYnOrderBySortSnAscLastUpdtPnttmDesc(String useYn, String exposureYn);

    List<FaqEntity> findByFaqGroupFaqGroupCodeAndUseYnAndExposureYnOrderBySortSnAscLastUpdtPnttmDesc(
            String faqGroupCode,
            String useYn,
            String exposureYn
    );
}


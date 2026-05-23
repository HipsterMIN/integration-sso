package kr.go.smes.support.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FaqGroupRepository extends JpaRepository<FaqGroupEntity, UUID> {

    List<FaqGroupEntity> findByUseYnOrderBySortSnAscFaqGroupNameAsc(String useYn);

    Optional<FaqGroupEntity> findByFaqGroupCodeAndUseYn(String faqGroupCode, String useYn);
}


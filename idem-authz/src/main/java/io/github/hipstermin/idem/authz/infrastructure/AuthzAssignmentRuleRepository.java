package io.github.hipstermin.idem.authz.infrastructure;

import io.github.hipstermin.idem.authz.domain.AuthzAssignmentRuleEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 1.1 규칙 할당 저장소. */
public interface AuthzAssignmentRuleRepository extends JpaRepository<AuthzAssignmentRuleEntity, UUID> {
    List<AuthzAssignmentRuleEntity> findByAgencyCodeAndEnabledTrueOrderByCreatedAtAsc(String agencyCode);
    List<AuthzAssignmentRuleEntity> findByAgencyCodeOrderByCreatedAtAsc(String agencyCode);
}

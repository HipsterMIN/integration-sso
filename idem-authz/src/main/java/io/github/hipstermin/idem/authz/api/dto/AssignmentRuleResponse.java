package io.github.hipstermin.idem.authz.api.dto;

import io.github.hipstermin.idem.authz.domain.AuthzAssignmentRuleEntity;
import java.time.Instant;
import java.util.List;

/** 1.1 규칙 할당 응답. */
public record AssignmentRuleResponse(
        String id,
        String agencyCode,
        String ruleType,
        String matchKey,
        List<String> matchValues,
        Integer expiresDays,
        boolean enabled,
        String description,
        String createdBy,
        Instant createdAt,
        Instant disabledAt
) {
    public static AssignmentRuleResponse from(AuthzAssignmentRuleEntity e) {
        return new AssignmentRuleResponse(e.getId().toString(), e.getAgencyCode(), e.getRuleType().name(),
                e.getMatchKey(), e.matchValueList(), e.getExpiresDays(), e.isEnabled(), e.getDescription(),
                e.getCreatedBy(), e.getCreatedAt(), e.getDisabledAt());
    }
}

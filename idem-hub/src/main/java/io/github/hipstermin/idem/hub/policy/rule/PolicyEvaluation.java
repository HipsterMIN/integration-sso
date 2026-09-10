package io.github.hipstermin.idem.hub.policy.rule;

import java.util.List;
import java.util.Optional;

/**
 * 규칙 집합의 평가 결과 (S3).
 *
 * @param decisions 평가된 순서대로. {@code stopAtFirstDenial} 로 중단됐으면 뒤 규칙은 없다
 */
public record PolicyEvaluation(List<PolicyDecision> decisions) {

    public PolicyEvaluation {
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    public static PolicyEvaluation allowedAll() {
        return new PolicyEvaluation(List.of());
    }

    public Optional<PolicyDecision> firstDenial() {
        return decisions.stream().filter(PolicyDecision::denied).findFirst();
    }

    public boolean allowed() {
        return firstDenial().isEmpty();
    }
}

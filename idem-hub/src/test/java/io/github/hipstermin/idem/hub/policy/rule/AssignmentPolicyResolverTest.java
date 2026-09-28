package io.github.hipstermin.idem.hub.policy.rule;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 1.1: 할당 정책 단일 해석기 — 규칙 파라미터는 프로파일 블록을 조이기만 한다. */
class AssignmentPolicyResolverTest {

    private static ServiceProfile profile(ServiceProfile.Assignment a, List<ServiceProfile.RuleRef> rules) {
        var policy = ServiceProfile.Policy.builder().minAuthLevel(AuthResult.AuthLevel.L1).assignment(a).rules(rules).build();
        return ServiceProfile.builder().policy(policy).build();
    }

    @Test @DisplayName("블록도 규칙도 없으면 할당을 보지 않는다")
    void none() {
        assertThat(AssignmentPolicyResolver.resolve((ServiceProfile) null)).isEqualTo(AssignmentPolicyResolver.Effective.NONE);
        assertThat(AssignmentPolicyResolver.resolve(profile(null, null))).isEqualTo(AssignmentPolicyResolver.Effective.NONE);
    }

    @Test @DisplayName("블록 그대로")
    void block() {
        var e = AssignmentPolicyResolver.resolve(profile(new ServiceProfile.Assignment(true, true), null));
        assertThat(e.required()).isTrue();
        assertThat(e.selfSignup()).isTrue();
    }

    @Test @DisplayName("규칙 파라미터 required=false 는 블록의 required=true 를 풀지 못한다 (종전 불일치 원인)")
    void ruleCannotLoosenRequired() {
        var rules = List.of(new ServiceProfile.RuleRef(AssignmentRule.TYPE, Map.of("required", false)));
        var e = AssignmentPolicyResolver.resolve(profile(new ServiceProfile.Assignment(true, false), rules));
        assertThat(e.required()).isTrue();
    }

    @Test @DisplayName("규칙 파라미터 required=true 는 블록이 없어도 필수로 조인다")
    void ruleCanTightenRequired() {
        var rules = List.of(new ServiceProfile.RuleRef("assignment", Map.of("required", "true")));
        var e = AssignmentPolicyResolver.resolve(profile(null, rules));
        assertThat(e.required()).isTrue();
        assertThat(e.selfSignup()).isFalse();
    }

    @Test @DisplayName("규칙 파라미터 selfSignup=false 는 블록의 selfSignup=true 를 닫는다, true 는 열지 못한다")
    void ruleCanOnlyCloseSelfSignup() {
        var close = List.of(new ServiceProfile.RuleRef(AssignmentRule.TYPE, Map.of("selfSignup", false)));
        assertThat(AssignmentPolicyResolver.resolve(profile(new ServiceProfile.Assignment(true, true), close)).selfSignup()).isFalse();
        var open = List.of(new ServiceProfile.RuleRef(AssignmentRule.TYPE, Map.of("selfSignup", true)));
        assertThat(AssignmentPolicyResolver.resolve(profile(new ServiceProfile.Assignment(true, false), open)).selfSignup()).isFalse();
    }

    @Test @DisplayName("evaluate 에 넘어온 파라미터도 같은 규칙으로 합쳐진다")
    void extraParamsMerged() {
        var policy = ServiceProfile.Policy.builder().assignment(new ServiceProfile.Assignment(false, true)).build();
        var e = AssignmentPolicyResolver.resolve(policy, Map.of("required", true));
        assertThat(e.required()).isTrue();
        assertThat(e.selfSignup()).isTrue();
    }
}

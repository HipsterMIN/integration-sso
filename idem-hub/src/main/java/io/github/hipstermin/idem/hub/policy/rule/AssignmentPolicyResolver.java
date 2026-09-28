package io.github.hipstermin.idem.hub.policy.rule;

import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import java.util.List;
import java.util.Map;

/**
 * 1.1: 할당 정책의 <b>단일 해석기</b> — 발급 경로 넷(Handoff 페이로드·OIDC 토큰 교환·CAST·AssignmentRule)이 같은 답을 내게 한다.
 *
 * <p>종전에는 {@code AssignmentRule} 만 프로파일 {@code policy.rules[{type:ASSIGNMENT, params}]} 의 파라미터로 블록을 덮어썼고
 * 상태 계산(PolicyEngineImpl·OidcRpAccessService·CastTokenServiceImpl)은 {@code policy.assignment} 만 봐서, 규칙 파라미터로
 * {@code required=false} 를 주면 규칙은 통과시키는데 상태는 할당 기준으로 나오는 불일치가 있었다(S8-b PR-2 남긴 것).
 *
 * <p>이제 규칙 파라미터는 프로파일 블록을 <b>조이기만</b> 한다: {@code required} 는 OR, {@code selfSignup} 은 AND.
 * 규칙으로 할당 필수를 풀거나 셀프 가입을 열 수는 없다 — 그 결정은 프로파일 블록(관리 콘솔 검토 대상)에만 있다.
 */
public final class AssignmentPolicyResolver {

    /** 해석된 할당 정책. */
    public record Effective(boolean required, boolean selfSignup) {
        public static final Effective NONE = new Effective(false, false);
    }

    private AssignmentPolicyResolver() {}

    /** 프로파일 전체 기준 — 블록 + {@code rules[]} 의 ASSIGNMENT 파라미터. */
    public static Effective resolve(ServiceProfile profile) {
        ServiceProfile.Policy policy = profile != null ? profile.policy() : null;
        return resolve(policy, null);
    }

    /**
     * 정책 블록 + 규칙 파라미터 기준. {@code extraParams} 는 {@code AssignmentRule.evaluate} 가 받은 파라미터(같은 규칙의 것)이며
     * {@code policy.rules[]} 에 실린 것과 합쳐 조이기만 한다.
     */
    public static Effective resolve(ServiceProfile.Policy policy, Map<String, Object> extraParams) {
        ServiceProfile.Assignment cfg = policy != null ? policy.assignment() : null;
        boolean required   = cfg != null && cfg.requiresAssignment();
        boolean selfSignup = cfg != null && cfg.allowsSelfSignup();

        List<ServiceProfile.RuleRef> rules = policy != null && policy.rules() != null ? policy.rules() : List.of();
        for (ServiceProfile.RuleRef ref : rules) {
            if (ref != null && AssignmentRule.TYPE.equalsIgnoreCase(ref.type())) {
                required   = required   || flag(ref.params(), "required",   false);
                selfSignup = selfSignup && flagOr(ref.params(), "selfSignup", true);
            }
        }
        if (extraParams != null && !extraParams.isEmpty()) {
            required   = required   || flag(extraParams, "required",   false);
            selfSignup = selfSignup && flagOr(extraParams, "selfSignup", true);
        }
        if (!required) selfSignup = false;   // 할당을 보지 않는 프로파일에서 selfSignup 은 의미가 없다
        return new Effective(required, selfSignup);
    }

    private static boolean flag(Map<String, Object> params, String key, boolean fallback) {
        return flagOr(params, key, fallback);
    }

    private static boolean flagOr(Map<String, Object> params, String key, boolean fallback) {
        Object v = params != null ? params.get(key) : null;
        if (v instanceof Boolean b) return b;
        if (v instanceof String st) return Boolean.parseBoolean(st.trim());
        return fallback;
    }
}

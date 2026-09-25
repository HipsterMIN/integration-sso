package io.github.hipstermin.idem.hub.policy.rule;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.hub.infrastructure.ServiceAccess;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * S8-b 내장 규칙 {@code ASSIGNMENT} — 프로파일 {@code policy.assignment.required=true} 면 idem-authz 에
 * 이 Service 할당이 있는 사용자만 통과한다.
 *
 * <ul>
 *   <li>블록 없음/required=false → ALLOW (할당을 보지 않음)</li>
 *   <li>할당 있음 → ALLOW</li>
 *   <li>미할당 + selfSignup=true → ALLOW (페이로드 상태는 GUEST — {@code PolicyEngineImpl.buildHandoffPayload})</li>
 *   <li>미할당 + selfSignup=false → DENY {@code E-IDO-120}</li>
 *   <li>authz 비활성 설치(idem.hub.authz.enabled=false)인데 required → DENY {@code E-IDO-117} (평가 불가 = 거부, fail-secure)</li>
 * </ul>
 * 규칙 파라미터 {@code required}/{@code selfSignup} 이 있으면 프로파일 블록보다 우선한다(시뮬레이션·에디션 오버라이드용).
 * order 95: USER_STATUS(90) 뒤 — 원격 조회이므로 값싼 규칙이 먼저 거른 뒤에 부른다.
 */
@Component
public class AssignmentRule implements PolicyRule {

    public static final String TYPE = "ASSIGNMENT";

    @Override public String type() { return TYPE; }
    @Override public boolean builtIn() { return true; }
    @Override public int order() { return 95; }

    @Override
    public PolicyDecision evaluate(PolicyContext ctx, Map<String, Object> params) {
        ServiceProfile.Assignment cfg = ctx.policy() != null ? ctx.policy().assignment() : null;
        boolean required   = flag(params, "required",   cfg != null && cfg.requiresAssignment());
        boolean selfSignup = flag(params, "selfSignup", cfg != null && cfg.allowsSelfSignup());
        if (!required) {
            return PolicyDecision.allow(TYPE, "할당 정책 미적용");
        }
        if (ctx.serviceAccess() == null) {
            return PolicyDecision.skip(TYPE, "할당 정보 미제공");
        }
        ServiceAccess access = ctx.serviceAccess().get();
        if (access == null || !access.authzEnabled()) {
            return PolicyDecision.deny(TYPE, "할당 필수 정책인데 인가 서비스(idem-authz)가 비활성 — 평가 불가",
                    "ASSIGNMENT_UNEVALUABLE", PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE);
        }
        if (access.assigned()) {
            return PolicyDecision.allow(TYPE, "할당됨 (" + access.assignmentSource() + ")");
        }
        if (selfSignup) {
            return PolicyDecision.allow(TYPE, "미할당 — 셀프 가입 허용 → GUEST");
        }
        return PolicyDecision.deny(TYPE, "이 Service 에 할당되지 않은 사용자",
                "ASSIGNMENT_REQUIRED", PlatformErrorCode.IDO_ASSIGNMENT_REQUIRED);
    }

    private static boolean flag(Map<String, Object> params, String key, boolean fallback) {
        Object v = params != null ? params.get(key) : null;
        if (v instanceof Boolean b) return b;
        if (v instanceof String st) return Boolean.parseBoolean(st.trim());
        return fallback;
    }
}

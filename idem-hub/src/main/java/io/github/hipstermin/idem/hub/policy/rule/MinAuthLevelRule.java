package io.github.hipstermin.idem.hub.policy.rule;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 최소 인증수준 — 프로파일 {@code policy.minAuthLevel} 이상이어야 한다. 요청 수준이 없으면 거부(fail-closed). */
@Component
public class MinAuthLevelRule implements PolicyRule {

    public static final String TYPE = "MIN_AUTH_LEVEL";

    @Override public String type() { return TYPE; }
    @Override public boolean builtIn() { return true; }
    @Override public int order() { return 20; }

    @Override
    public PolicyDecision evaluate(PolicyContext ctx, Map<String, Object> params) {
        AuthResult.AuthLevel required = ctx.policy() != null && ctx.policy().minAuthLevel() != null
                ? ctx.policy().minAuthLevel() : AuthResult.AuthLevel.L1;
        AuthResult.AuthLevel actual = ctx.authLevel();
        if (actual == null) {
            return PolicyDecision.deny(TYPE, "요청에 인증수준이 없음 (필요: " + required + ")",
                    "AUTH_LEVEL_INSUFFICIENT", PlatformErrorCode.IDO_AUTH_LEVEL_INSUFFICIENT);
        }
        if (!actual.meets(required)) {
            return PolicyDecision.deny(TYPE, "인증수준 미달: " + actual + " < " + required,
                    "AUTH_LEVEL_INSUFFICIENT", PlatformErrorCode.IDO_AUTH_LEVEL_INSUFFICIENT);
        }
        return PolicyDecision.allow(TYPE, actual + " ≥ " + required);
    }
}

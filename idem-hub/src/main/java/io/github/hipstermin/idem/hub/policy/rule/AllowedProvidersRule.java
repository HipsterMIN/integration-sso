package io.github.hipstermin.idem.hub.policy.rule;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 허용 본인인증 제공자 — 프로파일 {@code policy.allowedProviders}. 설정이 없거나 비어 있으면 제한 없음(SKIP).
 * 설정이 있는데 요청에 제공자 코드가 없으면 거부(fail-closed).
 */
@Component
public class AllowedProvidersRule implements PolicyRule {

    public static final String TYPE = "ALLOWED_PROVIDERS";

    @Override public String type() { return TYPE; }
    @Override public boolean builtIn() { return true; }
    @Override public int order() { return 30; }

    @Override
    public PolicyDecision evaluate(PolicyContext ctx, Map<String, Object> params) {
        List<String> allowed = ctx.policy() != null ? ctx.policy().allowedProviders() : null;
        if (allowed == null || allowed.isEmpty()) {
            return PolicyDecision.skip(TYPE, "허용 제공자 제한 없음");
        }
        String provider = ctx.providerCode();
        if (provider == null || provider.isBlank()) {
            return PolicyDecision.deny(TYPE, "요청에 제공자 코드가 없음 (허용: " + allowed + ")",
                    "PROVIDER_NOT_ALLOWED", PlatformErrorCode.IDO_POLICY_REJECTED);
        }
        boolean ok = allowed.stream().anyMatch(a -> a != null && a.trim().equalsIgnoreCase(provider.trim()));
        return ok
                ? PolicyDecision.allow(TYPE, provider.toUpperCase(Locale.ROOT) + " 허용")
                : PolicyDecision.deny(TYPE, "허용되지 않은 제공자: " + provider + " (허용: " + allowed + ")",
                        "PROVIDER_NOT_ALLOWED", PlatformErrorCode.IDO_POLICY_REJECTED);
    }
}

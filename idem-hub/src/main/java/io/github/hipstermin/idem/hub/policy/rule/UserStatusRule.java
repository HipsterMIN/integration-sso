package io.github.hipstermin.idem.hub.policy.rule;

import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 사용자 상태 — 기본으로 SUSPENDED·WITHDRAWN 을 거부한다(종전 Handoff 발급 동작과 동일).
 * 프로파일 {@code policy.rules[type=USER_STATUS].params.deny} 로 거부 집합을 바꿀 수 있다.
 * 상태 공급자가 없으면(시뮬레이션에서 상태 미지정) SKIP. 외부 조회 비용 때문에 내장 규칙 중 마지막.
 */
@Component
public class UserStatusRule implements PolicyRule {

    public static final String TYPE = "USER_STATUS";
    public static final Set<UserStatus> DEFAULT_DENY = Set.of(UserStatus.SUSPENDED, UserStatus.WITHDRAWN);

    @Override public String type() { return TYPE; }
    @Override public boolean builtIn() { return true; }
    @Override public int order() { return 90; }

    @Override
    public PolicyDecision evaluate(PolicyContext ctx, Map<String, Object> params) {
        if (ctx.userStatus() == null) {
            return PolicyDecision.skip(TYPE, "사용자 상태 미제공");
        }
        UserStatus status = ctx.userStatus().get();
        if (status == null) {
            return PolicyDecision.skip(TYPE, "사용자 상태 미제공");
        }
        Set<UserStatus> deny = denySet(params);
        if (deny.contains(status)) {
            return PolicyDecision.deny(TYPE, "사용자 상태 " + status + " 는 거부 대상",
                    "USER_" + status.name(), errorCodeFor(status));
        }
        return PolicyDecision.allow(TYPE, "사용자 상태 " + status);
    }

    private static Set<UserStatus> denySet(Map<String, Object> params) {
        Object raw = params != null ? params.get("deny") : null;
        if (!(raw instanceof Collection<?> values)) return DEFAULT_DENY;
        List<UserStatus> parsed = values.stream()
                .map(v -> String.valueOf(v).trim().toUpperCase(Locale.ROOT))
                .filter(v -> !v.isEmpty())
                .map(v -> { try { return UserStatus.valueOf(v); } catch (IllegalArgumentException e) { return null; } })
                .filter(v -> v != null)
                .collect(Collectors.toList());
        return parsed.isEmpty() ? DEFAULT_DENY : Set.copyOf(parsed);
    }

    private static PlatformErrorCode errorCodeFor(UserStatus status) {
        return switch (status) {
            case SUSPENDED -> PlatformErrorCode.IM_USER_SUSPENDED;
            case WITHDRAWN -> PlatformErrorCode.IM_USER_WITHDRAWN;
            default        -> PlatformErrorCode.IDO_POLICY_REJECTED;
        };
    }
}

package io.github.hipstermin.idem.hub.policy.rule;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;

/**
 * 규칙 하나의 결정 (S3).
 *
 * @param rule        규칙 식별자 ({@link PolicyRule#type()})
 * @param outcome     ALLOW / DENY / SKIP(해당 설정 없음·평가 불가)
 * @param reason      사람이 읽는 사유 — 감사 로그·시뮬레이션 응답에 실린다
 * @param auditReason 감사 로그의 짧은 코드 (예: AUTH_LEVEL_INSUFFICIENT). DENY 일 때만
 * @param errorCode   DENY 를 API 오류로 바꿀 때의 코드. DENY 일 때만
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyDecision(
        String rule,
        Outcome outcome,
        String reason,
        String auditReason,
        PlatformErrorCode errorCode) {

    public enum Outcome { ALLOW, DENY, SKIP }

    public static PolicyDecision allow(String rule, String reason) {
        return new PolicyDecision(rule, Outcome.ALLOW, reason, null, null);
    }

    public static PolicyDecision skip(String rule, String reason) {
        return new PolicyDecision(rule, Outcome.SKIP, reason, null, null);
    }

    public static PolicyDecision deny(String rule, String reason, String auditReason, PlatformErrorCode errorCode) {
        return new PolicyDecision(rule, Outcome.DENY, reason, auditReason, errorCode);
    }

    public boolean denied() {
        return outcome == Outcome.DENY;
    }
}

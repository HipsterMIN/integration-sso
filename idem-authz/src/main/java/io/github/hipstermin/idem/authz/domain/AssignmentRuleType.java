package io.github.hipstermin.idem.authz.domain;

/** 1.1 규칙 할당 — 규칙 유형. */
public enum AssignmentRuleType {
    /** {@code match_key = "agencyCode:roleCode"} (SCIM Group id) — 그 역할이 유효한 사용자 */
    GROUP,
    /** {@code match_key = 속성명}, {@code match_values} 에 값이 있으면 통과. 속성은 hub 가 접근 평가 시 보내는 비-PII 컨텍스트 */
    ATTRIBUTE
}

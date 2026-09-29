package io.github.hipstermin.idem.authz.domain;

/** 인가 감사 이벤트 종류. */
public enum AuditEvent {
    GRANT,
    REVOKE,
    EXPIRE,
    ROLE_CREATED,
    /** S8-b 할당 */
    ASSIGN,
    UNASSIGN,
    /** 1.1 규칙 할당 — 규칙 생성/비활성 (사용자 없음, agency_code 만) */
    RULE_CREATED,
    RULE_DISABLED
}

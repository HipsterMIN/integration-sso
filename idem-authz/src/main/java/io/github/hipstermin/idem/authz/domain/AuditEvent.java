package io.github.hipstermin.idem.authz.domain;

/** 인가 감사 이벤트 종류. */
public enum AuditEvent {
    GRANT,
    REVOKE,
    EXPIRE,
    ROLE_CREATED,
    /** S8-b 할당 */
    ASSIGN,
    UNASSIGN
}

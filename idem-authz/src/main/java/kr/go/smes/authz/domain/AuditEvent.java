package kr.go.smes.authz.domain;

/** 인가 감사 이벤트 종류. */
public enum AuditEvent {
    GRANT,
    REVOKE,
    EXPIRE,
    ROLE_CREATED
}

package kr.go.smes.authz.api;

import org.springframework.http.HttpStatus;

/** q-authz 도메인 오류 코드. */
public enum AuthzErrorCode {

    ROLE_NOT_FOUND        ("E-AUTHZ-404-ROLE", HttpStatus.NOT_FOUND,   "역할이 존재하지 않습니다."),
    ROLE_NOT_ASSIGNABLE   ("E-AUTHZ-409-ROLE", HttpStatus.CONFLICT,    "부여 불가(is_assignable=false) 역할입니다."),
    ROLE_ALREADY_EXISTS   ("E-AUTHZ-409-DUP",  HttpStatus.CONFLICT,    "이미 존재하는 역할입니다."),
    ASSIGNMENT_NOT_FOUND  ("E-AUTHZ-404-ASGN", HttpStatus.NOT_FOUND,   "역할 부여 내역이 없습니다."),
    INVALID_REQUEST       ("E-AUTHZ-400",      HttpStatus.BAD_REQUEST, "요청이 유효하지 않습니다.");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;

    AuthzErrorCode(String code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }
    public String defaultMessage() { return defaultMessage; }
}

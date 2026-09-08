package io.github.hipstermin.idem.authz.api;

import lombok.Getter;

/** q-authz 도메인 예외. */
@Getter
public class AuthzException extends RuntimeException {

    private final AuthzErrorCode errorCode;

    public AuthzException(AuthzErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public AuthzException(AuthzErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}

package com.onepass.common.error;

import lombok.Getter;

/**
 * 플랫폼 표준 예외
 */
@Getter
public class PlatformException extends RuntimeException {

    private final PlatformErrorCode errorCode;
    private final String correlationId;

    public PlatformException(PlatformErrorCode errorCode, String correlationId) {
        super(errorCode.getDefaultMessage());
        this.errorCode     = errorCode;
        this.correlationId = correlationId;
    }

    public PlatformException(PlatformErrorCode errorCode, String correlationId, String detail) {
        super(detail);
        this.errorCode     = errorCode;
        this.correlationId = correlationId;
    }

    public PlatformException(PlatformErrorCode errorCode, String correlationId, Throwable cause) {
        super(errorCode.getDefaultMessage(), cause);
        this.errorCode     = errorCode;
        this.correlationId = correlationId;
    }
}

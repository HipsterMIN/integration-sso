package kr.go.smes.support.api;

import org.springframework.http.HttpStatus;

public class SupportApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public SupportApiException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}


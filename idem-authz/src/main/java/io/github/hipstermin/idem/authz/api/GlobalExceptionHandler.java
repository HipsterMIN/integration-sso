package io.github.hipstermin.idem.authz.api;

import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * authz 글로벌 예외 핸들러 — 본문 {@code {error, message, timestamp}}.
 *
 * <p>1.0.1: 없는 경로·메서드·미디어 타입도 같은 본문으로 답한다(종전에는 Spring 기본 본문이 요청 경로를 반사했고 플랫폼 코드가 없어
 * registry 의 {@code E-IM-404} 와 계약이 달랐다).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthzException.class)
    public ResponseEntity<Map<String, Object>> handleAuthz(AuthzException e) {
        AuthzErrorCode ec = e.getErrorCode();
        log.warn("[q-authz] {} — {}", ec.code(), e.getMessage());
        return ResponseEntity.status(ec.status()).body(body(ec.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse(AuthzErrorCode.INVALID_REQUEST.defaultMessage());
        return ResponseEntity.status(AuthzErrorCode.INVALID_REQUEST.status())
                .body(body(AuthzErrorCode.INVALID_REQUEST.code(), msg));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound(Exception e) {
        log.debug("[q-authz] 없는 경로: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("E-AUTHZ-404", "요청한 경로가 없습니다."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body("E-AUTHZ-405", "허용되지 않은 HTTP 메서드입니다."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(body("E-AUTHZ-415", "지원하지 않는 Content-Type 입니다."));
    }

    private Map<String, Object> body(String code, String message) {
        return Map.of(
                "error", code,
                "message", message,
                "timestamp", Instant.now().toString());
    }
}

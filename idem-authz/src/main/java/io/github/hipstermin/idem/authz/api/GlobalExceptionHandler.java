package io.github.hipstermin.idem.authz.api;

import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    private Map<String, Object> body(String code, String message) {
        return Map.of(
                "error", code,
                "message", message,
                "timestamp", Instant.now().toString());
    }
}

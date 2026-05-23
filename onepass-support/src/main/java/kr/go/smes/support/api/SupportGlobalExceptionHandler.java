package kr.go.smes.support.api;

import java.time.Instant;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import kr.go.smes.common.error.ErrorResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class SupportGlobalExceptionHandler {

    @ExceptionHandler(SupportApiException.class)
    public ResponseEntity<ErrorResponse> handleSupport(SupportApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ErrorResponse.builder()
                        .code(ex.getCode())
                        .message(ex.getMessage())
                        .timestamp(Instant.now())
                        .build());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.builder()
                        .code("E-SUPPORT-400")
                        .message(message.isBlank() ? "입력값 검증에 실패했습니다." : message)
                        .timestamp(Instant.now())
                        .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("[SUPPORT] unexpected exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.builder()
                        .code("E-SUPPORT-500")
                        .message("내부 서버 오류가 발생했습니다.")
                        .timestamp(Instant.now())
                        .build());
    }
}


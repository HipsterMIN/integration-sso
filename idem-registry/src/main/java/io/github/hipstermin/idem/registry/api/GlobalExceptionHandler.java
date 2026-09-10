package io.github.hipstermin.idem.registry.api;

import io.github.hipstermin.idem.common.error.ErrorResponse;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Q-IM 글로벌 예외 핸들러
 * 설계서 §6.3 오류 응답 표준
 *
 * PlatformException → 표준 오류 응답 변환
 * 미처리 예외 → 500 Internal Server Error
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 기본 Retry-After 값 (초) — E-OPS-901 장애 시 재시도 권고 간격 */
    private static final int DEFAULT_RETRY_AFTER_SECONDS = 30;

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ErrorResponse> handlePlatformException(PlatformException ex) {
        log.warn("[Q-IM] PlatformException code={} correlationId={}",
                ex.getErrorCode().getCode(), ex.getCorrelationId(), ex);

        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(ex.getErrorCode().getHttpStatus());

        // E-OPS-901 외부 사업자 장애: Retry-After 헤더 필수 (설계서 §17.5)
        if (PlatformErrorCode.OPS_EXTERNAL_SYSTEM_ERROR.equals(ex.getErrorCode())) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(DEFAULT_RETRY_AFTER_SECONDS));
        }
        // IDO_QIM_UNREACHABLE / QS_PROVIDER_TIMEOUT 도 재시도 안내
        if (PlatformErrorCode.IDO_QIM_UNREACHABLE.equals(ex.getErrorCode())
                || PlatformErrorCode.QS_PROVIDER_TIMEOUT.equals(ex.getErrorCode())) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(DEFAULT_RETRY_AFTER_SECONDS));
        }

        return builder.body(ErrorResponse.of(ex.getErrorCode(), ex.getCorrelationId()));
    }

    /**
     * Bean Validation 실패 (@Valid @RequestBody) → 400 Bad Request
     *
     * <p>첫 번째 필드 오류의 메시지를 응답에 포함합니다.
     * 복수 오류가 있는 경우 message 필드에 쉼표 구분으로 모두 포함합니다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .reduce((a, b) -> a + "; " + b)
                .orElse("입력값 검증에 실패했습니다.");

        log.warn("[Q-IM] 입력 검증 실패: {}", message);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.builder()
                        .code("E-IM-400")
                        .message(message)
                        .timestamp(java.time.Instant.now())
                        .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("[Q-IM] 미처리 예외 발생", ex);
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.builder()
                        .code("E-QIM-500")
                        .message("내부 서버 오류가 발생했습니다.")
                        .timestamp(java.time.Instant.now())
                        .build());
    }
}

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
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Q-IM 글로벌 예외 핸들러
 * 설계서 §6.3 오류 응답 표준
 *
 * PlatformException → 표준 오류 응답 변환
 * 없는 경로 → 404 Not Found (catch-all 보다 먼저 — 에디션 분리 뒤 KR 전용 경로가 코어에 없는 것은 정상)
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
        // IDEM_HUB_REGISTRY_UNREACHABLE / QS_PROVIDER_TIMEOUT 도 재시도 안내
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

    /**
     * 없는 경로 (404) — 종전에는 catch-all 이 500 으로 바꿔 "엔드포인트 없음"과 "서버 오류"를 구분할 수 없었다.
     *
     * <p>Spring 6.1+ 는 매핑되지 않은 경로에 {@link NoResourceFoundException}(기본 리소스 핸들러) 또는
     * {@link NoHandlerFoundException} 을 던진다. 에디션 분리 뒤에는 KR 전용 경로
     * ({@code /api/v1/internal/biz-members/**} 등)가 코어 registry 에 없는 것이 정상 상태이므로 404 를 그대로 돌려준다
     * — {@code scripts/kr-member-import} 는 이 404 를 "registry 가 KR 에디션이 아님" 신호로 쓴다.
     * hub 의 동일 처리(S8-a)와 같은 계약.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception ex) {
        log.debug("[Q-IM] 없는 경로: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.builder()
                        .code("E-IM-404")
                        .message("요청한 경로가 없습니다.")
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

package kr.go.smes.qim.api;

import kr.go.smes.common.error.ErrorResponse;
import kr.go.smes.common.error.PlatformException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ErrorResponse> handlePlatformException(PlatformException ex) {
        log.warn("[Q-IM] PlatformException code={} correlationId={}",
                ex.getErrorCode().getCode(), ex.getCorrelationId(), ex);
        return ResponseEntity
                .status(ex.getErrorCode().getHttpStatus())
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getCorrelationId()));
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

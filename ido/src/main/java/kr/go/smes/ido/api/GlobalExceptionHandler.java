package kr.go.smes.ido.api;

import kr.go.smes.common.error.ErrorResponse;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * IdO 글로벌 예외 핸들러
 * 설계서 §6.3 오류 응답 표준 / §17.5 Retry-After 헤더
 *
 * <p>E-OPS-901 외부 사업자 장애 응답 시 Retry-After 헤더 포함 (§17.5 필수).
 * IDO_QIM_UNREACHABLE / QS_PROVIDER_TIMEOUT 도 재시도 안내 포함.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 기본 Retry-After 값 (초) — E-OPS-901 장애 시 재시도 권고 간격 */
    private static final int DEFAULT_RETRY_AFTER_SECONDS = 30;

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ErrorResponse> handlePlatformException(PlatformException ex) {
        log.warn("[IdO] PlatformException code={} correlationId={}",
                ex.getErrorCode().getCode(), ex.getCorrelationId(), ex);

        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(ex.getErrorCode().getHttpStatus());

        // E-OPS-901 외부 사업자 장애: Retry-After 헤더 필수 (설계서 §17.5)
        if (PlatformErrorCode.OPS_EXTERNAL_SYSTEM_ERROR.equals(ex.getErrorCode())) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(DEFAULT_RETRY_AFTER_SECONDS));
        }
        // IDO_QIM_UNREACHABLE: Q-IM 장애 시 재시도 안내
        if (PlatformErrorCode.IDO_QIM_UNREACHABLE.equals(ex.getErrorCode())) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(DEFAULT_RETRY_AFTER_SECONDS));
        }
        // QS_PROVIDER_TIMEOUT: 외부 인증 사업자 타임아웃
        if (PlatformErrorCode.QS_PROVIDER_TIMEOUT.equals(ex.getErrorCode())) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(DEFAULT_RETRY_AFTER_SECONDS));
        }
        // AGENCY_MAINTENANCE: 점검 시간 안내
        if (PlatformErrorCode.AGENCY_MAINTENANCE.equals(ex.getErrorCode())) {
            builder.header(HttpHeaders.RETRY_AFTER, "3600"); // 1시간 후 재시도
        }

        return builder.body(ErrorResponse.of(ex.getErrorCode(), ex.getCorrelationId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("[IdO] 미처리 예외 발생", ex);
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.builder()
                        .code("E-IDO-500")
                        .message("내부 서버 오류가 발생했습니다.")
                        .timestamp(java.time.Instant.now())
                        .build());
    }
}

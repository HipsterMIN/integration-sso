package io.github.hipstermin.idem.hub.api;

import io.github.hipstermin.idem.common.error.ErrorResponse;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * IdO 글로벌 예외 핸들러
 * 설계서 §6.3 오류 응답 표준 / §17.5 Retry-After 헤더
 *
 * <p><b>GAP-API-04 (v1.9.4)</b>: Retry-After 헤더 적용 범위 확대
 * <ul>
 *   <li>429 Too Many Requests (AGENCY_RATE_LIMIT_EXCEEDED, QS_AUTH_LOCKED): 동적 계산</li>
 *   <li>503 Service Unavailable (OPS_EXTERNAL_SYSTEM_ERROR, IDO_QIM_UNREACHABLE,
 *       IDP_CIRCUIT_OPEN, AGENCY_MAINTENANCE): 서비스별 차등 Retry-After</li>
 *   <li>504 Gateway Timeout (QS_PROVIDER_TIMEOUT): 30초 후 재시도</li>
 *   <li>5xx 미처리 예외: 기본 Retry-After 60초 추가</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 기본 Retry-After 값 (초) */
    private static final int DEFAULT_RETRY_AFTER_SECONDS  = 30;

    /** Circuit Breaker OPEN 상태 Retry-After (초) — CB Half-Open 시도 주기보다 길게 */
    private static final int CB_OPEN_RETRY_AFTER_SECONDS  = 60;

    /** 점검 Retry-After (초) = 1시간 */
    private static final int MAINTENANCE_RETRY_AFTER      = 3600;

    /** Rate Limit 기본 Retry-After (초) — 슬라이딩 윈도우 1초 + 여유 */
    private static final int RATE_LIMIT_RETRY_AFTER       = 1;

    /** 5xx 비즈니스 무관 서버 오류 Retry-After (초) */
    private static final int SERVER_ERROR_RETRY_AFTER     = 60;

    /**
     * 재시도 의미 있는 오류 코드 집합 (503 계열)
     * — 이 집합에 해당하면 Retry-After 헤더를 포함한다.
     */
    private static final Set<PlatformErrorCode> RETRYABLE_SERVICE_ERRORS = Set.of(
            PlatformErrorCode.OPS_EXTERNAL_SYSTEM_ERROR,
            PlatformErrorCode.IDO_QIM_UNREACHABLE,
            PlatformErrorCode.IDP_CIRCUIT_OPEN,
            PlatformErrorCode.AGENCY_MAINTENANCE
    );

    /** 429 Rate Limit 오류 코드 집합 */
    private static final Set<PlatformErrorCode> RATE_LIMIT_ERRORS = Set.of(
            PlatformErrorCode.AGENCY_RATE_LIMIT_EXCEEDED,
            PlatformErrorCode.QS_AUTH_LOCKED
    );

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ErrorResponse> handlePlatformException(PlatformException ex) {
        log.warn("[IdO] PlatformException code={} correlationId={}",
                ex.getErrorCode().getCode(), ex.getCorrelationId(), ex);

        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(ex.getErrorCode().getHttpStatus());

        // ── GAP-API-04: Retry-After 헤더 조건별 적용 ────────────────────

        // ① 503 서비스 불가 계열 — 서비스별 차등 Retry-After
        if (RETRYABLE_SERVICE_ERRORS.contains(ex.getErrorCode())) {
            int retryAfter = resolveServiceRetryAfter(ex.getErrorCode());
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
            log.debug("[IdO] Retry-After 헤더 추가: code={} retryAfter={}s",
                    ex.getErrorCode().getCode(), retryAfter);
        }
        // ② 429 Rate Limit — 짧은 Retry-After (슬라이딩 윈도우 주기)
        else if (RATE_LIMIT_ERRORS.contains(ex.getErrorCode())) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(RATE_LIMIT_RETRY_AFTER));
            log.debug("[IdO] Retry-After 헤더 추가 (Rate Limit): code={} retryAfter={}s",
                    ex.getErrorCode().getCode(), RATE_LIMIT_RETRY_AFTER);
        }
        // ③ 504 Gateway Timeout — 외부 사업자 타임아웃
        else if (PlatformErrorCode.QS_PROVIDER_TIMEOUT.equals(ex.getErrorCode())) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(DEFAULT_RETRY_AFTER_SECONDS));
        }

        return builder.body(ErrorResponse.of(ex.getErrorCode(), ex.getCorrelationId()));
    }

    /**
     * Bean Validation 오류 (400 Bad Request)
     * @Valid 실패 시 표준 오류 응답 반환
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String firstError = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("요청 파라미터 오류");
        log.warn("[IdO] 요청 유효성 검증 실패: {}", firstError);
        return ResponseEntity.badRequest()
                .body(ErrorResponse.builder()
                        .code("E-IDO-400")
                        .message(firstError)
                        .timestamp(Instant.now())
                        .build());
    }

    /**
     * ConstraintViolation (@Validated 계층)
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        log.warn("[IdO] ConstraintViolation: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.builder()
                        .code("E-IDO-400")
                        .message(ex.getMessage())
                        .timestamp(Instant.now())
                        .build());
    }

    /**
     * 요청 형식 오류 (400) — 필수 헤더·파라미터 누락, 본문 파싱 실패, 타입 불일치.
     * S8-a: 종전에는 catch-all 이 500 으로 바꿔 클라이언트 오류가 서버 오류로 집계됐다.
     */
    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        log.warn("[IdO] 요청 형식 오류: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.builder()
                        .code("E-IDO-400")
                        .message(ex instanceof HttpMessageNotReadableException ? "요청 본문을 읽을 수 없습니다." : ex.getMessage())
                        .timestamp(Instant.now())
                        .build());
    }

    /**
     * 없는 경로 (404) — S8-a: 종전에는 catch-all 이 500 으로 바꿔 "엔드포인트 없음"과 "서버 오류"를 구분할 수 없었다.
     * 에디션 분리 뒤에는 KR 엔드포인트가 코어에 없는 것이 정상 상태라 404 를 그대로 돌려준다.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception ex) {
        log.debug("[IdO] 없는 경로: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.builder()
                        .code("E-IDO-404")
                        .message("요청한 경로가 없습니다.")
                        .timestamp(Instant.now())
                        .build());
    }

    /**
     * 5xx 미처리 예외 — GAP-API-04: Retry-After 기본값 포함
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("[IdO] 미처리 예외 발생", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(SERVER_ERROR_RETRY_AFTER))
                .body(ErrorResponse.builder()
                        .code("E-IDO-500")
                        .message("내부 서버 오류가 발생했습니다.")
                        .timestamp(Instant.now())
                        .build());
    }

    // ── private ──────────────────────────────────────────────────────────────

    /**
     * 서비스 오류 코드별 Retry-After 초 계산
     *
     * <p>Circuit Breaker OPEN 상태는 Half-Open 시도 주기(기본 60초)보다
     * 크게 설정하여 클라이언트가 CB 자동 복구 전 재시도로 CB를 재개방하지 않도록 한다.
     */
    private int resolveServiceRetryAfter(PlatformErrorCode code) {
        if (PlatformErrorCode.AGENCY_MAINTENANCE.equals(code)) {
            return MAINTENANCE_RETRY_AFTER;     // 점검: 1시간
        }
        if (PlatformErrorCode.IDP_CIRCUIT_OPEN.equals(code)) {
            return CB_OPEN_RETRY_AFTER_SECONDS; // CB OPEN: 60초
        }
        return DEFAULT_RETRY_AFTER_SECONDS;     // 그 외: 30초
    }
}

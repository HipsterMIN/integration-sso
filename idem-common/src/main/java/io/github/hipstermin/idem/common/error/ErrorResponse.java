package io.github.hipstermin.idem.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * 표준 오류 응답 JSON
 * 설계서 16.12절 참조
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private final String code;
    private final String message;
    private final String correlationId;
    private final Instant timestamp;

    public static ErrorResponse of(PlatformException ex) {
        return ErrorResponse.builder()
                .code(ex.getErrorCode().getCode())
                .message(ex.getMessage())
                .correlationId(ex.getCorrelationId())
                .timestamp(Instant.now())
                .build();
    }

    public static ErrorResponse of(PlatformErrorCode errorCode, String correlationId) {
        return ErrorResponse.builder()
                .code(errorCode.getCode())
                .message(errorCode.getDefaultMessage())
                .correlationId(correlationId)
                .timestamp(Instant.now())
                .build();
    }
}

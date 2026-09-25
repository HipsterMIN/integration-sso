package io.github.hipstermin.idem.hub.qim.sp.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import lombok.Builder;
import lombok.Getter;

/**
 * Q-IM SP 수신 API 공통 응답 봉투
 * Q-IM 명세서 v1.52 §3.2 응답 봉투 표준 준수
 *
 * 성공: { "success": true,  "data": {...}, "message": "..." }
 * 실패: { "success": false, "errorCode": "...", "message": "...", "details": {...} }
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QimSpResponse<T> {

    private final boolean success;
    private final T data;
    private final String message;
    private final String errorCode;
    private final Object details;

    // ── 성공 팩토리 ──────────────────────────────────────────────────────────

    public static <T> QimSpResponse<T> ok(T data, String message) {
        return QimSpResponse.<T>builder()
                .success(true)
                .data(data)
                .message(message)
                .build();
    }

    // ── 실패 팩토리 ──────────────────────────────────────────────────────────

    public static <T> QimSpResponse<T> error(String errorCode, String message) {
        return QimSpResponse.<T>builder()
                .success(false)
                .errorCode(errorCode)
                .message(message)
                .build();
    }

    public static <T> QimSpResponse<T> error(String errorCode, String message, Object details) {
        return QimSpResponse.<T>builder()
                .success(false)
                .errorCode(errorCode)
                .message(message)
                .details(details)
                .build();
    }

    // ── 응답 데이터 내부 DTO ──────────────────────────────────────────────────

    /** MEMBER_QUERY 응답 data */
    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QueryData {
        private final boolean exists;
        private final String instMbrId;
    }

    /** MEMBER_REGISTER 응답 data */
    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RegisterData {
        private final String instMbrId;
        private final String registeredAt;

        public static RegisterData of(String instMbrId, Instant registeredAt) {
            return RegisterData.builder()
                    .instMbrId(instMbrId)
                    .registeredAt(formatIso8601(registeredAt))
                    .build();
        }
    }

    /** MEMBER_WITHDRAW 응답 data */
    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WithdrawData {
        private final String withdrawnAt;

        public static WithdrawData of(Instant withdrawnAt) {
            return WithdrawData.builder()
                    .withdrawnAt(formatIso8601(withdrawnAt))
                    .build();
        }
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────────

    private static final DateTimeFormatter ISO8601_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
                    .withZone(ZoneId.systemDefault());   // D3: 컨테이너 TZ(IDEM_TZ) — 코어에 시간대를 박지 않는다

    private static String formatIso8601(Instant instant) {
        if (instant == null) return null;
        return ISO8601_FORMATTER.format(instant);
    }
}

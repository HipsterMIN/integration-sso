package io.github.hipstermin.idem.registry.withdrawal;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * 회원 탈퇴 요청 DTO
 * POST /api/v1/internal/users/{qimUserId}/withdrawal
 *
 * <p><b>유형별 필수 파라미터</b>:
 * <ul>
 *   <li>IMMEDIATE       — type, reason</li>
 *   <li>SCHEDULED       — type, reason, scheduledAt (미래 시각)</li>
 *   <li>AGENCY_REQUESTED— type, reason, requestedBy (기관코드)</li>
 *   <li>ADMIN_FORCED    — type, reason, requestedBy (관리자 ID)</li>
 * </ul>
 */
@Getter
@Builder
@Jacksonized
public class WithdrawalRequest {

    /** 탈퇴 유형 (필수) */
    private final WithdrawalType type;

    /** 탈퇴 사유 (필수, 최대 200자) */
    private final String reason;

    /**
     * SCHEDULED 탈퇴 예약 일시 (UTC)
     * type=SCHEDULED 일 때만 유효. null이면 기본 30일 유예.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private final Instant scheduledAt;

    /**
     * 탈퇴 요청 주체
     * AGENCY_REQUESTED: 기관 코드 (예: "GOV_AGENCY_01")
     * ADMIN_FORCED: 관리자 ID
     * IMMEDIATE/SCHEDULED: null 허용 (사용자 본인)
     */
    private final String requestedBy;

    /** 전체 흐름 추적 ID */
    private final String correlationId;
}

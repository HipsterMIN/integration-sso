package kr.go.smes.qim.withdrawal;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * 회원 탈퇴 처리 결과 DTO
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WithdrawalResponse {

    /** 처리된 Q-IM 사용자 ID */
    private final String qimUserId;

    /** 처리된 탈퇴 유형 */
    private final WithdrawalType type;

    /**
     * 처리 결과 상태
     * WITHDRAWN: 즉시 탈퇴 완료
     * WITHDRAWAL_SCHEDULED: 예약 탈퇴 등록 완료
     * WITHDRAWAL_CANCELLED: 예약 취소 완료
     */
    private final String resultStatus;

    /**
     * SCHEDULED 탈퇴 예약 일시 (type=SCHEDULED 일 때만 포함)
     */
    private final Instant scheduledAt;

    /** 처리 완료 일시 */
    private final Instant processedAt;
}

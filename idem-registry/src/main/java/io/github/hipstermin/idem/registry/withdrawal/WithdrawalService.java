package io.github.hipstermin.idem.registry.withdrawal;

/**
 * 회원 탈퇴 서비스 인터페이스 (P2 §12.2)
 *
 * <p>4종 탈퇴 유형을 단일 {@link #withdraw} 메서드로 처리한다.
 * 내부적으로 {@link WithdrawalType}에 따라 즉시/예약/기관요청/강제 분기를 수행한다.
 *
 * <p>예약 탈퇴({@link WithdrawalType#SCHEDULED}) 취소는
 * {@link #cancelScheduledWithdrawal}로 처리한다.
 */
public interface WithdrawalService {

    /**
     * 회원 탈퇴 처리 (4종 유형 통합 진입점)
     *
     * @param qimUserId 탈퇴 대상 사용자 ID
     * @param request   탈퇴 요청 파라미터 (유형, 사유, 예약일시 등)
     * @return 탈퇴 처리 결과
     * @throws io.github.hipstermin.idem.common.error.PlatformException
     *         IM_USER_NOT_FOUND / IM_WITHDRAWAL_ALREADY / IM_WITHDRAWAL_NOT_ALLOWED
     */
    WithdrawalResponse withdraw(String qimUserId, WithdrawalRequest request);

    /**
     * 예약 탈퇴 취소 (type=SCHEDULED 전용)
     *
     * <p>유예기간 중에만 취소 가능.
     * 이미 WITHDRAWN 처리된 경우 IM_WITHDRAWAL_ALREADY 예외.
     *
     * @param qimUserId     취소 대상 사용자 ID
     * @param correlationId 흐름 추적 ID
     * @return 취소 처리 결과 (resultStatus=ACTIVE — 취소 후 활성 복원)
     */
    WithdrawalResponse cancelScheduledWithdrawal(String qimUserId, String correlationId);

    /**
     * 예약 탈퇴 만료 처리 (스케줄러 호출 전용)
     *
     * <p>scheduledAt 이 지난 WITHDRAWAL_SCHEDULED 상태 사용자를 일괄 탈퇴 처리.
     * UserRegistrationServiceImpl.withdraw() 와 동일한 PII 삭제 로직을 수행한다.
     *
     * @return 처리된 사용자 수
     */
    int processExpiredScheduledWithdrawals();
}

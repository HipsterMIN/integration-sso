package kr.go.smes.qim.api;

import kr.go.smes.qim.withdrawal.WithdrawalRequest;
import kr.go.smes.qim.withdrawal.WithdrawalResponse;
import kr.go.smes.qim.withdrawal.WithdrawalService;
import kr.go.smes.qim.withdrawal.WithdrawalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Q-IM 회원 탈퇴 API 컨트롤러 (P2 §12.2)
 *
 * <p><b>엔드포인트</b>:
 * <pre>
 * POST   /api/v1/internal/users/{qimUserId}/withdrawal         — 탈퇴 처리 (4종)
 * DELETE /api/v1/internal/users/{qimUserId}/withdrawal/schedule — 예약 탈퇴 취소
 * </pre>
 *
 * <p><b>보안</b>: X-Internal-Api-Key 헤더 검증 ({@link kr.go.smes.qim.config.InternalApiKeyInterceptor})
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/users/{qimUserId}/withdrawal")
@RequiredArgsConstructor
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    /**
     * 회원 탈퇴 — 4종 유형 통합 진입점
     *
     * <p><b>요청 예시 (IMMEDIATE)</b>:
     * <pre>{@code
     * POST /api/v1/internal/users/01HXXX.../withdrawal
     * {
     *   "type": "IMMEDIATE",
     *   "reason": "사용자 요청"
     * }
     * }</pre>
     *
     * <p><b>요청 예시 (SCHEDULED)</b>:
     * <pre>{@code
     * {
     *   "type": "SCHEDULED",
     *   "reason": "서비스 이용 종료",
     *   "scheduledAt": "2026-06-12T00:00:00Z"
     * }
     * }</pre>
     *
     * <p><b>요청 예시 (AGENCY_REQUESTED)</b>:
     * <pre>{@code
     * {
     *   "type": "AGENCY_REQUESTED",
     *   "reason": "기관 회원 탈퇴 요청",
     *   "requestedBy": "GOV_AGENCY_01"
     * }
     * }</pre>
     *
     * <p><b>요청 예시 (ADMIN_FORCED)</b>:
     * <pre>{@code
     * {
     *   "type": "ADMIN_FORCED",
     *   "reason": "이용 약관 위반",
     *   "requestedBy": "admin@smes.go.kr"
     * }
     * }</pre>
     *
     * @return 즉시 탈퇴: 200 + resultStatus=WITHDRAWN
     *         예약 탈퇴: 200 + resultStatus=WITHDRAWAL_SCHEDULED + scheduledAt
     */
    @PostMapping
    public ResponseEntity<WithdrawalResponse> withdraw(
            @PathVariable String qimUserId,
            @RequestHeader(value = "X-Internal-Api-Key",  required = false) String apiKey,
            @RequestHeader(value = "X-Correlation-Id",    required = false) String correlationId,
            @RequestBody WithdrawalRequest request) {

        log.info("[WithdrawalCtrl] 탈퇴 요청: qimUserId={} type={} correlationId={}",
                qimUserId, request.getType(), correlationId);

        WithdrawalRequest resolvedRequest = request.getCorrelationId() != null
                ? request
                : WithdrawalRequest.builder()
                        .type(request.getType())
                        .reason(request.getReason())
                        .scheduledAt(request.getScheduledAt())
                        .requestedBy(request.getRequestedBy())
                        .correlationId(correlationId)
                        .build();

        WithdrawalResponse response = withdrawalService.withdraw(qimUserId, resolvedRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * 예약 탈퇴 취소
     *
     * <p>type=SCHEDULED 로 등록된 예약을 유예기간 내에 취소한다.
     * 취소 성공 시 status가 ACTIVE로 복원된다.
     *
     * @return 200 + resultStatus=ACTIVE (취소 후 활성 복원 — resultStatus는 새 상태)
     */
    @DeleteMapping("/schedule")
    public ResponseEntity<WithdrawalResponse> cancelSchedule(
            @PathVariable String qimUserId,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Correlation-Id",   required = false) String correlationId) {

        log.info("[WithdrawalCtrl] 예약 탈퇴 취소 요청: qimUserId={}", qimUserId);
        WithdrawalResponse response =
                withdrawalService.cancelScheduledWithdrawal(qimUserId, correlationId);
        return ResponseEntity.ok(response);
    }
}

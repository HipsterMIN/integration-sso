package io.github.hipstermin.idem.common.domain;

/**
 * 사용자 상태 (Q-IM SoR)
 * 설계서 10.1절 / P2 §12.2 회원 생명주기 참조
 *
 * <p>상태 전이 규칙:
 * <pre>
 *   ACTIVE ──────────────┬─→ SUSPENDED
 *                        ├─→ WITHDRAWAL_SCHEDULED (30일 유예 예약)
 *                        └─→ WITHDRAWN (즉시/강제)
 *   SUSPENDED ───────────┬─→ ACTIVE (정지 해제)
 *                        └─→ WITHDRAWN
 *   WITHDRAWAL_SCHEDULED ┬─→ ACTIVE (예약 취소)
 *                        └─→ WITHDRAWN (유예 만료)
 *   WITHDRAWN ───────────→ (terminal, 재활성 불가)
 * </pre>
 */
public enum UserStatus {
    /** 정상 활성 상태 */
    ACTIVE,
    /** 일시 정지 상태 */
    SUSPENDED,
    /**
     * 탈퇴 예약 상태 (30일 유예기간)
     * 유예 중 취소 가능. 만료 시 자동으로 WITHDRAWN 전환.
     */
    WITHDRAWAL_SCHEDULED,
    /** 탈퇴 완료 (terminal) — PII 삭제됨 */
    WITHDRAWN
}

package io.github.hipstermin.idem.registry.conversion;

/**
 * 통합계정 전환 세션 상태 (P2 §12.1)
 *
 * <p><b>상태 전이 규칙</b>:
 * <pre>
 * INITIATED ──────────────→ MEMBERS_FETCHED
 *                           └──→ ACCOUNT_SELECTED
 *                                └──→ LINKING
 *                                     └──→ COMPLETED
 * (모든 상태) ──→ CANCELLED  (사용자 취소)
 * (모든 상태) ──→ EXPIRED    (TTL 만료, 스케줄러 처리)
 * </pre>
 */
public enum ConversionSessionState {
    /** 전환 시작 — CI 확보, Q-IM UUID 발급 완료 */
    INITIATED,
    /** 유관 시스템 회원 목록 조회 완료 */
    MEMBERS_FETCHED,
    /** 사용자가 연결 대상 계정 선택 완료 */
    ACCOUNT_SELECTED,
    /** 계정 연결 진행 중 */
    LINKING,
    /** 전환 완료 (terminal) */
    COMPLETED,
    /** 사용자 취소 (terminal) */
    CANCELLED,
    /** TTL 만료 — 미처리 세션 자동 만료 (terminal) */
    EXPIRED;

    /**
     * 상태 전이 가능 여부 검증
     *
     * @param next 전이 대상 상태
     * @return 허용된 전이이면 true
     */
    public boolean canTransitionTo(ConversionSessionState next) {
        return switch (this) {
            case INITIATED       -> next == MEMBERS_FETCHED  || next == CANCELLED || next == EXPIRED;
            case MEMBERS_FETCHED -> next == ACCOUNT_SELECTED || next == CANCELLED || next == EXPIRED;
            case ACCOUNT_SELECTED-> next == LINKING          || next == CANCELLED || next == EXPIRED;
            case LINKING         -> next == COMPLETED        || next == CANCELLED || next == EXPIRED;
            // terminal states — no further transition
            case COMPLETED, CANCELLED, EXPIRED -> false;
        };
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == EXPIRED;
    }
}

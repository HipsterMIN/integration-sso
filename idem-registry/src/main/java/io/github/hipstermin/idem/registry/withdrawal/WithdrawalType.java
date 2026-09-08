package io.github.hipstermin.idem.registry.withdrawal;

/**
 * 회원 탈퇴 유형 (설계서 P2 §12.2 회원 생명주기)
 *
 * <p>4종 탈퇴 유형에 따라 PII 처리 방식·예약 일정·이벤트 타입이 달라진다.
 *
 * <table border="1">
 *   <tr><th>유형</th><th>트리거</th><th>PII 삭제</th><th>예약</th></tr>
 *   <tr><td>IMMEDIATE</td><td>사용자 즉시 요청</td><td>즉시</td><td>없음</td></tr>
 *   <tr><td>SCHEDULED</td><td>사용자 예약 요청 (30일 유예)</td><td>유예 만료 후</td><td>있음</td></tr>
 *   <tr><td>AGENCY_REQUESTED</td><td>기관 연동 탈퇴 (API)</td><td>즉시</td><td>없음</td></tr>
 *   <tr><td>ADMIN_FORCED</td><td>관리자 강제 탈퇴</td><td>즉시</td><td>없음</td></tr>
 * </table>
 */
public enum WithdrawalType {

    /**
     * 즉시 탈퇴 — 사용자 본인이 즉시 탈퇴 요청.
     * PII 즉시 NULL 처리, 탈퇴 이벤트 발행.
     */
    IMMEDIATE,

    /**
     * 예약 탈퇴 — 사용자가 30일 유예기간 후 탈퇴를 예약.
     * {@code withdrawal_scheduled_at} 설정 → 스케줄러가 만료 시 {@link #IMMEDIATE}와 동일하게 처리.
     * 유예기간 중 취소 가능.
     */
    SCHEDULED,

    /**
     * 기관 요청 탈퇴 — 연계 기관이 API를 통해 소속 회원 탈퇴를 요청.
     * 기관 코드({@code agencyCode})가 reason에 포함되어 감사 추적 가능.
     * PII 즉시 NULL 처리.
     */
    AGENCY_REQUESTED,

    /**
     * 관리자 강제 탈퇴 — 운영팀이 정책 위반 등으로 강제 탈퇴 처리.
     * 감사 로그 강화 필수.
     * PII 즉시 NULL 처리.
     */
    ADMIN_FORCED
}

package com.onepass.ido.handoff;

import com.onepass.common.domain.HandoffPayload;
import com.onepass.common.domain.HandoffTicket;

/**
 * IdO Handoff 서비스 인터페이스
 * 설계서 16장 참조 — Issue / Verify / Revoke
 */
public interface HandoffService {

    /**
     * Handoff Ticket 발급 (설계서 16.1 / 11.4절)
     * 정책 적용 순서:
     *   1. 기관코드 등록 여부 검증
     *   2. Q-IM 사용자 상태 확인
     *   3. 기관 최소 인증수준 충족 검증
     *   4. 기관 허용 속성 필터링
     *   5. 차단 룰 검증
     *   6. Subject Identifier Projection 생성
     *   7. Ticket 발급
     */
    HandoffTicket issue(HandoffIssueCommand command);

    /**
     * Handoff Ticket 검증 및 소비 (1회성)
     * 설계서 16.6 / 16.10절 — consumeOnce 보장
     */
    HandoffPayload verify(String ticketId, String agencyCode, String correlationId);

    /**
     * Ticket 강제 취소 (설계서 16.4.3절)
     * 사유: compromise / qim_suspend / policy_rollback / incident_containment
     */
    void revoke(String ticketId, String revokeReason, String correlationId);
}

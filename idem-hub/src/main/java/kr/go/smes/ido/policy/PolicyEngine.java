package kr.go.smes.ido.policy;

import kr.go.smes.common.domain.AuthResult;
import kr.go.smes.common.domain.HandoffPayload;
import kr.go.smes.common.domain.HandoffTicket;
import kr.go.smes.common.domain.UserStatus;
import kr.go.smes.ido.domain.AgencyMeta;

/**
 * IdO 정책 엔진 인터페이스
 * 설계서 11.4 / 11.5절 참조
 *
 * 정책 적용 순서 (설계서 11.4절):
 *   1. 기관코드 등록 여부 검증
 *   2. Q-IM 사용자 상태 확인 (캐시 ≤5분 TTL)
 *   3. 기관 최소 인증수준 충족 검증
 *   4. 기관 허용 속성 필터링
 *   5. 차단 룰(블랙리스트, 점검시간) 검증
 *   6. Subject Identifier Projection 생성
 *   7. Ticket 발급 또는 거부 결정
 */
public interface PolicyEngine {

    /** Q-IM 사용자 상태 조회 (캐시 → Q-IM fallback) */
    UserStatus resolveUserStatus(String qimUserId, String correlationId);

    /** 인증 수준 충족 여부 */
    boolean meetsMinAuthLevel(AuthResult.AuthLevel actual, AuthResult.AuthLevel required);

    /** 기관 점검 시간 여부 */
    boolean isUnderMaintenance(AgencyMeta agency);

    /**
     * Handoff Payload 생성 (Subject Identifier Projection + 허용 속성 필터링)
     * 설계서 5.3 / 16.5절 참조
     * - 정본은 Q-IM, 기관이 보는 식별자는 Projection
     */
    HandoffPayload buildHandoffPayload(HandoffTicket ticket, String correlationId);
}

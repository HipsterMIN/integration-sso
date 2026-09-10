package io.github.hipstermin.idem.hub.policy;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.domain.HandoffTicket;
import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.hub.domain.AgencyMeta;
import io.github.hipstermin.idem.hub.policy.rule.PolicyContext;
import io.github.hipstermin.idem.hub.policy.rule.PolicyEvaluation;

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

    /**
     * 규칙 집합 평가 (S3). 내장 규칙(MAINTENANCE → MIN_AUTH_LEVEL → ALLOWED_PROVIDERS → USER_STATUS)은 항상,
     * 프로파일 {@code policy.rules[]} 의 커스텀 규칙은 지정 순으로. {@code ctx.profile()} 이 null 이면 {@code tenantCode} 로 읽는다.
     *
     * @param stopAtFirstDenial true 면 첫 DENY 에서 멈춘다(발급 경로). false 면 전부 평가(시뮬레이션)
     */
    PolicyEvaluation evaluate(PolicyContext ctx, boolean stopAtFirstDenial);
}

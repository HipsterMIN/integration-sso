package io.github.hipstermin.idem.tenant.session;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * 기관 로컬 세션 객체 (기관 SoR)
 * 설계서 14.3절 참조
 *
 * 핵심 원칙:
 *   - 세션 오너십: 기관 서비스 단독 보유 (외부 강제 종료 불가)
 *   - AGSID: ≥128bit 엔트로피, Secure/HttpOnly/SameSite=Strict
 *   - Session Fixation 방지: Verify 성공 직후 기존 AGSID 폐기 후 재발급
 *   - AGSID는 URL 파라미터로 전달 금지 (GET 노출 차단)
 */
@Getter
@Builder
public class AgencyLocalSession {

    /** 기관 세션 ID (충분 엔트로피 ≥128bit) */
    private final String agencySessionId;

    /** 기관 내부 사용자 ID */
    private final String agencyUserId;

    /** Q-IM 기반 기관향 식별자 (Projection — 정본 아님) */
    private final String agencySubjectId;

    /** Q-IM 사용자 ID (정본 참조용) */
    private final String qimUserId;

    /** 인증 수준 */
    private final String authLevel;

    /** 연계된 Handoff Ticket ID */
    private final String ticketId;

    /** 추적 키 */
    private final String correlationId;

    private final Instant createdAt;
    private final Instant lastActivityAt;
    private final Instant absoluteExpiresAt;

    public boolean isAbsoluteExpired() {
        return Instant.now().isAfter(absoluteExpiresAt);
    }
}

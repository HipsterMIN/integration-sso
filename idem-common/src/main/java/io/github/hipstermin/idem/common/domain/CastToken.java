package io.github.hipstermin.idem.common.domain;

import java.time.Instant;
import java.util.List;

/**
 * Cross-Agency SSO Token (CAST) — 불변 도메인 객체
 *
 * <p>설계서 Sprint-13 §4.1 — Cross-Agency SSO 흐름에서 기관 A의 인증 세션을
 * 기관 B가 재인증 없이 수락하도록 하는 1회성(One-Time-Use) 단기 토큰.
 *
 * <h3>토큰 생명주기</h3>
 * <pre>
 *   기관 A 로그인 완료
 *     → 사용자가 기관 B URL 접속
 *     → CrossAgencySsoController.issue() 호출
 *         → CastTokenService.issue(feSessionId, targetAgencyCode)
 *             → CAST 토큰 생성 (EdDSA JWT, TTL 5분)
 *             → Redis SET NX ← jti (1회 소비 보장)
 *             → cast_token_audit INSERT
 *     → 기관 B 리디렉션 URL에 idem_sso=&lt;CAST JWT&gt; 첨부
 *     → 기관 B SDK가 POST /api/v1/agency/cast/verify 호출
 *         → CastTokenService.verify(castJwt, targetAgencyCode)
 *             → Redis SET NX 소비 여부 확인 (이미 소비 시 409)
 *             → JWT 서명 · 만료 · agency mismatch 검증
 *             → sso_session_link INSERT
 *             → HandoffTicket 즉시 발급 (qimUserId 기반)
 * </pre>
 *
 * <h3>보안 특성</h3>
 * <ul>
 *   <li><b>EdDSA (Ed25519)</b>: 고속 서명, 256-bit 보안 수준</li>
 *   <li><b>jti (JWT ID)</b>: UUID v7 — Redis SET NX로 1회 소비 강제</li>
 *   <li><b>TTL 5분</b>: 긴 토큰 유효시간으로 인한 재사용 위험 최소화</li>
 *   <li><b>targetAgency 검증</b>: 기관 B가 아닌 기관 C에서 사용 불가</li>
 * </ul>
 *
 * @param jti           JWT ID (UUID v7) — Redis 1회 소비 키
 * @param qimUserId     OnePass 사용자 고유 ID
 * @param sourceAgency  발행 기관 코드 (기관 A)
 * @param targetAgency  대상 기관 코드 (기관 B) — verify 시 일치 검증
 * @param authLevel     인증 수준 — 정규 어휘 L1/L2/L3 (S3). 수신 시 LOW/MEDIUM/HIGH 도 호환 해석
 * @param issuedAt      발행 시각
 * @param expiresAt     만료 시각 (issuedAt + 5분)
 * @param token         서명된 JWT 문자열 (Base64url 인코딩)
 * @param roles         대상 기관에서의 사용자 역할 코드 목록 (연합 인가 — q-authz에서 해석).
 *                      플랫폼은 굵은 RBAC 역할만 배송하고, 세밀한 권한 해석은 기관이 수행한다.
 *                      미부여(L0)·인가 서비스 장애 시 빈 리스트.
 */
public record CastToken(
        String       jti,
        String       qimUserId,
        String       sourceAgency,
        String       targetAgency,
        String       authLevel,
        Instant      issuedAt,
        Instant      expiresAt,
        String       token,
        List<String> roles
) {
    /** JWT 헤더 typ 값 */
    public static final String TOKEN_TYPE = "CAST+JWT";

    /** JWT 클레임 키 — 대상 기관 */
    public static final String CLAIM_TARGET_AGENCY = "tgt_agency";

    /** JWT 클레임 키 — 발행 기관 */
    public static final String CLAIM_SOURCE_AGENCY = "src_agency";

    /** JWT 클레임 키 — 인증 수준 */
    public static final String CLAIM_AUTH_LEVEL = "auth_level";

    /** JWT 클레임 키 — 연합 인가 역할 목록 (대상 기관 스코프) */
    public static final String CLAIM_ROLES = "roles";

    /** Redis 1회 소비 키 접두사 */
    public static final String REDIS_CONSUMED_PREFIX = "cast:consumed:";

    /** CAST 토큰 TTL (초) */
    public static final long TTL_SECONDS = 300L; // 5분

    /**
     * 토큰 만료 여부 확인
     *
     * @return 현재 시각이 expiresAt 이후이면 true
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}

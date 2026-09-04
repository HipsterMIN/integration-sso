package kr.go.smes.ido.sso;

import kr.go.smes.common.domain.CastToken;

/**
 * Cross-Agency SSO Token (CAST) 서비스 인터페이스
 *
 * <p>Sprint 13 — 기관 간 SSO 완성의 핵심 컴포넌트.
 * 기관 A에서 로그인한 사용자가 기관 B에 재인증 없이 접속할 수 있도록
 * 1회성 단기 JWT(CAST Token)를 발급하고 검증한다.
 *
 * @see CastTokenServiceImpl
 * @see CastToken
 */
public interface CastTokenService {

    /**
     * CAST 토큰 발급
     *
     * <p>FE 세션에서 사용자 정보를 추출하여 대상 기관(targetAgencyCode)에 대한
     * EdDSA 서명 JWT를 생성한다. 발급된 JTI는 Redis에 SET NX로 등록되어 1회 소비를 보장한다.
     *
     * @param feSessionId      FE 세션 ID (기관 A 로그인 세션) — 없으면 SSO_CAST_SESSION_NOT_FOUND
     * @param targetAgencyCode 대상 기관 코드 (기관 B) — 미등록 시 AGENCY_NOT_REGISTERED
     * @param correlationId    흐름 추적 키
     * @return 발급된 CAST 토큰 (JWT 포함)
     * @throws kr.go.smes.common.error.PlatformException SSO_CAST_SESSION_NOT_FOUND, AGENCY_NOT_REGISTERED
     */
    CastToken issue(String feSessionId, String targetAgencyCode, String correlationId);

    /**
     * CAST 토큰 검증 및 소비
     *
     * <p>기관 B SDK가 호출하는 검증 엔드포인트의 핵심 로직.
     * 검증 순서:
     * <ol>
     *   <li>JWT 서명 검증 (Ed25519 공개키)</li>
     *   <li>만료 시각 검증 (exp 클레임)</li>
     *   <li>대상 기관 일치 검증 (tgt_agency == agencyCode)</li>
     *   <li>Redis SET NX 1회 소비 원자 연산</li>
     *   <li>cast_token_audit 상태 업데이트 (CONSUMED)</li>
     *   <li>sso_session_link 이력 기록</li>
     * </ol>
     *
     * @param castJwt           CAST JWT 토큰 문자열
     * @param targetAgencyCode  검증 요청 기관 코드 (기관 B)
     * @param consumerIp        소비 요청 IP (감사용)
     * @param correlationId     흐름 추적 키
     * @return 검증된 CAST 토큰 도메인 객체
     * @throws kr.go.smes.common.error.PlatformException
     *   SSO_CAST_SIGNATURE_INVALID, SSO_CAST_EXPIRED,
     *   SSO_CAST_AGENCY_MISMATCH, SSO_CAST_CONSUMED
     */
    CastToken verify(String castJwt, String targetAgencyCode, String consumerIp, String correlationId);
}

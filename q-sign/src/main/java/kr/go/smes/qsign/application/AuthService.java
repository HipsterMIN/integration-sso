package kr.go.smes.qsign.application;

import kr.go.smes.common.domain.AuthResult;
import kr.go.smes.common.domain.IdOAuthInput;

/**
 * Q-Sign 핵심 인증 서비스 인터페이스
 * 설계서 9장 참조
 */
public interface AuthService {

    /**
     * 표준 OIDC 인증 결과 발급
     * Q-Sign이 외부 OIDC Provider와 직접 통신하여 AuthResult 발급
     */
    AuthResult issueFromOidc(String correlationId, String providerCode,
                              String idToken, String requestedLevel);

    /**
     * 비OIDC/반표준 인증 정규화 입력으로 AuthResult 발급
     * IdO 브로커로부터 IdOAuthInput 수신 후 Q-Sign이 최종 결과 발급
     * (설계서 9.5 / 11.7절)
     */
    AuthResult issueFromIdOAuthInput(IdOAuthInput input);

    /**
     * AuthResult 조회 (authResultId 기준)
     */
    AuthResult findById(String authResultId, String correlationId);

    /**
     * 잠금 상태 확인
     */
    boolean isLocked(String identifierHash, String providerCode);
}

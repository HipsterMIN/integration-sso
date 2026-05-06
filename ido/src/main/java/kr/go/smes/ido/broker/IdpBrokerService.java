package kr.go.smes.ido.broker;

import kr.go.smes.common.domain.IdOAuthInput;

/**
 * 비OIDC / 반표준 인증 정규화 브로커 인터페이스
 * 설계서 11.6절 참조
 *
 * 역할:
 *   - 외부 IdP 호출·응답 중계·정규화·보안 검증
 *   - IdOAuthInput 생성 후 Q-Sign 전달 (인증 결과 자체는 생성하지 않음)
 *   - Circuit Breaker 보호 (설계서 11.6.5절)
 *
 * 대상 IdP: PASS(통신3사) / 토스 / KB / 신한 / 페이코 / 금융인증서
 *           공동인증서 / GPKI / 정부24 / 디지털원패스 / 삼성패스
 */
public interface IdpBrokerService {

    /**
     * 외부 IdP 인증 시작 (redirect URL 또는 요청 토큰 반환)
     */
    IdpBrokerResult initiateAuth(String providerCode, String correlationId, String callbackUrl);

    /**
     * 외부 IdP 응답 정규화 → IdOAuthInput 생성
     */
    IdOAuthInput normalizeResponse(String providerCode, String correlationId,
                                   String providerTxId, Object rawResponse);
}

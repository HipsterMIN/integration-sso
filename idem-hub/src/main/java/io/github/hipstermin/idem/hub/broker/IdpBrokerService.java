package io.github.hipstermin.idem.hub.broker;

import io.github.hipstermin.idem.common.domain.IdOAuthInput;

/**
 * 비OIDC / 반표준 인증 정규화 브로커 인터페이스
 * 설계서 11.6절 참조
 *
 * 역할 (Option 3 확정 — IdO 직접 처리):
 *   - 외부 IdP 호출·응답 중계·정규화·보안 검증
 *   - normalizeResponse() 내부에서 NonOidcAuthService.processAuth() 직접 호출
 *     → ido.auth_result 생성 + qsign.auth.events Kafka 발행 (Strategy B)
 *   - Q-Sign으로 전달하지 않음 (이전 "Q-Sign 전달" 설명 폐기)
 *   - Circuit Breaker 보호: keycloak-client (설계서 §11.6.5)
 *
 * 구현체: {@link io.github.hipstermin.idem.hub.broker.nonoidc.NonOidcBrokerAdapter}
 * 진입점: {@link io.github.hipstermin.idem.hub.broker.nonoidc.NonOidcBrokerController}
 *
 * 대상 IdP: PASS(통신3사) / 금융인증서 / 공동인증서 / GPKI / 정부24 / 디지털원패스
 */
public interface IdpBrokerService {

    /**
     * 외부 IdP 인증 시작 (redirect URL 또는 요청 토큰 반환)
     *
     * @param providerCode  인증 수단 코드 (PASS / FINANCIAL_CERT / GPKI / JOINT_CERT)
     * @param correlationId 흐름 추적 ID
     * @param callbackUrl   인증 완료 후 사업자가 호출할 ido 콜백 URL
     * @return {@link IdpBrokerResult} — redirect URL·providerTxId·status 포함
     */
    IdpBrokerResult initiateAuth(String providerCode, String correlationId, String callbackUrl);

    /**
     * 외부 IdP 응답 정규화 → {@link IdOAuthInput} 생성
     *
     * <p>구현체({@link io.github.hipstermin.idem.hub.broker.nonoidc.NonOidcBrokerAdapter})에서
     * {@code NonOidcAuthService.processAuth()}를 직접 호출하여
     * {@code ido.auth_result} 저장 + Kafka 발행까지 완료한다.
     *
     * @param providerCode  인증 수단 코드
     * @param correlationId 흐름 추적 ID
     * @param providerTxId  사업자 트랜잭션 ID
     * @param rawResponse   사업자 원본 응답 (Map 또는 사업자별 DTO)
     * @return 정규화된 {@link IdOAuthInput} (internalSignature 필드에 authResultId 포함)
     */
    IdOAuthInput normalizeResponse(String providerCode, String correlationId,
                                   String providerTxId, Object rawResponse);
}

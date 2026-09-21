package io.github.hipstermin.idem.hub.broker.nonoidc;

import java.util.Map;

/**
 * 비OIDC 사업자(PASS·금융인증서·GPKI·공동인증서 등) 콜백 응답의 서명·MAC 검증 SPI (D2 fail-secure).
 *
 * <p>종전 {@code NonOidcBrokerAdapter} 는 응답 검증을 "PoC: 항상 통과" 로 두어 위조 콜백이 인증 우회로 이어질 수 있었다.
 * 이제 사업자 코드를 지원하는 구현체가 없으면 그 사업자는 <b>설정되지 않은 것</b>으로 보고 인증 시작·콜백 모두 503 으로 거부한다.
 * 실제 사업자 연동은 이 인터페이스 구현(플러그인)으로 제공한다.
 */
public interface NonOidcProviderVerifier {

    /** 이 검증기가 다루는 사업자 코드인가 (대소문자 무시). */
    boolean supports(String providerCode);

    /**
     * 사업자 응답의 서명·MAC·타임스탬프를 검증한다.
     *
     * @return {@code true} 이면 정상, {@code false} 이면 위조·변조 의심(거부)
     * @throws RuntimeException 검증 자체가 불가능한 경우(키 없음 등) — 거부로 처리된다
     */
    boolean verify(Map<String, Object> response, String providerCode, String correlationId);
}

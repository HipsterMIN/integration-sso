package io.github.hipstermin.idem.common.spi.broker;

/**
 * 직접 브로커 어댑터 SPI — 표준 OIDC 가 아닌 인증수단(설치형 벤더 SDK 등)의 Authorization URL 을 플러그인이 만든다.
 *
 * <p>코어({@code idem-hub} BrokerService)는 provider 코드를 보고 다음 순서로 어댑터를 고른다.
 * <ol>
 *   <li>{@code provider_config.broker_mode} 값이 {@link #id()} 와 같은 어댑터</li>
 *   <li>DB 설정이 없으면 {@link #supports(String)} 가 true 인 첫 어댑터</li>
 *   <li>없으면 코어의 비OIDC 직접 브로커링 경로</li>
 * </ol>
 *
 * <p>구현체는 벤더 플러그인 모듈({@code plugins/idem-plugin-*})에 두고 Spring 빈으로 등록한다. 코어는 벤더 클래스를 참조하지 않는다.
 */
public interface DirectBrokerAdapter {

    /** 어댑터 식별자 — {@code provider_config.broker_mode} 컬럼 값과 맞춘다 (예: {@code anyid}). 소문자. */
    String id();

    /**
     * DB 설정이 없을 때 쓰는 코드 휴리스틱.
     *
     * @param providerCode 대문자·언더스코어로 정규화된 provider 코드 (예: {@code MOBILE_ID})
     */
    boolean supports(String providerCode);

    /**
     * 브라우저가 리다이렉트할 인증 시작 URL.
     *
     * @param provider       FE 가 준 provider 코드 (정규화 전)
     * @param correlationId  흐름 추적 ID
     * @param returnUrl      인증 완료 후 기관 귀환 URL (null 허용)
     * @param requestedLevel 요청 인증 수준 (L1/L2/L3)
     */
    String buildAuthorizationUrl(String provider, String correlationId, String returnUrl, String requestedLevel);
}

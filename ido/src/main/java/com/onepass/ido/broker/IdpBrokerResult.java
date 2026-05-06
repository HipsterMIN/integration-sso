package com.onepass.ido.broker;

import lombok.Builder;
import lombok.Getter;

/**
 * 외부 IdP 인증 시작 결과
 * 설계서 11.6.3절 참조
 */
@Getter
@Builder
public class IdpBrokerResult {

    private final String providerCode;
    private final String providerTxId;

    /** 사용자를 리다이렉트할 외부 IdP URL */
    private final String redirectUrl;

    /** 임시 상태 코드 (state param) */
    private final String state;

    /** 브로커 처리 결과 */
    private final BrokerStatus status;

    public enum BrokerStatus {
        REDIRECT_REQUIRED,
        DIRECT_CALL_REQUIRED,
        CIRCUIT_OPEN
    }
}

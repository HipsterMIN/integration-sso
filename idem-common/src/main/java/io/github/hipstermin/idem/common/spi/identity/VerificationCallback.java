package io.github.hipstermin.idem.common.spi.identity;

import java.util.Collections;
import java.util.Map;

/**
 * 벤더 콜백(인증 완료) 입력.
 *
 * @param providerCode  제공자 코드
 * @param txId          {@link VerificationStart#txId()}
 * @param correlationId 추적 ID (null 허용)
 * @param params        벤더가 돌려준 파라미터 원문 (예: NICE 의 web_transaction_id) — null 이면 빈 맵
 */
public record VerificationCallback(String providerCode, String txId, String correlationId, Map<String, String> params) {
    public VerificationCallback {
        params = params == null ? Collections.emptyMap() : Map.copyOf(params);
    }

    public String param(String key) {
        return params.get(key);
    }
}

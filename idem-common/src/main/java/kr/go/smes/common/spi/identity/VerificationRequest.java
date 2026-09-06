package kr.go.smes.common.spi.identity;

import java.util.Collections;
import java.util.Map;

/**
 * 인증 시작 요청.
 *
 * @param correlationId 추적 ID (없으면 null 허용)
 * @param returnUrl     벤더 인증 완료 후 돌아올 URL (제공자에 따라 무시될 수 있음)
 * @param params        제공자별 추가 파라미터 (예: mock 의 name/phone) — null 이면 빈 맵
 */
public record VerificationRequest(String correlationId, String returnUrl, Map<String, String> params) {
    public VerificationRequest {
        params = params == null ? Collections.emptyMap() : Map.copyOf(params);
    }

    public String param(String key) {
        return params.get(key);
    }
}

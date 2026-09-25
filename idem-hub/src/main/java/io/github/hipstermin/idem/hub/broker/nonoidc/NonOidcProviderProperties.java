package io.github.hipstermin.idem.hub.broker.nonoidc;

import io.github.hipstermin.idem.common.domain.AuthResult;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * D3: 비OIDC 직접 브로커 사업자 목록 — 설정 {@code ido.broker.nonoidc.providers.{CODE}}.
 *
 * <p>종전에는 {@code NonOidcBrokerAdapter} 가 PASS·FINANCIAL_CERT·GPKI·JOINT_CERT 를 코드에 박고 있었다. 코어는 사업자를 모르고,
 * 어떤 사업자가 있는지·어디로 보내는지·인증수준이 무엇인지는 설치(에디션 후처리기 또는 환경)가 정한다. 코어 기본값은 빈 목록이다.
 *
 * <pre>
 * ido.broker.nonoidc.providers:
 *   PASS:
 *     initiate-url: https://pass.example.com/auth?callback={callbackUrl}&amp;cid={correlationId}
 *     auth-level: L2
 *     tx-prefix: PASS
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ido.broker.nonoidc")
public class NonOidcProviderProperties {

    private Map<String, Provider> providers = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class Provider {
        /** 인증 시작 리다이렉트 URL 템플릿 — {@code {callbackUrl}}(URL 인코딩됨)·{@code {correlationId}} 자리표시자 */
        private String initiateUrl;
        /** 이 사업자가 보증하는 인증수준 (기본 L1) */
        private AuthResult.AuthLevel authLevel = AuthResult.AuthLevel.L1;
        /** providerTxId 접두 (기본 = 사업자 코드) */
        private String txPrefix;
    }

    /** 대소문자 무관 조회 */
    public Optional<Provider> find(String providerCode) {
        if (providerCode == null || providers == null) return Optional.empty();
        String key = providerCode.trim().toUpperCase(Locale.ROOT);
        return providers.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getKey().trim().toUpperCase(Locale.ROOT).equals(key))
                .map(Map.Entry::getValue)
                .findFirst();
    }
}

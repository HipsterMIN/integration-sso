package io.github.hipstermin.idem.hub.kr.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 본인인증 외부 시스템 연동 설정 (ido.auth.*)
 *
 * <p>{@code application.yml}의 {@code ido.auth} 네임스페이스 하위 설정을 바인딩.
 * {@code IdoApplication} 또는 {@code AuthWebClientConfig}에서
 * {@code @EnableConfigurationProperties(AuthProperties.class)}로 활성화.
 *
 * <p><b>설정 예시 (application.yml):</b>
 * <pre>
 * ido:
 *   auth:
 *     nice:
 *       client-id: ${NICE_CLIENT_ID:}
 *       client-secret: ${NICE_CLIENT_SECRET:}
 *       return-url: ${NICE_RETURN_URL:http://localhost:3000/otp/auth-result}
 *       timeout-seconds: 10
 *     oacx:
 *       provider-key-path: ${OACX_PROVIDER_KEY_PATH:}
 *       debug-mode: false
 *     integration:
 *       base-url: ${INTEGRATION_AUTH_BASE_URL:}
 *       timeout-seconds: 10
 * </pre>
 *
 * <p><b>환경변수 주입 (운영):</b>
 * <ul>
 *   <li>{@code NICE_CLIENT_ID} — NICE 클라이언트 ID (NICE 계약 발급)</li>
 *   <li>{@code NICE_CLIENT_SECRET} — NICE 클라이언트 시크릿 (NICE 계약 발급)</li>
 *   <li>{@code NICE_RETURN_URL} — 인증 완료 후 리다이렉트 URL</li>
 *   <li>{@code OACX_PROVIDER_KEY_PATH} — OACX SDK provider key JSON 파일 경로</li>
 *   <li>{@code INTEGRATION_AUTH_BASE_URL} — 통합인증 서버 기본 URL</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "ido.auth")
public record AuthProperties(
        Integration integration
) {
    public AuthProperties {
        if (integration == null) integration = new Integration(null, 0);
    }

    /** NICE·OACX 설정은 S5a 부터 idem-plugin-nice-oacx 가 같은 키(ido.auth.nice.* / ido.auth.oacx.*)로 바인딩한다. */
    public record Integration(
            String baseUrl,
            int timeoutSeconds
    ) {
        public Integration {
            if (baseUrl == null) baseUrl = "";
            if (timeoutSeconds <= 0) timeoutSeconds = 10;
        }
    }
}

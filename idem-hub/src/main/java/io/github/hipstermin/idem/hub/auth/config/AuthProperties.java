package io.github.hipstermin.idem.hub.auth.config;

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
        Nice nice,
        Oacx oacx,
        Integration integration
) {

    /**
     * NICE 휴대폰 본인인증 연동 설정
     *
     * <p>NICE IDO 인증 서버({@code https://auth.niceid.co.kr}) 접속 정보.
     * NICE 계약을 통해 발급받은 clientId/clientSecret 필수.
     *
     * <p><b>민감 정보 주의:</b> clientSecret은 로그에 절대 출력하지 말 것.
     */
    public record Nice(
            /**
             * NICE 클라이언트 ID
             *
             * <p>NICE 계약 후 발급받은 클라이언트 식별자.
             * Basic Auth 형식({@code clientId:clientSecret})으로 인코딩하여
             * Access Token 발급 API에 전달.
             */
            String clientId,

            /**
             * NICE 클라이언트 시크릿
             *
             * <p><b>보안 주의:</b> 이 값은 절대 로그에 출력하지 말 것.
             * 환경변수 {@code NICE_CLIENT_SECRET}로 주입.
             */
            String clientSecret,

            /**
             * 인증 완료 후 리다이렉트 URL
             *
             * <p>NICE 표준창이 인증 완료 후 브라우저를 리다이렉트하는 URL.
             * FE의 팝업 수신 페이지 URL이어야 함.
             * 기본값: {@code http://localhost:3000/otp/auth-result}
             */
            String returnUrl,

            /**
             * NICE API 요청 타임아웃 (초)
             *
             * <p>기본값: 10초. NICE 서버 응답이 늦을 경우 조정 필요.
             */
            int timeoutSeconds
    ) {
        public Nice {
            if (clientId == null) clientId = "";
            if (clientSecret == null) clientSecret = "";
            if (returnUrl == null) returnUrl = "";
            if (timeoutSeconds <= 0) timeoutSeconds = 10;
        }
    }

    /**
     * OACX 전자서명 중계모듈 설정
     *
     * <p>OACX SDK를 통해 간편서명 기능을 사용하기 위한 설정.
     */
    public record Oacx(
            /**
             * OACX SDK provider key JSON 파일 경로
             *
             * <p>OACX 운영사에서 발급한 provider 키 파일의 절대 경로.
             * OACX SDK의 {@code OacxUtil.loadJSONInfo(path)}에 전달.
             * 예: {@code /app/config/oacx-provider-key.json}
             */
            String providerKeyPath,

            /**
             * OACX SDK 디버그 모드 활성화 여부
             *
             * <p>true: OACX SDK 내부 디버그 로그 출력 (개발/검증 환경에서만 사용).
             * 운영 환경에서는 반드시 false로 설정.
             */
            boolean debugMode
    ) {
        public Oacx {
            if (providerKeyPath == null || providerKeyPath.isBlank()) {
                providerKeyPath = "";
            }
        }
    }

    /**
     * 통합인증 서버 연동 설정
     *
     * <p>기업 간편인증 콜백({@code POST /api/v1/auth/callback}) 처리 시
     * 통합인증 서버에 auth-check를 요청하는 설정.
     */
    public record Integration(
            /**
             * 통합인증 서버 기본 URL
             *
             * <p>예: {@code https://intg-auth.smes.go.kr}
             * 환경변수 {@code INTEGRATION_AUTH_BASE_URL}로 주입.
             */
            String baseUrl,

            /**
             * 통합인증 서버 요청 타임아웃 (초)
             *
             * <p>기본값: 10초. 기업인증 처리가 느릴 경우 조정 필요.
             */
            int timeoutSeconds
    ) {
        public Integration {
            if (baseUrl == null) baseUrl = "";
            if (timeoutSeconds <= 0) timeoutSeconds = 10;
        }
    }
}

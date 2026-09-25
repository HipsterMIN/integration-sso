package io.github.hipstermin.idem.plugin.anyid;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Any-ID 설치형 연동 설정 프로퍼티
 *
 * <p>행안부 Any-ID 사업단 발급 개발키 바인딩. 기본값은 플러그인의 {@code idem-plugin-anyid-defaults.yml}
 * ({@link AnyIdDefaultsEnvironmentPostProcessor}) 가 올리고, 코어 {@code application.yml} 에는 AnyID 설정이 없다 (S5b).<br>
 * 운영기관 식별자(srvc-no·agency-code·agency-name)와 자격증명은 코드 기본값 없이 설치 시 주입한다 (S1 범용화).
 *
 * <p><b>환경변수 → 설정 매핑</b>:
 * <pre>
 * ANYID_SRVC_NO          → idem.hub.anyid.srvc-no          (필수 — 운영기관에 발급된 서비스 번호)
 * ANYID_SSO_SECRET_CODE  → idem.hub.anyid.sso.secret-code  (HMAC 서명 키)
 * ANYID_KMS_APP_KEY      → idem.hub.anyid.kms.app-key      (ARIA-CBC-256 앱 키)
 * ANYID_KMS_CLIENT_INFO  → idem.hub.anyid.kms.client-info  (ARIA-CBC-256 클라이언트 정보)
 * ANYID_PID_CLIENT_ID    → idem.hub.anyid.pid.client-id    (민간ID 클라이언트 ID)
 * ANYID_PID_CLIENT_SECRET→ idem.hub.anyid.pid.client-secret
 * ANYID_PID_CLIENT_API_KEY→idem.hub.anyid.pid.client-api-key
 * </pre>
 *
 * <p><b>브로커 모드 (IDEM_HUB_BROKER_MODE) 관계</b>:
 * <pre>
 * IDEM_HUB_BROKER_MODE는 표준 OIDC(카카오·네이버)의 처리 백엔드만 결정한다.
 *   IDEM_HUB_BROKER_MODE=qsign    → [표준 OIDC] q-sign에 위임 (기본값)
 *   IDEM_HUB_BROKER_MODE=keycloak → [표준 OIDC] Keycloak 직접 연동
 *
 * ⚠️  AnyIdBrokerAdapter는 IDEM_HUB_BROKER_MODE 값과 무관하게 항상 동작한다.
 *     MOBILE_ID / EASY_SIGN / JOINT_CERT / FINANCIAL_CERT / PRIVATE_ID 등
 *     비표준 OIDC 인증수단은 ProviderRouter.resolve()가 자동으로 DIRECT_BROKER로
 *     라우팅하여 AnyIdBrokerAdapter로 연결한다.
 * </pre>
 *
 * @see AnyIdBrokerAdapter
 * @see AnyIdKmsClient
 * @see AnyIdAutoConfiguration
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "idem.hub.anyid")
public class AnyIdProperties {

    // ── 기관 식별자 ──────────────────────────────────────────────────────
    /** 서비스 번호 — 모든 API 호출의 srvc_no 파라미터. 설치 시 주입(코어 기본값 없음) */
    private String srvcNo = "";
    /** 기관 코드 (=srvcNo) */
    private String agencyCode = "";
    /** 기관명 (로그/감사 추적용) */
    private String agencyName = "";

    /** 운영기관 식별자 3종이 모두 주입됐는가. AnyID 를 쓰지 않는 설치에서는 false 이며 브로커 진입 시 503 으로 거부한다. */
    public boolean isAgencyConfigured() {
        return hasText(srvcNo) && hasText(agencyCode) && hasText(agencyName);
    }

    /** AnyID 브로커 진입점에서 호출 — 식별자 미설정이면 IDEM_HUB_PROVIDER_NOT_CONFIGURED(503). */
    public void requireAgencyConfigured(String correlationId) {
        if (!isAgencyConfigured()) {
            throw new PlatformException(PlatformErrorCode.IDO_PROVIDER_NOT_CONFIGURED, correlationId,
                    "AnyID 운영기관 식별자 미설정 — idem.hub.anyid.srvc-no / agency-code / agency-name (ANYID_SRVC_NO 등) 을 주입하세요");
        }
    }

    private static boolean hasText(String v) {
        return v != null && !v.isBlank();
    }

    // ── Any-ID 포털 (관리 API) ────────────────────────────────────────────
    private Portal portal = new Portal();
    // ── Any-ID 인증 서버 ──────────────────────────────────────────────────
    private Auth auth = new Auth();
    // ── Any-ID SSO 서버 ───────────────────────────────────────────────────
    private Sso sso = new Sso();
    // ── Any-ID KMS (ARIA-CBC-256) ─────────────────────────────────────────
    private Kms kms = new Kms();
    // ── 민간ID (소셜 로그인) ──────────────────────────────────────────────
    private Pid pid = new Pid();

    /** config.anyidc.json 파일 위치 */
    private String configFile = "classpath:config/anyid/config.anyidc.json";

    // ── HTTP 공통 타임아웃 ─────────────────────────────────────────────────
    private Http http = new Http();

    // ══════════════════════════════════════════════════════════════════════
    // 중첩 설정 클래스
    // ══════════════════════════════════════════════════════════════════════

    @Getter @Setter
    public static class Portal {
        /** 포털 도메인 (개발: https://www.anyid.dev, 운영: https://www.anyid.go.kr) */
        private String domain  = "https://www.anyid.dev";
        /** 포털 포트 (개발: 1451, 운영: 443) */
        private int    port    = 1451;
        private String baseUrl = "https://www.anyid.dev:1451";

        /** 완성된 포털 기본 URL 반환 */
        public String resolvedBaseUrl() {
            return (baseUrl != null && !baseUrl.isBlank())
                    ? baseUrl : domain + ":" + port;
        }
    }

    @Getter @Setter
    public static class Auth {
        /** 인증 서버 도메인 (개발: https://www.anyid.dev) */
        private String domain  = "https://www.anyid.dev";
        /** 인증 서버 포트 (개발: 1443, 운영: 443) */
        private int    port    = 1443;
        private String baseUrl = "https://www.anyid.dev:1443";

        public String resolvedBaseUrl() {
            return (baseUrl != null && !baseUrl.isBlank())
                    ? baseUrl : domain + ":" + port;
        }
    }

    @Getter @Setter
    public static class Sso {
        /** SSO 서버 도메인 (개발: https://sso.anyid.dev, 운영: https://sso.anyid.go.kr) */
        private String domain     = "https://sso.anyid.dev";
        /** SSO 포트 (개발: 1452, 운영: 443) */
        private int    port       = 1452;
        private String baseUrl    = "https://sso.anyid.dev:1452";
        /** HMAC-SHA256 서명 검증 시크릿 (Base64, 행안부 발급) */
        private String secretCode = "";
        /** SSO 어댑터 설정 파일 위치 */
        private String adaptorConf = "classpath:config/anyid/sso-adaptor-conf-local.properties";
        /** SSO 세션 TTL (초) */
        private long   sessionTtlSeconds = 3600;
        /** SSO 토큰 검증 URL */
        private String verifyUrl  = "";
        /** SSO 로그아웃 URL */
        private String logoutUrl  = "";

        public String resolvedBaseUrl() {
            return (baseUrl != null && !baseUrl.isBlank())
                    ? baseUrl : domain + ":" + port;
        }
    }

    @Getter @Setter
    public static class Kms {
        /** KMS 서버 호스트 (개발: https://www.anyid.dev:8119/) */
        private String serverHost  = "https://www.anyid.dev:8119/";
        /** 암호화 알고리즘 — ARIA-CBC-256 (국산 블록 암호) */
        private String encAlg      = "ARIA-CBC-256";
        /** 키 버전 */
        private int    cversion    = 1;
        /** kdist.json 파일 위치 */
        private String kdistConfig = "classpath:config/anyid/kdist-local.json";
        /** KMS 앱 키 (Base64, 행안부 발급) — K8s Secret ANYID_KMS_APP_KEY */
        private String appKey      = "";
        /** KMS 클라이언트 정보 (Base64, 행안부 발급) — K8s Secret ANYID_KMS_CLIENT_INFO */
        private String clientInfo  = "";
        /** KMS 연결 타임아웃 (ms) */
        private int    connectTimeoutMs = 3000;
        /** KMS 읽기 타임아웃 (ms) */
        private int    readTimeoutMs    = 5000;
    }

    @Getter @Setter
    public static class Pid {
        /** pid_api.json 위치 */
        private String apiConfig    = "classpath:config/anyid/pid_api.json";
        /** 민간ID 인증 시작 URL (social-relay) */
        private String providerUrl  = "https://www.anyid.dev:1443/pid/auth.do";
        /** ido 수신 콜백 경로 */
        private String callbackPath = "/api/v1/anyid/pid/callback";
        /** 민간ID 클라이언트 ID (행안부 발급) */
        private String clientId     = "";
        /** 민간ID 클라이언트 시크릿 (행안부 발급) */
        private String clientSecret = "";
        /** 민간ID 클라이언트 API 키 (행안부 발급) */
        private String clientApiKey = "";
    }

    @Getter @Setter
    public static class Http {
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs    = 5000;
    }
}

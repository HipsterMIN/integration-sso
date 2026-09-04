package kr.go.smes.ido.broker.anyid;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Any-ID 설치형 연동 설정 프로퍼티 (기관 #311 — 중소벤처24기업마당)
 *
 * <p>행안부 Any-ID 사업단 발급 개발키 바인딩.<br>
 * 원본: {@code 별첨2.(개발KEY)311_중소기업기술정보진흥원_중소벤처24기업마당.xlsx}
 *
 * <p><b>환경변수 → 설정 매핑</b>:
 * <pre>
 * ANYID_SRVC_NO          → ido.anyid.srvc-no          (기본: 1000001157)
 * ANYID_SSO_SECRET_CODE  → ido.anyid.sso.secret-code  (HMAC 서명 키)
 * ANYID_KMS_APP_KEY      → ido.anyid.kms.app-key      (ARIA-CBC-256 앱 키)
 * ANYID_KMS_CLIENT_INFO  → ido.anyid.kms.client-info  (ARIA-CBC-256 클라이언트 정보)
 * ANYID_PID_CLIENT_ID    → ido.anyid.pid.client-id    (민간ID 클라이언트 ID)
 * ANYID_PID_CLIENT_SECRET→ ido.anyid.pid.client-secret
 * ANYID_PID_CLIENT_API_KEY→ido.anyid.pid.client-api-key
 * </pre>
 *
 * <p><b>브로커 모드 (IDO_BROKER_MODE) 관계</b>:
 * <pre>
 * IDO_BROKER_MODE는 표준 OIDC(카카오·네이버)의 처리 백엔드만 결정한다.
 *   IDO_BROKER_MODE=qsign    → [표준 OIDC] q-sign에 위임 (기본값)
 *   IDO_BROKER_MODE=keycloak → [표준 OIDC] Keycloak 직접 연동
 *
 * ⚠️  AnyIdBrokerAdapter는 IDO_BROKER_MODE 값과 무관하게 항상 동작한다.
 *     MOBILE_ID / EASY_SIGN / JOINT_CERT / FINANCIAL_CERT / PRIVATE_ID 등
 *     비표준 OIDC 인증수단은 ProviderRouter.resolve()가 자동으로 DIRECT_BROKER로
 *     라우팅하여 AnyIdBrokerAdapter로 연결한다.
 * </pre>
 *
 * @see AnyIdBrokerAdapter
 * @see kr.go.smes.ido.crypto.kms.AnyIdKmsClient
 * @see kr.go.smes.ido.broker.BrokerService
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ido.anyid")
public class AnyIdProperties {

    // ── 기관 식별자 ──────────────────────────────────────────────────────
    /** 서비스 번호 — 모든 API 호출의 srvc_no 파라미터 (기본: 1000001157) */
    private String srvcNo = "1000001157";
    /** 기관 코드 (=srvcNo) */
    private String agencyCode = "1000001157";
    /** 기관명 (로그/감사 추적용) */
    private String agencyName = "중소벤처24기업마당";

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
        private String adaptorConf = "classpath:sso-adaptor-conf-local.properties";
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

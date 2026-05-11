package kr.go.smes.qsign.keycloak;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Q-Sign Keycloak 어댑터 설정 프로퍼티
 *
 * <p>q-sign 은 Keycloak 의 OIDC 클라이언트 어댑터로 동작한다.
 * 카카오·네이버 등 소셜 IdP 연동은 Keycloak 이 내부적으로 처리하며,
 * q-sign 은 Keycloak 과만 통신한다 (외부 IdP 직접 호출 금지).
 *
 * <p>설정 예시 (application.yml):
 * <pre>
 * qsign:
 *   keycloak:
 *     base-url: http://localhost:8080
 *     realm: onepass
 *     client-id: q-sign-client
 *     client-secret: ${QSIGN_KEYCLOAK_CLIENT_SECRET:change-me}
 *     redirect-uri: http://localhost:8081/api/v1/oidc/keycloak/callback
 *     state-ttl-seconds: 300
 *     idp-hint-mapping:
 *       kakao: social-kakao
 *       naver: social-naver
 *       pass: social-pass
 *       gpki: social-gpki
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "qsign.keycloak")
public class KeycloakProperties {

    /** Keycloak 서버 Base URL (예: http://localhost:8080) */
    private String baseUrl = "http://localhost:8080";

    /** Keycloak Realm 이름 */
    private String realm = "onepass";

    /** q-sign 용 Keycloak Client ID */
    private String clientId = "q-sign-client";

    /**
     * q-sign 용 Keycloak Client Secret
     * 환경변수: QSIGN_KEYCLOAK_CLIENT_SECRET (필수)
     *
     * <p>[P2 수정] 기본값 "change-me" 제거 → 환경변수 주입 필수.
     * 미설정 시 Keycloak Token Endpoint 401 → 기동 후 즉시 감지 가능.
     */
    private String clientSecret = "";

    /**
     * Keycloak 이 인증 완료 후 리다이렉트할 q-sign Callback URI
     * Keycloak Admin Console 의 Valid Redirect URIs 에 반드시 등록 필요
     */
    private String redirectUri = "http://localhost:8081/api/v1/oidc/keycloak/callback";

    /** Authorization URL state/nonce Redis TTL (초, 기본 300초 = 5분) */
    private long stateTtlSeconds = 300L;

    /**
     * provider 식별자 → Keycloak kc_idp_hint 매핑 테이블
     * 기본값: kakao→social-kakao, naver→social-naver, pass→social-pass, gpki→social-gpki
     */
    private Map<String, String> idpHintMapping = new HashMap<>(Map.of(
            "kakao", "social-kakao",
            "naver", "social-naver",
            "pass",  "social-pass",
            "gpki",  "social-gpki"
    ));

    // ── 편의 메서드 ────────────────────────────────────────────────────────

    /**
     * Keycloak Token Endpoint URL
     * POST {baseUrl}/realms/{realm}/protocol/openid-connect/token
     */
    public String tokenEndpoint() {
        return baseUrl + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    /**
     * Keycloak Authorization Endpoint URL
     * GET {baseUrl}/realms/{realm}/protocol/openid-connect/auth
     */
    public String authorizationEndpoint() {
        return baseUrl + "/realms/" + realm + "/protocol/openid-connect/auth";
    }

    /**
     * Keycloak JWKS URI
     * GET {baseUrl}/realms/{realm}/protocol/openid-connect/certs
     */
    public String jwksUri() {
        return baseUrl + "/realms/" + realm + "/protocol/openid-connect/certs";
    }

    /**
     * provider 이름에서 Keycloak kc_idp_hint 값을 조회한다.
     * idpHintMapping 에 없으면 provider 원문을 그대로 반환한다.
     *
     * @param provider ido 가 전달한 provider 식별자 (예: "kakao")
     * @return Keycloak IdP alias (예: "social-kakao")
     */
    public String resolveIdpHint(String provider) {
        if (provider == null) return "";
        return idpHintMapping.getOrDefault(provider.toLowerCase(), provider);
    }
}

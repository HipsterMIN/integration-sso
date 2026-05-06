package kr.go.smes.ido.broker.keycloak;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Keycloak 연동 설정 프로퍼티 (문서 §5-1)
 *
 * <p>q-sign = Keycloak 전환 시 {@code ido.qsign.*} 블록을
 * {@code ido.keycloak.*} 블록으로 교체하여 사용한다.
 *
 * <p>이중 운영 모드:
 * <ul>
 *   <li>{@code ido.broker.mode=qsign}    : 기존 q-sign Spring Boot 직접 연동 (현재)</li>
 *   <li>{@code ido.broker.mode=keycloak} : Keycloak OIDC 브로커 모드 (전환 후)</li>
 * </ul>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ido.keycloak")
public class KeycloakProperties {

    /**
     * Keycloak 서버 base URL
     * 예: http://localhost:8088 (로컬), http://keycloak:8088 (Docker)
     */
    private String baseUrl = "http://localhost:8088";

    /**
     * Keycloak Realm 이름 (§10-1)
     * 예: onepass
     */
    private String realm = "onepass";

    /**
     * ido-client Client ID (§10-2)
     * Keycloak Admin Console → Clients → ido-client
     */
    private String clientId = "ido-client";

    /**
     * ido-client Client Secret (§10-2 confidential client)
     * Token Endpoint code 교환 시 사용
     */
    private String clientSecret = "change-me";

    /**
     * Keycloak 콜백 Redirect URI (§10-2 Valid Redirect URIs)
     * GET /api/v1/broker/callback — ido가 직접 수신
     */
    private String redirectUri = "http://localhost:8083/api/v1/broker/callback";

    /**
     * state/nonce Redis TTL (초)
     * CSRF 방어용 state 유효 시간 — Keycloak 인가 URL 유효시간과 동일하게 설정
     */
    private long stateTtlSeconds = 300;

    /**
     * kc_idp_hint 매핑: 브로커 경로 provider → Keycloak Identity Provider alias (§10-3)
     * 예: kakao → social-kakao, naver → social-naver
     */
    private Map<String, String> idpHintMapping = Map.of(
            "kakao", "social-kakao",
            "naver", "social-naver"
    );

    /**
     * Keycloak acr 클레임 → 플랫폼 AuthLevel 매핑 (§10-4)
     * 예: "1" → L1, "2" → L2, "3" → L3
     */
    private Map<String, String> acrToAuthLevel = Map.of(
            "1", "L1",
            "2", "L2",
            "3", "L3"
    );

    /**
     * Keycloak Token Endpoint URL 반환
     * POST {baseUrl}/realms/{realm}/protocol/openid-connect/token
     */
    public String tokenEndpoint() {
        return baseUrl + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    /**
     * Keycloak Authorization Endpoint URL 반환
     * GET {baseUrl}/realms/{realm}/protocol/openid-connect/auth
     */
    public String authorizationEndpoint() {
        return baseUrl + "/realms/" + realm + "/protocol/openid-connect/auth";
    }

    /**
     * provider 코드(kakao/naver)에 대응하는 kc_idp_hint 반환
     * 매핑이 없으면 provider 값 그대로 반환 (안전한 fallback)
     */
    public String resolveIdpHint(String provider) {
        return idpHintMapping.getOrDefault(provider, provider);
    }

    /**
     * identity_provider 클레임 → 플랫폼 providerCode 변환 (§10-5)
     * social-kakao → KAKAO_OIDC, social-naver → NAVER_OIDC 등
     */
    public String resolveProviderCode(String identityProvider) {
        if (identityProvider == null) return "UNKNOWN";
        // Keycloak IdP alias 역매핑
        return idpHintMapping.entrySet().stream()
                .filter(e -> e.getValue().equals(identityProvider))
                .map(e -> e.getKey().toUpperCase() + "_OIDC")
                .findFirst()
                .orElse(identityProvider.toUpperCase().replace("-", "_"));
    }

    /**
     * Keycloak acr 클레임 → 플랫폼 AuthLevel 변환 (§10-4)
     * null 또는 미등록 acr → L1 (최소 수준, 안전 우선)
     */
    public String resolveAuthLevel(String acr) {
        if (acr == null) return "L1";
        return acrToAuthLevel.getOrDefault(acr, "L1");
    }
}

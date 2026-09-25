package io.github.hipstermin.idem.hub.broker.keycloak;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Keycloak 연동 설정 프로퍼티 (문서 §5-1)
 *
 * <p>q-sign = Keycloak 전환 시 {@code idem.hub.gate.*} 블록을
 * {@code idem.hub.keycloak.*} 블록으로 교체하여 사용한다.
 *
 * <p>이중 운영 모드:
 * <ul>
 *   <li>{@code idem.hub.broker.mode=qsign}    : 기존 q-sign Spring Boot 직접 연동 (현재)</li>
 *   <li>{@code idem.hub.broker.mode=keycloak} : Keycloak OIDC 브로커 모드 (전환 후)</li>
 * </ul>
 */
@Slf4j
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "idem.hub.keycloak")
public class KeycloakProperties {

    /**
     * 절대 통과 금지 placeholder 목록 (Sprint γ-1 / F2.1).
     */
    private static final Set<String> FORBIDDEN_PLACEHOLDERS = Set.of(
            "change-me",
            "changeme",
            "default",
            "secret",
            "client-secret",
            "test"
    );

    /**
     * Keycloak 서버 base URL
     * 예: http://localhost:8088 (로컬), http://keycloak:8088 (Docker)
     */
    private String baseUrl = "http://localhost:8088";

    /**
     * Keycloak Realm 이름 (§10-1)
     * 예: idem
     */
    private String realm = "idem";

    /**
     * idem-hub Client ID (§10-2)
     * Keycloak Admin Console → Clients → idem-hub
     */
    private String clientId = "idem-hub";

    /**
     * idem-hub Client Secret (§10-2 confidential client)
     * Token Endpoint code 교환 시 사용
     *
     * <p>[Sprint γ-1 / F2.1] 기본값 "change-me" 제거 → 환경변수 KEYCLOAK_CLIENT_SECRET 필수.
     * 부팅 시 {@link #validateClientSecret()} 가 비어있거나 placeholder 면 즉시 실패.
     * (q-sign 측 KeycloakProperties 와 동일 패턴 — α-3 F4.3 패턴 재사용)
     */
    private String clientSecret = "";

    /**
     * 부팅 검증 우회 escape hatch — 테스트/로컬 한정.
     * 운영 환경에서는 절대 true 설정 금지.
     * <p>활성화 방법(테스트 한정): {@code idem.hub.keycloak.allow-empty-client-secret=true}
     */
    @Value("${idem.hub.keycloak.allow-empty-client-secret:false}")
    private boolean allowEmptyClientSecret;

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

    // ── 부팅 시 안전 검증 (Sprint γ-1 / F2.1) ─────────────────────────────────

    /**
     * 부팅 시 client-secret 안전성 검증 (ido 측 — q-sign 측과 동일 패턴).
     *
     * <p>실패 조건:
     * <ul>
     *   <li>{@code clientSecret} null/blank + {@code allow-empty-client-secret} 미설정</li>
     *   <li>{@code clientSecret} 이 알려진 placeholder ({@code change-me} 등)</li>
     *   <li>{@code clientSecret} 8자 미만</li>
     * </ul>
     *
     * <p>실패 시 {@link IllegalStateException} 으로 컨텍스트 기동을 중단한다.
     */
    @PostConstruct
    void validateClientSecret() {
        if (clientSecret == null || clientSecret.isBlank()) {
            if (allowEmptyClientSecret) {
                log.warn("[ido KeycloakProperties] idem.hub.keycloak.client-secret 미설정 — "
                        + "allow-empty-client-secret=true 로 우회 (테스트/로컬 한정)");
                return;
            }
            throw new IllegalStateException(
                    "idem.hub.keycloak.client-secret 환경변수 KEYCLOAK_CLIENT_SECRET 가 설정되지 않았습니다. "
                            + "운영에서는 반드시 Keycloak Admin Console 의 Client Secret 을 주입하십시오. "
                            + "테스트/로컬에서만 idem.hub.keycloak.allow-empty-client-secret=true 로 우회 가능.");
        }
        String normalized = clientSecret.trim().toLowerCase();
        if (FORBIDDEN_PLACEHOLDERS.contains(normalized)) {
            throw new IllegalStateException(
                    "idem.hub.keycloak.client-secret 가 안전하지 않은 placeholder('"
                            + clientSecret + "') 입니다. 운영용 비밀키를 주입하십시오.");
        }
        if (clientSecret.length() < 8) {
            throw new IllegalStateException(
                    "idem.hub.keycloak.client-secret 가 너무 짧습니다 (length="
                            + clientSecret.length() + "). 최소 8자 이상의 무작위 비밀키를 주입하십시오.");
        }
        log.info("[ido KeycloakProperties] client-secret 검증 통과 (length={})",
                clientSecret.length());
    }
}

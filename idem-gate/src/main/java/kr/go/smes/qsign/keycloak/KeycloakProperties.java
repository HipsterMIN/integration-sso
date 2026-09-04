package kr.go.smes.qsign.keycloak;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
@Slf4j
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
     * <p>[Sprint γ-1 / F2.1] 기본값 "change-me" 제거 → 환경변수 주입 필수.
     * 부팅 시 {@link #validateClientSecret()} 가 비어있거나 placeholder 면 즉시 실패.
     * 미설정 시 Keycloak Token Endpoint 401 가 아닌 컨테이너 CrashLoopBackOff 로
     * 운영자가 즉시 인지 가능. (α-3 F4.3 패턴 재사용)
     */
    private String clientSecret = "";

    /**
     * 부팅 검증 우회 escape hatch — 테스트/로컬 한정.
     * 운영 환경에서는 절대 true 설정 금지.
     * <p>활성화 방법(테스트 한정): {@code qsign.keycloak.allow-empty-client-secret=true}
     */
    @Value("${qsign.keycloak.allow-empty-client-secret:false}")
    private boolean allowEmptyClientSecret;

    /**
     * 절대 통과 금지 placeholder 목록. 과거 default 값 / 흔한 디폴트 값을
     * 운영에 그대로 주입했을 때 즉시 차단한다.
     */
    private static final java.util.Set<String> FORBIDDEN_PLACEHOLDERS = java.util.Set.of(
            "change-me",
            "changeme",
            "default",
            "secret",
            "client-secret",
            "test"
    );

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

    // ── 부팅 시 안전 검증 (Sprint γ-1 / F2.1) ─────────────────────────────────

    /**
     * 부팅 시 client-secret 안전성 검증.
     *
     * <p>실패 조건:
     * <ul>
     *   <li>{@code clientSecret} 이 null/blank 인데 {@code allow-empty-client-secret} 미설정</li>
     *   <li>{@code clientSecret} 이 알려진 placeholder ({@code change-me} 등)</li>
     * </ul>
     *
     * <p>실패 시 {@link IllegalStateException} 으로 컨텍스트 기동 자체를 중단한다.
     * 이는 의도된 동작 — 평문 placeholder 가 운영에 그대로 주입되는 사고를 막기 위함.
     */
    @PostConstruct
    void validateClientSecret() {
        if (clientSecret == null || clientSecret.isBlank()) {
            if (allowEmptyClientSecret) {
                log.warn("[KeycloakProperties] qsign.keycloak.client-secret 미설정 — "
                        + "allow-empty-client-secret=true 로 우회 (테스트/로컬 한정)");
                return;
            }
            throw new IllegalStateException(
                    "qsign.keycloak.client-secret 환경변수 QSIGN_KEYCLOAK_CLIENT_SECRET 가 설정되지 않았습니다. "
                            + "운영에서는 반드시 Keycloak Admin Console 의 Client Secret 을 주입하십시오. "
                            + "테스트/로컬에서만 qsign.keycloak.allow-empty-client-secret=true 로 우회 가능.");
        }
        String normalized = clientSecret.trim().toLowerCase();
        if (FORBIDDEN_PLACEHOLDERS.contains(normalized)) {
            throw new IllegalStateException(
                    "qsign.keycloak.client-secret 가 안전하지 않은 placeholder('"
                            + clientSecret + "') 입니다. 운영용 비밀키를 주입하십시오.");
        }
        if (clientSecret.length() < 8) {
            throw new IllegalStateException(
                    "qsign.keycloak.client-secret 가 너무 짧습니다 (length="
                            + clientSecret.length() + "). 최소 8자 이상의 무작위 비밀키를 주입하십시오.");
        }
        log.info("[KeycloakProperties] client-secret 검증 통과 (length={})", clientSecret.length());
    }
}

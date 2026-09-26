package io.github.hipstermin.idem.gate.keycloak.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Keycloak ID Token (JWT) payload Claims DTO
 *
 * <p>Keycloak JWKS RS256 서명 검증 완료 후 payload 를 역직렬화한 결과.
 * PII(sub 등)는 identifierHash 생성 후 원문 참조 금지.
 *
 * <p>audience 클레임은 Keycloak 발급 토큰에서 String 또는 List&lt;String&gt; 양쪽 모두
 * 가능하므로 {@link Object} 로 수신하고 {@link #getAudienceAsString()} 으로 정규화한다.
 *
 * <p>identity_provider 클레임은 Keycloak 이 소셜 IdP 브로커링 시 설정하는 값이다
 * (예: "social-kakao"). 이 값을 역매핑하여 providerCode 를 결정한다.
 */
@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KeycloakIdTokenClaims {

    /** Keycloak Issuer URL (예: http://localhost:8080/realms/idem) */
    @JsonProperty("iss")
    private String issuer;

    /**
     * Subject — Keycloak 내부 사용자 고유 식별자.
     * identifierHash = SHA-256(sub) 계산 원본 (원문은 이후 참조 금지).
     */
    @JsonProperty("sub")
    private String subject;

    /**
     * Audience — idem-gate (Keycloak 발급 토큰).
     * 단일 String 또는 List&lt;String&gt; 양쪽 허용.
     */
    @JsonProperty("aud")
    private Object audience;

    /** nonce — replay attack 방지 검증 대상 */
    @JsonProperty("nonce")
    private String nonce;

    /** 발급 시각 (epoch seconds) */
    @JsonProperty("iat")
    private long issuedAt;

    /** 만료 시각 (epoch seconds) */
    @JsonProperty("exp")
    private long expiresAt;

    /**
     * ACR (Authentication Context Class Reference)
     * Keycloak 인증 수준 표현에 사용 가능
     */
    @JsonProperty("acr")
    private String acr;

    /**
     * Keycloak 이 소셜 IdP 브로커링 시 설정하는 클레임.
     * 값 예시: "social-kakao", "social-naver"
     * 이 값을 역매핑(social-kakao → KAKAO_OIDC) 하여 providerCode 를 결정한다.
     */
    @JsonProperty("identity_provider")
    private String identityProvider;

    /** S6: Keycloak 세션 ID — 판정 감사·캐시 키. */
    @JsonProperty("sid")
    private String sessionId;

    /** S6: authorized party — 토큰을 받은 client_id. */
    @JsonProperty("azp")
    private String authorizedParty;

    /** JWT ID — Back-Channel Logout 토큰은 필수(§2.4), 재사용 방지 키. */
    @JsonProperty("jti")
    private String jwtId;

    /** S6 PR-2: Back-Channel Logout 토큰의 {@code events} 클레임 (OIDC Back-Channel Logout 1.0 §2.4). */
    @JsonProperty("events")
    private java.util.Map<String, Object> events;

    /** Back-Channel Logout 토큰인가 — {@code events} 에 정해진 URI 가 있어야 한다. */
    public boolean isBackchannelLogoutToken() {
        return events != null && events.containsKey("http://schemas.openid.net/event/backchannel-logout");
    }

    // ── 편의 메서드 ────────────────────────────────────────────────────────

    /**
     * audience 를 항상 String 으로 반환한다.
     * <ul>
     *   <li>String  → 그대로 반환</li>
     *   <li>List    → 첫 번째 원소 반환</li>
     *   <li>null    → null 반환</li>
     * </ul>
     */
    /** audience 전체 — String 이면 1개, List 면 각 원소. 1.0.1: 검증은 이 목록의 원소와 <b>정확 일치</b>로 한다(부분 일치 금지). */
    public List<String> getAudiences() {
        if (audience == null) return List.of();
        if (audience instanceof String s) return List.of(s);
        if (audience instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        return List.of(String.valueOf(audience));
    }

    @SuppressWarnings("unchecked")
    public String getAudienceAsString() {
        if (audience == null) return null;
        if (audience instanceof String s) return s;
        if (audience instanceof List<?> list) {
            return list.isEmpty() ? null : String.valueOf(list.get(0));
        }
        return String.valueOf(audience);
    }
}

package kr.go.smes.ido.broker.keycloak.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Keycloak ID Token JWT Claims DTO (문서 §5-4)
 *
 * <p>Keycloak이 발급한 id_token의 payload 클레임.
 * q-sign의 {@code KakaoIdTokenClaims}에 해당하는 역할을 IdO가 담당.
 *
 * <p>필수 Mapper 설정 (§10-5):
 * <ul>
 *   <li>{@code identity_provider}: Keycloak User Session Note Mapper → IdP alias (social-kakao 등)</li>
 *   <li>{@code acr}: Keycloak Authentication Context Class Reference → 인증 수준 (1/2/3)</li>
 *   <li>{@code nonce}: OIDC 표준 클레임 — Keycloak이 자동 포함</li>
 * </ul>
 */
@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KeycloakJwtClaims {

    /** Keycloak Issuer URL (예: http://localhost:8088/realms/onepass) */
    @JsonProperty("iss")
    private String issuer;

    /** ido-client Client ID */
    @JsonProperty("aud")
    private Object audience;   // String 또는 String[] 모두 처리

    /** Keycloak 고유 사용자 식별자 — identifierHash = SHA-256(sub) */
    @JsonProperty("sub")
    private String subject;

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
     * 인증 수단 IdP alias (§10-5 User Session Note Mapper 필요)
     * 예: social-kakao, social-naver
     * → KeycloakProperties.resolveProviderCode()로 KAKAO_OIDC 등으로 변환
     */
    @JsonProperty("identity_provider")
    private String identityProvider;

    /**
     * 인증 수준 (§10-4 ACR Mapper 필요)
     * 예: "1" → L1, "2" → L2, "3" → L3
     */
    @JsonProperty("acr")
    private String acr;

    /** 이메일 (scope에 email 포함 시) */
    @JsonProperty("email")
    private String email;

    /** 선호 사용자명 (scope에 profile 포함 시) */
    @JsonProperty("preferred_username")
    private String preferredUsername;

    /**
     * audience 문자열 반환 (배열인 경우 첫 번째 값)
     */
    public String getAudienceAsString() {
        if (audience == null) return null;
        if (audience instanceof String s) return s;
        if (audience instanceof java.util.List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(0));
        }
        return audience.toString();
    }
}

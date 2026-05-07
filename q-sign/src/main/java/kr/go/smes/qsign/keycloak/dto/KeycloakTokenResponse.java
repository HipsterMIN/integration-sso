package kr.go.smes.qsign.keycloak.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Keycloak Token Endpoint 응답 DTO
 *
 * <p>POST {keycloak}/realms/{realm}/protocol/openid-connect/token 응답 바인딩.
 * id_token 이 없으면 OIDC 스코프 미설정 또는 Keycloak Client 설정 오류이므로
 * IDP_RESPONSE_INVALID 예외를 발생시킨다.
 */
@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KeycloakTokenResponse {

    /** Bearer Access Token */
    @JsonProperty("access_token")
    private String accessToken;

    /** OpenID Connect ID Token (JWT) — JWKS 검증 대상 */
    @JsonProperty("id_token")
    private String idToken;

    /** Access Token 만료 시간(초) */
    @JsonProperty("expires_in")
    private int expiresIn;

    /** Refresh Token */
    @JsonProperty("refresh_token")
    private String refreshToken;

    /** 토큰 타입 (항상 "Bearer") */
    @JsonProperty("token_type")
    private String tokenType;

    /** 발급된 scope 목록 */
    @JsonProperty("scope")
    private String scope;
}

package kr.go.smes.qsign.broker.oidc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 카카오 Token Endpoint 응답 DTO
 * https://developers.kakao.com/docs/latest/ko/kakaologin/rest-api#request-token-response
 */
@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KakaoTokenResponse {

    /** Bearer 액세스 토큰 */
    @JsonProperty("access_token")
    private String accessToken;

    /** OpenID Connect ID Token (JWT) */
    @JsonProperty("id_token")
    private String idToken;

    /** access_token 만료 시간(초) */
    @JsonProperty("expires_in")
    private int expiresIn;

    /** refresh_token */
    @JsonProperty("refresh_token")
    private String refreshToken;

    /** 토큰 타입 (항상 "bearer") */
    @JsonProperty("token_type")
    private String tokenType;

    /** 부여된 scope 목록 */
    @JsonProperty("scope")
    private String scope;
}

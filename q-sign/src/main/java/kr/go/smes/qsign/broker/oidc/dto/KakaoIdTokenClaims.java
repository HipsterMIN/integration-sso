package kr.go.smes.qsign.broker.oidc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 카카오 ID Token (JWT) 내 Claims DTO
 * https://developers.kakao.com/docs/latest/ko/kakaologin/rest-api#oidc-id-token-payload
 *
 * <p>JWKS 서명 검증 후 파싱한 payload 클레임.
 * PII(sub, email 등)는 identifierHash 생성 후 원문 보관 금지.
 */
@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KakaoIdTokenClaims {

    /** 카카오 OIDC Issuer URL */
    @JsonProperty("iss")
    private String issuer;

    /** 카카오 앱 클라이언트 ID */
    @JsonProperty("aud")
    private String audience;

    /** 카카오 고유 사용자 식별자 (anonymized sub) — identifierHash 생성 원본 */
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

    /** 이메일 (scope에 account_email 포함 시) */
    @JsonProperty("email")
    private String email;

    /** 카카오 nickname (scope에 profile_nickname 포함 시) */
    @JsonProperty("nickname")
    private String nickname;
}

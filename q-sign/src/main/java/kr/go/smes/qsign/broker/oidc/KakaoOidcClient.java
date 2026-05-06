package kr.go.smes.qsign.broker.oidc;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.qsign.broker.oidc.dto.KakaoIdTokenClaims;
import kr.go.smes.qsign.broker.oidc.dto.KakaoTokenResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 카카오 OIDC Authorization Code Flow HTTP 클라이언트
 *
 * <p>담당 기능:
 * <ol>
 *   <li>Authorization URL 조립 (redirect_uri, state, nonce, scope)</li>
 *   <li>Authorization Code → Token 교환 (POST /oauth/token)</li>
 *   <li>ID Token JWT 파싱 (claims 추출, 서명 검증은 {@link KakaoJwksVerifier} 위임)</li>
 *   <li>identifierHash 계산 (SHA-256(sub) — PII 비보관 원칙)</li>
 * </ol>
 *
 * <p>카카오 OIDC 공식 문서:
 * https://developers.kakao.com/docs/latest/ko/kakaologin/rest-api#oidc
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoOidcClient {

    private static final String PROVIDER_CODE    = "KAKAO_OIDC";
    private static final String TOKEN_ENDPOINT   = "https://kauth.kakao.com/oauth/token";
    private static final String AUTH_ENDPOINT    = "https://kauth.kakao.com/oauth/authorize";
    private static final String KAKAO_ISSUER     = "https://kauth.kakao.com";

    private final RestTemplate      restTemplate;
    private final KakaoJwksVerifier jwksVerifier;
    private final ObjectMapper      objectMapper;

    @Value("${qsign.oidc.kakao.client-id}")
    private String clientId;

    @Value("${qsign.oidc.kakao.client-secret:}")
    private String clientSecret;

    @Value("${qsign.oidc.kakao.redirect-uri}")
    private String redirectUri;

    @Value("${qsign.oidc.kakao.scope:openid profile_nickname account_email}")
    private String scope;

    // ── Authorization URL 조립 ───────────────────────────────────────────

    /**
     * 카카오 로그인 Authorization URL 생성
     *
     * @param state CSRF 방어용 opaque 값 (OidcStateStore 가 생성)
     * @param nonce Replay attack 방지 nonce (OidcStateStore 가 생성)
     * @return 브라우저가 리다이렉트해야 할 카카오 로그인 URL
     */
    public String buildAuthorizationUrl(String state, String nonce) {
        String url = AUTH_ENDPOINT
                + "?response_type=code"
                + "&client_id=" + clientId
                + "&redirect_uri=" + encodeUri(redirectUri)
                + "&scope=" + encodeUri(scope)
                + "&state=" + state
                + "&nonce=" + nonce;
        log.debug("[KakaoOidcClient] Authorization URL 생성: state={}", state);
        return url;
    }

    // ── Token 교환 ────────────────────────────────────────────────────────

    /**
     * Authorization Code → Access Token + ID Token 교환
     *
     * @param code      카카오가 callback 으로 전달한 authorization code
     * @param correlationId 로깅용
     */
    public KakaoTokenResponse exchangeCode(String code, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type",    "authorization_code");
        body.add("client_id",     clientId);
        body.add("redirect_uri",  redirectUri);
        body.add("code",          code);
        if (clientSecret != null && !clientSecret.isBlank()) {
            body.add("client_secret", clientSecret);
        }

        try {
            ResponseEntity<KakaoTokenResponse> resp = restTemplate.exchange(
                    TOKEN_ENDPOINT,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    KakaoTokenResponse.class
            );
            if (resp.getBody() == null || resp.getBody().getIdToken() == null) {
                throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                        "카카오 token 응답에 id_token 없음");
            }
            log.info("[KakaoOidcClient] 토큰 교환 성공: correlationId={}", correlationId);
            return resp.getBody();
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("[KakaoOidcClient] 토큰 교환 실패: correlationId={}", correlationId, e);
            throw new PlatformException(PlatformErrorCode.IDP_PROVIDER_UNAVAILABLE, correlationId, e);
        }
    }

    // ── ID Token 검증 ─────────────────────────────────────────────────────

    /**
     * ID Token 서명 검증 + Claims 파싱
     *
     * <p>검증 항목:
     * <ul>
     *   <li>JWKS 서명 검증 (RS256)</li>
     *   <li>iss = "https://kauth.kakao.com"</li>
     *   <li>aud = clientId</li>
     *   <li>exp > 현재 시각</li>
     *   <li>nonce = 발급 시 저장한 nonce</li>
     * </ul>
     */
    public KakaoIdTokenClaims verifyAndParseClaims(String idToken,
                                                    String expectedNonce,
                                                    String correlationId) {
        // JWKS 서명 검증 + payload 파싱
        KakaoIdTokenClaims claims = jwksVerifier.verify(idToken, correlationId);

        // issuer 검증
        if (!KAKAO_ISSUER.equals(claims.getIssuer())) {
            throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, correlationId,
                    "issuer 불일치: " + claims.getIssuer());
        }
        // audience 검증
        if (!clientId.equals(claims.getAudience())) {
            throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, correlationId,
                    "audience 불일치: " + claims.getAudience());
        }
        // nonce 검증
        if (!expectedNonce.equals(claims.getNonce())) {
            throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, correlationId,
                    "nonce 불일치 — replay attack 의심");
        }
        // exp 검증
        long nowEpoch = System.currentTimeMillis() / 1000L;
        if (claims.getExpiresAt() < nowEpoch) {
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                    "id_token 만료: exp=" + claims.getExpiresAt());
        }

        log.info("[KakaoOidcClient] ID Token 검증 성공: sub(prefix)={}...", safeSub(claims.getSubject()));
        return claims;
    }

    // ── identifierHash 계산 ───────────────────────────────────────────────

    /**
     * SHA-256(sub) → hex 문자열
     *
     * <p>PII 비보관 원칙: sub 원문은 이 메서드 호출 이후 참조 불가.
     * identifierHash 는 Q-IM 조회 키 + AuthResult SoR 저장.
     */
    public String computeIdentifierHash(String sub) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sub.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 해시 계산 실패", e);
        }
    }

    // ── 내부 유틸 ─────────────────────────────────────────────────────────

    private String encodeUri(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    /** 로그에 sub 원문이 노출되지 않도록 앞 8자만 표시 */
    private String safeSub(String sub) {
        if (sub == null || sub.length() <= 8) return "****";
        return sub.substring(0, 8);
    }
}

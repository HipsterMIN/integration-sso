package kr.go.smes.qsign.broker.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.qsign.broker.oidc.dto.KakaoIdTokenClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * 카카오 OIDC JWKS(RS256) 서명 검증기
 *
 * <p>카카오 JWKS 엔드포인트에서 공개키를 가져와 ID Token 서명을 검증한다.
 * JWKS는 @Cacheable 로 캐싱 (Spring Cache / Redis) — TTL 은 application.yml 에서 관리.
 *
 * <p>카카오 JWKS URI: https://kauth.kakao.com/.well-known/jwks.json
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoJwksVerifier {

    private static final String KAKAO_JWKS_URI = "https://kauth.kakao.com/.well-known/jwks.json";
    private static final String ALG_RS256       = "RS256";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${qsign.oidc.kakao.client-id}")
    private String clientId;

    /**
     * ID Token JWT 서명 검증 + Claims 파싱
     *
     * <p>처리 순서:
     * <ol>
     *   <li>JWT header 에서 kid 추출</li>
     *   <li>JWKS 에서 kid 에 해당하는 RSA 공개키 조회</li>
     *   <li>RS256 서명 검증</li>
     *   <li>payload 역직렬화 → KakaoIdTokenClaims 반환</li>
     * </ol>
     */
    public KakaoIdTokenClaims verify(String idToken, String correlationId) {
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) {
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                    "ID Token 형식 오류 (JWT 3-part 아님)");
        }

        try {
            // ── 1. header 파싱 → kid, alg 추출 ─────────────────────────
            String headerJson  = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            JsonNode header    = objectMapper.readTree(headerJson);
            String kid         = header.path("kid").asText();
            String alg         = header.path("alg").asText(ALG_RS256);

            if (!ALG_RS256.equalsIgnoreCase(alg)) {
                throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, correlationId,
                        "지원하지 않는 JWT 알고리즘: " + alg);
            }

            // ── 2. JWKS 에서 공개키 조회 ─────────────────────────────────
            PublicKey publicKey = fetchPublicKey(kid, correlationId);

            // ── 3. RS256 서명 검증 ───────────────────────────────────────
            byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8);
            byte[] signatureBytes = Base64.getUrlDecoder().decode(parts[2]);

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update(signingInput);

            if (!sig.verify(signatureBytes)) {
                throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, correlationId,
                        "ID Token RS256 서명 검증 실패");
            }

            // ── 4. payload 파싱 ──────────────────────────────────────────
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            KakaoIdTokenClaims claims = objectMapper.readValue(payloadJson, KakaoIdTokenClaims.class);

            log.debug("[KakaoJwksVerifier] 서명 검증 성공: kid={}", kid);
            return claims;

        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("[KakaoJwksVerifier] ID Token 검증 예외: correlationId={}", correlationId, e);
            throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, correlationId, e);
        }
    }

    /**
     * 카카오 JWKS 에서 kid 에 해당하는 RSA PublicKey 반환
     * Spring Cache 로 캐싱 (키 이름: kakaoJwks)
     */
    @Cacheable(value = "kakaoJwks", key = "#kid")
    public PublicKey fetchPublicKey(String kid, String correlationId) {
        try {
            String jwksJson = restTemplate.getForObject(KAKAO_JWKS_URI, String.class);
            JsonNode jwks   = objectMapper.readTree(jwksJson);
            JsonNode keys   = jwks.path("keys");

            for (JsonNode key : keys) {
                if (kid.equals(key.path("kid").asText())) {
                    // RSA n, e 추출
                    byte[] nBytes = Base64.getUrlDecoder().decode(key.path("n").asText());
                    byte[] eBytes = Base64.getUrlDecoder().decode(key.path("e").asText());

                    BigInteger n = new BigInteger(1, nBytes);
                    BigInteger e = new BigInteger(1, eBytes);

                    KeyFactory kf = KeyFactory.getInstance("RSA");
                    return kf.generatePublic(new RSAPublicKeySpec(n, e));
                }
            }
            throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, correlationId,
                    "JWKS 에서 kid 를 찾을 수 없음: " + kid);

        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("[KakaoJwksVerifier] JWKS 조회 실패", e);
            throw new PlatformException(PlatformErrorCode.IDP_PROVIDER_UNAVAILABLE, correlationId, e);
        }
    }
}

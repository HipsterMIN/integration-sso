package kr.go.smes.ido.broker.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.ido.broker.keycloak.dto.KeycloakJwtClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * Keycloak JWKS 기반 ID Token 서명 검증기 (문서 §7.4)
 *
 * <p>Keycloak이 발급한 id_token(JWT RS256)의 서명을 JWKS 엔드포인트에서
 * 가져온 공개키로 검증한다.
 *
 * <p>JWKS 캐시: {@code keycloakJwks} 캐시에 TTL 기반으로 저장 (기본 1시간).
 * IdoWebConfig의 {@code CacheManager} 빈을 통해 설정.
 *
 * <p>JWKS 엔드포인트:
 * {@code {keycloak.base-url}/realms/{realm}/protocol/openid-connect/certs}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeycloakJwksVerifier {

    private static final String CACHE_NAME = "keycloakJwks";

    private final KeycloakProperties keycloakProperties;
    private final RestTemplate       restTemplate;
    private final ObjectMapper       objectMapper;

    /**
     * Keycloak id_token 서명 검증 및 클레임 파싱
     *
     * @param idToken       Keycloak 발급 id_token (JWT 문자열)
     * @param correlationId 흐름 추적 ID (에러 로깅용)
     * @return 파싱된 {@link KeycloakJwtClaims}
     * @throws PlatformException 서명 불일치 / 만료 / 파싱 오류 시
     */
    public KeycloakJwtClaims verifyAndParse(String idToken, String correlationId) {
        try {
            // 1. JWT 헤더에서 kid 추출
            String kid = extractKid(idToken);
            log.debug("[KeycloakJwksVerifier] id_token 검증 시작: kid={} correlationId={}", kid, correlationId);

            // 2. JWKS에서 공개키 조회 (캐시 적용)
            RSAPublicKey publicKey = fetchPublicKey(kid);

            // 3. JWT 서명 검증 + 클레임 파싱
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(idToken);

            Claims claims = jws.getPayload();

            // 4. DTO 매핑
            KeycloakJwtClaims result = objectMapper.convertValue(claims, KeycloakJwtClaims.class);
            log.info("[KeycloakJwksVerifier] id_token 검증 성공: sub={}... correlationId={}",
                    result.getSubject() != null && result.getSubject().length() >= 8
                            ? result.getSubject().substring(0, 8) : "??",
                    correlationId);
            return result;

        } catch (JwtException e) {
            log.warn("[KeycloakJwksVerifier] JWT 서명 검증 실패: correlationId={} error={}", correlationId, e.getMessage());
            throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, correlationId,
                    "Keycloak id_token 서명 검증 실패: " + e.getMessage());
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("[KeycloakJwksVerifier] id_token 파싱 실패: correlationId={}", correlationId, e);
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                    "Keycloak id_token 파싱 오류");
        }
    }

    /**
     * JWKS에서 kid에 해당하는 RSA 공개키 조회 (캐시 적용)
     *
     * <p>캐시 키: kid — 키 로테이션 시 새 kid로 자동 갱신.
     */
    @Cacheable(value = CACHE_NAME, key = "#kid")
    public RSAPublicKey fetchPublicKey(String kid) {
        String jwksUri = keycloakProperties.getBaseUrl()
                + "/realms/" + keycloakProperties.getRealm()
                + "/protocol/openid-connect/certs";

        log.debug("[KeycloakJwksVerifier] JWKS 조회: {} kid={}", jwksUri, kid);

        try {
            String jwksJson = restTemplate.getForObject(jwksUri, String.class);
            JsonNode root   = objectMapper.readTree(jwksJson);
            JsonNode keys   = root.get("keys");

            if (keys == null || !keys.isArray()) {
                throw new IllegalStateException("JWKS 응답에 keys 배열 없음");
            }

            for (JsonNode key : keys) {
                String keyId  = key.path("kid").asText();
                String keyUse = key.path("use").asText();
                String kty    = key.path("kty").asText();

                if (kid.equals(keyId) && "sig".equals(keyUse) && "RSA".equals(kty)) {
                    String nStr = key.path("n").asText();
                    String eStr = key.path("e").asText();
                    return buildRsaPublicKey(nStr, eStr);
                }
            }

            throw new IllegalStateException("JWKS에서 kid 매칭 키 없음: " + kid);

        } catch (Exception e) {
            log.error("[KeycloakJwksVerifier] JWKS 조회/파싱 실패: kid={}", kid, e);
            throw new PlatformException(PlatformErrorCode.IDP_PROVIDER_UNAVAILABLE, "jwks",
                    "Keycloak JWKS 조회 실패: " + e.getMessage());
        }
    }

    // ── 내부 유틸 ─────────────────────────────────────────────────────────

    /**
     * JWT 헤더에서 kid 추출 (Base64URL 디코딩)
     */
    private String extractKid(String jwt) {
        try {
            String[] parts  = jwt.split("\\.");
            String   header = new String(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode node   = objectMapper.readTree(header);
            String   kid    = node.path("kid").asText();
            if (kid.isBlank()) throw new IllegalArgumentException("kid 클레임 없음");
            return kid;
        } catch (Exception e) {
            throw new IllegalArgumentException("JWT 헤더 파싱 실패: " + e.getMessage());
        }
    }

    /**
     * JWKS n, e 값 → RSAPublicKey 변환
     */
    private RSAPublicKey buildRsaPublicKey(String n, String e) throws Exception {
        BigInteger modulus  = new BigInteger(1, Base64.getUrlDecoder().decode(n));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(e));
        RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
    }
}

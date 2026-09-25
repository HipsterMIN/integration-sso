package io.github.hipstermin.idem.gate.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.gate.keycloak.dto.KeycloakIdTokenClaims;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Keycloak JWKS RS256 서명 검증기
 *
 * <p>Keycloak JWKS 엔드포인트({@code /realms/{realm}/protocol/openid-connect/certs})에서
 * RSA 공개키를 조회하여 ID Token 의 RS256 서명을 검증한다.
 *
 * <p><b>금지 사항</b>: 카카오(kauth.kakao.com), 네이버 등 외부 IdP JWKS 를 직접 호출하지 않는다.
 * Keycloak 이 소셜 IdP 와의 토큰 교환을 모두 수행하고, q-sign 은 Keycloak JWKS 만 사용한다.
 *
 * <p>JWKS 공개키는 {@code @Cacheable(value="keycloakJwks")} 로 Redis 에 TTL 3600초 캐싱된다.
 * (QSignWebConfig 의 CacheManager 설정에 의해 관리)
 *
 * <p>Java 표준 라이브러리만 사용 — 추가 JWT 라이브러리 의존성 없음:
 * {@code CryptoProvider.verify}/{@code rsaPublicKey}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeycloakJwksVerifier {

    private static final String ALG_RS256 = "RS256";

    private final RestTemplate        restTemplate;
    private final ObjectMapper        objectMapper;
    private final KeycloakProperties  keycloakProperties;

    /**
     * Keycloak ID Token JWT 의 RS256 서명을 검증하고 Claims 를 반환한다.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>JWT header 파싱 → kid, alg 추출</li>
     *   <li>alg 검증: RS256 만 허용</li>
     *   <li>Keycloak JWKS 에서 kid 에 해당하는 RSA 공개키 조회 (@Cacheable)</li>
     *   <li>SHA256withRSA 서명 검증</li>
     *   <li>payload 역직렬화 → KeycloakIdTokenClaims 반환</li>
     * </ol>
     *
     * @param idToken      Keycloak 이 발급한 ID Token (JWT)
     * @param correlationId 로깅 및 예외 컨텍스트용 흐름 추적 ID
     * @return 서명 검증이 완료된 Claims
     * @throws PlatformException IDP_SIGNATURE_MISMATCH — 서명 검증 실패 또는 kid 미발견
     * @throws PlatformException IDP_RESPONSE_INVALID   — JWT 형식 오류
     */
    public KeycloakIdTokenClaims verify(String idToken, String correlationId) {
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) {
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                    "Keycloak ID Token 형식 오류 — JWT 3-part 구조가 아님");
        }

        try {
            // ── 1. header 파싱 → kid, alg 추출 ─────────────────────────────
            String   headerJson = new String(
                    Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            JsonNode header     = objectMapper.readTree(headerJson);
            String   kid        = header.path("kid").asText();
            String   alg        = header.path("alg").asText(ALG_RS256);

            if (!ALG_RS256.equalsIgnoreCase(alg)) {
                throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, correlationId,
                        "지원하지 않는 JWT 알고리즘: " + alg);
            }

            // ── 2. Keycloak JWKS 에서 RSA 공개키 조회 (캐시 적용) ──────────
            PublicKey publicKey = fetchPublicKey(kid, correlationId);

            // ── 3. SHA256withRSA 서명 검증 ───────────────────────────────────
            byte[] signingInput    = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8);
            byte[] signatureBytes  = Base64.getUrlDecoder().decode(parts[2]);

            if (!CryptoProviders.current().verify("SHA256withRSA", publicKey, signingInput, signatureBytes)) {
                throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, correlationId,
                        "Keycloak ID Token RS256 서명 검증 실패");
            }

            // ── 4. payload 파싱 → Claims 반환 ───────────────────────────────
            String payloadJson = new String(
                    Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            KeycloakIdTokenClaims claims =
                    objectMapper.readValue(payloadJson, KeycloakIdTokenClaims.class);

            log.debug("[KeycloakJwksVerifier] 서명 검증 성공: kid={} identityProvider={}",
                    kid, claims.getIdentityProvider());
            return claims;

        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("[KeycloakJwksVerifier] ID Token 검증 예외: correlationId={}", correlationId, e);
            throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, correlationId, e);
        }
    }

    /**
     * Keycloak JWKS 에서 {@code kid} 에 해당하는 RSA PublicKey 를 조회한다.
     *
     * <p>캐시 키: {@code keycloakJwks::{kid}}
     * TTL: QSignWebConfig 의 CacheManager 설정(keycloakJwks 항목, 기본 3600초)
     *
     * <p><b>호출 대상</b>: Keycloak JWKS URI 만 허용.
     * 카카오·네이버 등 외부 IdP JWKS 직접 호출 금지.
     *
     * @param kid          JWT header 의 kid 값
     * @param correlationId 로깅용
     * @return RSA PublicKey
     */
    /** D3: 명시적 TTL 캐시 — 종전 {@code @Cacheable} 은 같은 클래스 안 자기호출이라 프록시를 타지 않아 매 검증마다 JWKS 를 읽었다. */
    private final java.util.concurrent.ConcurrentHashMap<String, CachedKey> keyCache = new java.util.concurrent.ConcurrentHashMap<>();
    private record CachedKey(PublicKey key, long expiresAtMillis) {}

    @org.springframework.beans.factory.annotation.Value("${idem.gate.keycloak.jwks-cache-ttl-seconds:3600}")
    private long jwksCacheTtlSeconds = 3600;

    public PublicKey fetchPublicKey(String kid, String correlationId) {
        CachedKey cached = keyCache.get(kid);
        if (cached != null && cached.expiresAtMillis() > System.currentTimeMillis()) return cached.key();
        PublicKey fresh = loadPublicKey(kid, correlationId);
        keyCache.put(kid, new CachedKey(fresh, System.currentTimeMillis() + jwksCacheTtlSeconds * 1000L));
        return fresh;
    }

    /** 캐시 항목 수 (테스트·진단용). */
    public int cachedKeyCount() { return keyCache.size(); }

    private PublicKey loadPublicKey(String kid, String correlationId) {
        String jwksUri = keycloakProperties.jwksUri();
        log.debug("[KeycloakJwksVerifier] JWKS 조회: uri={} kid={}", jwksUri, kid);

        try {
            String   jwksJson = restTemplate.getForObject(jwksUri, String.class);
            JsonNode jwks     = objectMapper.readTree(jwksJson);
            JsonNode keys     = jwks.path("keys");

            for (JsonNode key : keys) {
                if (kid.equals(key.path("kid").asText())) {
                    // RSA n (modulus), e (exponent) 추출
                    byte[]     nBytes = Base64.getUrlDecoder().decode(key.path("n").asText());
                    byte[]     eBytes = Base64.getUrlDecoder().decode(key.path("e").asText());
                    BigInteger n      = new BigInteger(1, nBytes);
                    BigInteger e      = new BigInteger(1, eBytes);

                    PublicKey  pk = CryptoProviders.current().rsaPublicKey(n, e);
                    log.debug("[KeycloakJwksVerifier] 공개키 로드 성공: kid={}", kid);
                    return pk;
                }
            }

            throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, correlationId,
                    "Keycloak JWKS 에서 kid 를 찾을 수 없음: " + kid);

        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("[KeycloakJwksVerifier] JWKS 조회 실패: uri={}", jwksUri, e);
            throw new PlatformException(PlatformErrorCode.IDP_PROVIDER_UNAVAILABLE, correlationId, e);
        }
    }
}

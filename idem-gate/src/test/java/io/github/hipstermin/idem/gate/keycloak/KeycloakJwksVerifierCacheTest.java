package io.github.hipstermin.idem.gate.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/** D3: JWKS 공개키는 TTL 캐시 — 종전 {@code @Cacheable} 은 캐시 매니저가 없어 검증마다 Keycloak 을 호출했다. */
@ExtendWith(MockitoExtension.class)
@DisplayName("gate KeycloakJwksVerifier — JWKS TTL 캐시")
class KeycloakJwksVerifierCacheTest {

    @Mock RestTemplate rest;

    static String jwks(RSAPublicKey key, String kid) {
        return "{\"keys\":[{\"kid\":\"" + kid + "\",\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
                + b64(key.getModulus()) + "\",\"e\":\"" + b64(key.getPublicExponent()) + "\"}]}";
    }

    static String b64(BigInteger i) {
        byte[] b = i.toByteArray();
        if (b[0] == 0) { byte[] t = new byte[b.length - 1]; System.arraycopy(b, 1, t, 0, t.length); b = t; }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @Test
    @DisplayName("같은 kid 두 번 → JWKS 는 한 번만 조회, TTL 0 이면 다시 조회")
    void fetchesOnceWithinTtl() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048);
        RSAPublicKey pub = (RSAPublicKey) g.generateKeyPair().getPublic();
        KeycloakProperties props = new KeycloakProperties();
        props.setBaseUrl("http://kc"); props.setRealm("onepass");
        given(rest.getForObject(eq("http://kc/realms/onepass/protocol/openid-connect/certs"), eq(String.class))).willReturn(jwks(pub, "k1"));
        KeycloakJwksVerifier sut = new KeycloakJwksVerifier(rest, new ObjectMapper(), props);

        PublicKey a = sut.fetchPublicKey("k1", "cid");
        PublicKey b = sut.fetchPublicKey("k1", "cid");
        assertThat(a).isEqualTo(pub).isSameAs(b);
        verify(rest, times(1)).getForObject(anyString(), eq(String.class));
        assertThat(sut.cachedKeyCount()).isEqualTo(1);


        // TTL 0 인 새 검증기: 캐시가 즉시 만료돼 매번 다시 조회한다
        KeycloakJwksVerifier noCache = new KeycloakJwksVerifier(rest, new ObjectMapper(), props);
        ReflectionTestUtils.setField(noCache, "jwksCacheTtlSeconds", 0L);
        noCache.fetchPublicKey("k1", "cid");
        noCache.fetchPublicKey("k1", "cid");
        verify(rest, times(3)).getForObject(anyString(), eq(String.class));
    }
}

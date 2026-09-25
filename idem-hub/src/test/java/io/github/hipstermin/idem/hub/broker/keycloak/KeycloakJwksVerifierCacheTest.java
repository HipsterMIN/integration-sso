package io.github.hipstermin.idem.hub.broker.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

/** D3: hub 쪽 JWKS 도 TTL 캐시 — 종전 {@code @Cacheable} 은 캐시 매니저 없이 매번 Keycloak 을 호출했다. */
@ExtendWith(MockitoExtension.class)
@DisplayName("hub KeycloakJwksVerifier — JWKS TTL 캐시")
class KeycloakJwksVerifierCacheTest {

    @Mock RestTemplate rest;

    static String b64(BigInteger i) {
        byte[] b = i.toByteArray();
        if (b[0] == 0) { byte[] t = new byte[b.length - 1]; System.arraycopy(b, 1, t, 0, t.length); b = t; }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @Test
    void fetchesOnceWithinTtl() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048);
        RSAPublicKey pub = (RSAPublicKey) g.generateKeyPair().getPublic();
        KeycloakProperties props = new KeycloakProperties();
        props.setBaseUrl("http://kc"); props.setRealm("idem");
        String jwks = "{\"keys\":[{\"kid\":\"k1\",\"kty\":\"RSA\",\"use\":\"sig\",\"n\":\"" + b64(pub.getModulus())
                + "\",\"e\":\"" + b64(pub.getPublicExponent()) + "\"}]}";
        given(rest.getForObject(eq("http://kc/realms/idem/protocol/openid-connect/certs"), eq(String.class))).willReturn(jwks);
        KeycloakJwksVerifier sut = new KeycloakJwksVerifier(props, rest, new ObjectMapper());

        RSAPublicKey a = sut.fetchPublicKey("k1");
        RSAPublicKey b = sut.fetchPublicKey("k1");
        assertThat(a.getModulus()).isEqualTo(pub.getModulus());
        assertThat(a).isSameAs(b);
        verify(rest, times(1)).getForObject(anyString(), eq(String.class));
        assertThat(sut.cachedKeyCount()).isEqualTo(1);
    }
}

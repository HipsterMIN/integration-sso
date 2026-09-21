package io.github.hipstermin.idem.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hipstermin.idem.common.crypto.jca.JcaCryptoProvider;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** D2-b: JCA 구현이 코어가 기대하는 규약(포맷·길이·라운드트립)을 지키는지. 이관 전 코드와 같은 값을 내야 한다. */
class JcaCryptoProviderTest {

    private final JcaCryptoProvider sut = new JcaCryptoProvider();
    private final byte[] key = sut.randomBytes(32);

    @Test
    @DisplayName("sha256Hex — 알려진 벡터(RFC 6234 'abc'), 소문자 hex 64자")
    void sha256Hex_knownVector() {
        assertThat(sut.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(sut.sha256Base64Url("abc".getBytes(StandardCharsets.US_ASCII))).doesNotContain("=").hasSize(43);
    }

    @Test
    @DisplayName("hmacSha256Hex — RFC 4231 test case 2 벡터")
    void hmac_knownVector() {
        assertThat(sut.hmacSha256Hex("Jefe".getBytes(StandardCharsets.US_ASCII), "what do ya want for nothing?"))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
        assertThat(sut.hmacSha256Base64Url(key, "m")).doesNotContain("=").doesNotContain("+").doesNotContain("/");
    }

    @Test
    @DisplayName("AES-GCM 라운드트립 — AAD 바인딩, 변조 시 실패")
    void aesGcm_roundTrip_andTamper() {
        byte[] iv = sut.randomBytes(12);
        byte[] ct = sut.aesGcmEncrypt(key, iv, "hello".getBytes(StandardCharsets.UTF_8), "ticket-1".getBytes());
        assertThat(ct).hasSize(5 + 16); // ct || 128-bit tag
        assertThat(sut.aesGcmDecrypt(key, iv, ct, "ticket-1".getBytes())).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> sut.aesGcmDecrypt(key, iv, ct, "ticket-2".getBytes())).isInstanceOf(CryptoException.class);
        ct[0] ^= 1;
        assertThatThrownBy(() -> sut.aesGcmDecrypt(key, iv, ct, "ticket-1".getBytes())).isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("AES-CBC 라운드트립 (레거시)")
    void aesCbc_roundTrip() {
        byte[] iv = sut.randomBytes(16);
        byte[] ct = sut.aesCbcEncrypt(key, iv, "hello world!".getBytes(StandardCharsets.UTF_8));
        assertThat(ct.length % 16).isZero();
        assertThat(sut.aesCbcDecrypt(key, iv, ct)).isEqualTo("hello world!".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("PBKDF2-HMAC-SHA256 — 같은 입력이면 같은 키, 다른 salt 면 다른 키")
    void pbkdf2() {
        byte[] salt = sut.randomBytes(16);
        char[] pw = "secret".toCharArray();
        byte[] a = sut.pbkdf2HmacSha256(pw, salt, 1000, 256);
        assertThat(a).hasSize(32);
        assertThat(sut.pbkdf2HmacSha256("secret".toCharArray(), salt, 1000, 256)).isEqualTo(a);
        assertThat(sut.pbkdf2HmacSha256("secret".toCharArray(), sut.randomBytes(16), 1000, 256)).isNotEqualTo(a);
    }

    @Test
    @DisplayName("난수 — 길이·인코딩·범위")
    void random() {
        assertThat(sut.randomBytes(32)).hasSize(32);
        assertThat(sut.randomToken(32)).hasSize(43).doesNotContain("=");
        assertThat(sut.randomHex(16)).hasSize(32).matches("[0-9a-f]+");
        for (int i = 0; i < 100; i++) assertThat(sut.randomInt(3)).isBetween(0, 2);
        assertThat(sut.randomHex(16)).isNotEqualTo(sut.randomHex(16));
    }

    @Test
    @DisplayName("constantTimeEquals — null 은 false, 길이 달라도 예외 없음")
    void constantTimeEquals() {
        assertThat(sut.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(sut.constantTimeEquals("abc", "abd")).isFalse();
        assertThat(sut.constantTimeEquals("abc", "ab")).isFalse();
        assertThat(sut.constantTimeEquals((String) null, "a")).isFalse();
        assertThat(sut.constantTimeEquals(new byte[] {1}, null)).isFalse();
    }

    @Test
    @DisplayName("Ed25519 키 생성·인코딩 왕복·서명 검증, RSA JWK(n,e) 공개키")
    void asymmetric() {
        KeyPair kp = sut.generateKeyPair("Ed25519");
        var priv = sut.decodePrivateKey("Ed25519", kp.getPrivate().getEncoded());
        var pub  = sut.decodePublicKey("Ed25519", kp.getPublic().getEncoded());
        byte[] sig = sut.sign("Ed25519", priv, "data".getBytes());
        assertThat(sut.verify("Ed25519", pub, "data".getBytes(), sig)).isTrue();
        assertThat(sut.verify("Ed25519", pub, "datA".getBytes(), sig)).isFalse();
        assertThat(sut.verify("Ed25519", pub, "data".getBytes(), new byte[3])).isFalse(); // 형식 오류도 false

        KeyPair rsa = sut.generateKeyPair("RSA");
        RSAPublicKey rpk = (RSAPublicKey) rsa.getPublic();
        var rebuilt = sut.rsaPublicKey(rpk.getModulus(), rpk.getPublicExponent());
        byte[] rsig = sut.sign("SHA256withRSA", rsa.getPrivate(), "jwt".getBytes());
        assertThat(sut.verify("SHA256withRSA", rebuilt, "jwt".getBytes(), rsig)).isTrue();
        assertThatThrownBy(() -> sut.rsaPublicKey(BigInteger.ZERO, BigInteger.ZERO)).isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("CryptoProviders — 기본은 JCA, install 로 교체된다")
    void providers() {
        assertThat(CryptoProviders.current().providerName()).isEqualTo("jca");
        CryptoProvider custom = new JcaCryptoProvider() {
            @Override public String providerName() { return "kcmvp:test"; }
        };
        CryptoProviders.install(custom);
        try {
            assertThat(CryptoProviders.current().providerName()).isEqualTo("kcmvp:test");
        } finally {
            CryptoProviders.install(new JcaCryptoProvider());
        }
    }
}

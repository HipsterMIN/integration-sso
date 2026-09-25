package io.github.hipstermin.idem.common.crypto;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * 암호 연산 SPI (범용화 D2-b, {@code docs/generalization-plan.md} D2 · {@code docs/execution-plan.md} P2).
 *
 * <p>코어 모듈(idem-common·hub·gate·registry·authz)의 모든 해시·MAC·대칭 암복호·난수·서명 연산은 이 인터페이스를 거친다.
 * {@code javax.crypto}·{@code java.security.MessageDigest/SecureRandom/Signature/KeyFactory} 직접 호출은
 * {@code CryptoBoundaryGuardTest} 가 막는다. 기본 구현은 JCA({@link io.github.hipstermin.idem.common.crypto.jca.JcaCryptoProvider})이며,
 * CC 인증(P2)에서는 KCMVP 검증필 모듈 어댑터로 교체한다 — 호출부는 바뀌지 않는다.
 *
 * <p><b>키 보관은 이 SPI 의 책임이 아니다.</b> 키 바이트는 호출부가 {@code KmsClient}·{@code KeyVersionRegistry}·설정에서 가져온다.
 * 암호문 프레이밍(IV 결합, 버전 접두)도 호출부가 유지한다 — 저장된 데이터 포맷을 바꾸지 않기 위함이다.
 *
 * <p>구현체는 스레드 안전해야 한다.
 */
public interface CryptoProvider {

    /** 구현 식별자 ("jca", 향후 "kcmvp:&lt;module&gt;"). */
    String providerName();

    // ── 해시 ────────────────────────────────────────────────────────────────

    byte[] sha256(byte[] input);

    /** SHA-256(UTF-8) → 소문자 hex 64자. 코어의 identifierHash 규약. */
    String sha256Hex(String input);

    /** SHA-256 → Base64URL(패딩 없음). PKCE S256 등. */
    String sha256Base64Url(byte[] input);

    // ── MAC ─────────────────────────────────────────────────────────────────

    byte[] hmacSha256(byte[] key, byte[] message);

    /**
     * HMAC-SHA1 — RFC 6238 TOTP(인증 앱 호환) 전용. SHA-1 은 HMAC 키 유도 용도로만 허용되며 다른 서명·해시에는 쓰지 않는다.
     */
    byte[] hmacSha1(byte[] key, byte[] message);

    /** HMAC-SHA256(UTF-8 메시지) → 소문자 hex. */
    String hmacSha256Hex(byte[] key, String message);

    /** HMAC-SHA256(UTF-8 메시지) → Base64URL(패딩 없음). */
    String hmacSha256Base64Url(byte[] key, String message);

    // ── 대칭 암복호 ──────────────────────────────────────────────────────────

    /**
     * AES-GCM (태그 128비트). 반환값은 {@code ciphertext || tag}. IV 는 호출부가 {@link #randomBytes(int)} 로 만든다(보통 12바이트).
     *
     * @param aad 추가 인증 데이터, 없으면 {@code null}
     */
    byte[] aesGcmEncrypt(byte[] key, byte[] iv, byte[] plaintext, byte[] aad);

    /** @param ciphertextAndTag {@code ciphertext || tag} */
    byte[] aesGcmDecrypt(byte[] key, byte[] iv, byte[] ciphertextAndTag, byte[] aad);

    /** AES-CBC/PKCS5Padding — 인증 태그 없는 레거시 경로(Q-IM 공유키 CBC). 새 코드는 GCM 을 쓴다. */
    byte[] aesCbcEncrypt(byte[] key, byte[] iv, byte[] plaintext);

    byte[] aesCbcDecrypt(byte[] key, byte[] iv, byte[] ciphertext);

    // ── KDF ─────────────────────────────────────────────────────────────────

    /**
     * PBKDF2-HMAC-SHA256. 구현은 내부 복사본만 지운다 — {@code secret} 배열의 소유·소거는 호출자 책임이다.
     */
    byte[] pbkdf2HmacSha256(char[] secret, byte[] salt, int iterations, int keyLengthBits);

    // ── 난수 (CSPRNG) ────────────────────────────────────────────────────────

    byte[] randomBytes(int length);

    /** {@code length} 바이트 난수 → Base64URL(패딩 없음). 세션 ID·API Key·PKCE verifier. */
    String randomToken(int length);

    /** {@code length} 바이트 난수 → 소문자 hex. OAuth state·nonce. */
    String randomHex(int length);

    /** {@code [0, boundExclusive)} 균등 난수. */
    int randomInt(int boundExclusive);

    // ── 비교 ────────────────────────────────────────────────────────────────

    /** 상수 시간 비교. {@code null} 은 항상 {@code false}. */
    boolean constantTimeEquals(byte[] a, byte[] b);

    /** UTF-8 바이트 상수 시간 비교. */
    boolean constantTimeEquals(String a, String b);

    // ── 비대칭 키·서명 ───────────────────────────────────────────────────────

    /** @param algorithm "Ed25519" | "RSA" 등 JCA 이름 */
    KeyPair generateKeyPair(String algorithm);

    PrivateKey decodePrivateKey(String algorithm, byte[] pkcs8Der);

    PublicKey decodePublicKey(String algorithm, byte[] x509Der);

    /** JWK(n, e) → RSA 공개키. */
    PublicKey rsaPublicKey(BigInteger modulus, BigInteger exponent);

    /** @param algorithm "SHA256withRSA" | "Ed25519" 등 JCA Signature 이름 */
    byte[] sign(String algorithm, PrivateKey key, byte[] data);

    boolean verify(String algorithm, PublicKey key, byte[] data, byte[] signature);
}

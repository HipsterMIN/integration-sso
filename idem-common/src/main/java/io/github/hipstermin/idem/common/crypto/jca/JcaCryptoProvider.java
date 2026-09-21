package io.github.hipstermin.idem.common.crypto.jca;

import io.github.hipstermin.idem.common.crypto.CryptoException;
import io.github.hipstermin.idem.common.crypto.CryptoProvider;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * JCA(JDK 기본 프로바이더) 기반 {@link CryptoProvider}. 코어에서 JCA 를 직접 부르는 유일한 곳이다
 * ({@code CryptoBoundaryGuardTest} 허용 목록).
 */
public class JcaCryptoProvider implements CryptoProvider {

    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    public String providerName() {
        return "jca";
    }

    // ── 해시 ──

    @Override
    public byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("SHA-256 unavailable", e);
        }
    }

    @Override
    public String sha256Hex(String input) {
        return HexFormat.of().formatHex(sha256(input.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public String sha256Base64Url(byte[] input) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(input));
    }

    // ── MAC ──

    @Override
    public byte[] hmacSha256(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new CryptoException("HMAC-SHA256 failed", e);
        }
    }

    @Override
    public String hmacSha256Hex(byte[] key, String message) {
        return HexFormat.of().formatHex(hmacSha256(key, message.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public String hmacSha256Base64Url(byte[] key, String message) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmacSha256(key, message.getBytes(StandardCharsets.UTF_8)));
    }

    // ── 대칭 ──

    @Override
    public byte[] aesGcmEncrypt(byte[] key, byte[] iv, byte[] plaintext, byte[] aad) {
        return aesGcm(Cipher.ENCRYPT_MODE, key, iv, plaintext, aad);
    }

    @Override
    public byte[] aesGcmDecrypt(byte[] key, byte[] iv, byte[] ciphertextAndTag, byte[] aad) {
        return aesGcm(Cipher.DECRYPT_MODE, key, iv, ciphertextAndTag, aad);
    }

    private static byte[] aesGcm(int mode, byte[] key, byte[] iv, byte[] input, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(input);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new CryptoException("AES-GCM " + (mode == Cipher.ENCRYPT_MODE ? "encrypt" : "decrypt") + " failed", e);
        }
    }

    @Override
    public byte[] aesCbcEncrypt(byte[] key, byte[] iv, byte[] plaintext) {
        return aesCbc(Cipher.ENCRYPT_MODE, key, iv, plaintext);
    }

    @Override
    public byte[] aesCbcDecrypt(byte[] key, byte[] iv, byte[] ciphertext) {
        return aesCbc(Cipher.DECRYPT_MODE, key, iv, ciphertext);
    }

    private static byte[] aesCbc(int mode, byte[] key, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new CryptoException("AES-CBC " + (mode == Cipher.ENCRYPT_MODE ? "encrypt" : "decrypt") + " failed", e);
        }
    }

    // ── KDF ──

    @Override
    public byte[] pbkdf2HmacSha256(char[] secret, byte[] salt, int iterations, int keyLengthBits) {
        PBEKeySpec spec = new PBEKeySpec(secret, salt, iterations, keyLengthBits);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new CryptoException("PBKDF2WithHmacSHA256 failed", e);
        } finally {
            spec.clearPassword();
        }
    }

    // ── 난수 ──

    @Override
    public byte[] randomBytes(int length) {
        byte[] out = new byte[length];
        SECURE_RANDOM.nextBytes(out);
        return out;
    }

    @Override
    public String randomToken(int length) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(length));
    }

    @Override
    public String randomHex(int length) {
        return HexFormat.of().formatHex(randomBytes(length));
    }

    @Override
    public int randomInt(int boundExclusive) {
        return SECURE_RANDOM.nextInt(boundExclusive);
    }

    // ── 비교 ──

    @Override
    public boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a, b);
    }

    @Override
    public boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    // ── 비대칭 ──

    @Override
    public KeyPair generateKeyPair(String algorithm) {
        try {
            return KeyPairGenerator.getInstance(algorithm).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new CryptoException("KeyPairGenerator " + algorithm + " failed", e);
        }
    }

    @Override
    public PrivateKey decodePrivateKey(String algorithm, byte[] pkcs8Der) {
        try {
            return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(pkcs8Der));
        } catch (GeneralSecurityException e) {
            throw new CryptoException("private key decode (" + algorithm + ") failed", e);
        }
    }

    @Override
    public PublicKey decodePublicKey(String algorithm, byte[] x509Der) {
        try {
            return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(x509Der));
        } catch (GeneralSecurityException e) {
            throw new CryptoException("public key decode (" + algorithm + ") failed", e);
        }
    }

    @Override
    public PublicKey rsaPublicKey(BigInteger modulus, BigInteger exponent) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
        } catch (GeneralSecurityException e) {
            throw new CryptoException("RSA public key build failed", e);
        }
    }

    @Override
    public byte[] sign(String algorithm, PrivateKey key, byte[] data) {
        try {
            Signature sig = Signature.getInstance(algorithm);
            sig.initSign(key);
            sig.update(data);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new CryptoException("sign (" + algorithm + ") failed", e);
        }
    }

    @Override
    public boolean verify(String algorithm, PublicKey key, byte[] data, byte[] signature) {
        try {
            Signature sig = Signature.getInstance(algorithm);
            sig.initVerify(key);
            sig.update(data);
            return sig.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}

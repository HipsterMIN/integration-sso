package io.github.hipstermin.idem.hub.sso;

import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ed25519 (EdDSA) KeyPair 설정 — CAST 토큰 서명/검증용
 *
 * <h3>운영 환경 키 설정</h3>
 * K8s Secret (ExternalSecrets CRD)에서 주입:
 * <pre>
 *   ido:
 *     cast:
 *       private-key: &lt;Base64-DER PKCS8 private key&gt;
 *       public-key:  &lt;Base64-DER X.509 public key&gt;
 * </pre>
 *
 * <h3>로컬 개발 환경</h3>
 * application-local.yml 에 하드코딩 또는 CAST_PRIVATE_KEY, CAST_PUBLIC_KEY 환경변수 사용.
 * 키 생성 명령:
 * <pre>
 *   openssl genpkey -algorithm ED25519 -out cast_private.pem
 *   openssl pkey -in cast_private.pem -pubout -out cast_public.pem
 *   # DER Base64 변환:
 *   openssl pkey -in cast_private.pem -outform DER | base64 -w0 > cast_private.b64
 *   openssl pkey -in cast_public.pem  -pubin -outform DER | base64 -w0 > cast_public.b64
 * </pre>
 *
 * <h3>키 부재 시 동작</h3>
 * 개발 편의를 위해 설정값이 없으면 인메모리 임시 키페어를 자동 생성.
 * <b>운영 환경에서는 반드시 실제 키를 주입해야 함.</b>
 *
 * @see CastTokenServiceImpl
 */
@Slf4j
@Configuration
public class CastKeyConfig {

    /** K8s Secret 또는 환경변수로 주입되는 Ed25519 PKCS8 DER Base64 개인키 */
    @Value("${ido.cast.private-key:}")
    private String privateKeyBase64;

    /** K8s Secret 또는 환경변수로 주입되는 Ed25519 X.509 DER Base64 공개키 */
    @Value("${ido.cast.public-key:}")
    private String publicKeyBase64;

    /** D2 fail-secure: 키 미설정 시 임시 키페어 자동 생성은 로컬·테스트에서만 (기본 false → 부팅 실패) */
    @Value("${ido.cast.allow-generated-keys:false}")
    private boolean allowGeneratedKeys;

    /**
     * Ed25519 KeyPair 빈 생성
     *
     * <p>설정값이 존재하면 해당 값으로 KeyPair를 복원하고,
     * 없으면 개발용 임시 KeyPair를 자동 생성한다.
     *
     * @return Ed25519 KeyPair
     * @throws IllegalStateException 키 복원/생성 실패 시
     */
    @Bean(name = "castKeyPair")
    public KeyPair castKeyPair() {
        if (privateKeyBase64 != null && !privateKeyBase64.isBlank()
                && publicKeyBase64 != null && !publicKeyBase64.isBlank()) {
            return loadFromConfig();
        }
        if (!allowGeneratedKeys) {
            throw new IllegalStateException(
                "[CastKeyConfig] ido.cast.private-key / public-key 가 설정되지 않았습니다. 운영에서는 Ed25519 키를 주입하십시오 "
                    + "(docs/sso-im-operations-manual.md). 로컬·테스트에서만 ido.cast.allow-generated-keys=true 로 임시 키를 허용합니다.");
        }
        return generateDevKeyPair();
    }

    // ── 설정 파일에서 키 복원 ──────────────────────────────────────────────

    private KeyPair loadFromConfig() {
        try {
            byte[] privDer = Base64.getDecoder().decode(privateKeyBase64.strip());
            byte[] pubDer  = Base64.getDecoder().decode(publicKeyBase64.strip());

            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            PrivateKey privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privDer));
            PublicKey  publicKey  = kf.generatePublic(new X509EncodedKeySpec(pubDer));

            log.info("[CastKeyConfig] Ed25519 키 로드 완료 (운영 설정값)");
            return new KeyPair(publicKey, privateKey);

        } catch (Exception e) {
            throw new IllegalStateException(
                "[CastKeyConfig] Ed25519 키 로드 실패 — ido.cast.private-key / public-key 값을 확인하세요: "
                    + e.getMessage(), e);
        }
    }

    // ── 개발용 임시 키 생성 ────────────────────────────────────────────────

    private KeyPair generateDevKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
            KeyPair kp = kpg.generateKeyPair();
            log.warn("[CastKeyConfig] ⚠️  Ed25519 임시 키페어 생성됨 — 개발 전용! " +
                     "운영 환경에서는 ido.cast.private-key / public-key 를 반드시 설정하세요.");
            return kp;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                "[CastKeyConfig] Ed25519 알고리즘 미지원 (JDK 15+ 필요): " + e.getMessage(), e);
        }
    }
}

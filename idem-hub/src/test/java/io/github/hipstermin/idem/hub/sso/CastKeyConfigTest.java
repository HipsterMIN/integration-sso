package io.github.hipstermin.idem.hub.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import java.security.KeyPair;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 시험 항목 A-2 "필수 비밀 없이 기동" (G2-13, 2026-10-16 — 수동 → 자동).
 *
 * <p>{@code castKeyPair} 빈이 만들어지지 않으면 컨텍스트가 뜨지 않으므로 hub 는 기동을 거부하고 헬스도 없다.
 * 운영 프로파일에서 {@code idem.hub.cast.allow-generated-keys=true} 탈출구는 {@code FailSecureBootGuardTest} 가 막는다.
 */
@DisplayName("CastKeyConfig — Ed25519 키 없이 기동 거부 (A-2)")
class CastKeyConfigTest {

    private CastKeyConfig config(String priv, String pub, boolean allowGenerated) {
        CastKeyConfig c = new CastKeyConfig();
        ReflectionTestUtils.setField(c, "privateKeyBase64", priv);
        ReflectionTestUtils.setField(c, "publicKeyBase64", pub);
        ReflectionTestUtils.setField(c, "allowGeneratedKeys", allowGenerated);
        return c;
    }

    @Test
    @DisplayName("IDEM_HUB_CAST_PRIVATE_KEY 가 비어 있고 탈출구가 없으면 키 이름을 담은 예외로 기동 거부")
    void emptyKeys_withoutEscapeHatch_refusesToStart() {
        assertThatThrownBy(() -> config("", "", false).castKeyPair())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idem.hub.cast.private-key")
                .hasMessageContaining("allow-generated-keys");
    }

    @Test
    @DisplayName("공개키만 있고 개인키가 비어도 거부한다(둘 다 있어야 로드)")
    void onlyPublicKey_refusesToStart() {
        String pub = Base64.getEncoder().encodeToString(CryptoProviders.current().generateKeyPair("Ed25519").getPublic().getEncoded());
        assertThatThrownBy(() -> config("", pub, false).castKeyPair()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("깨진 키 값은 '키 로드 실패' 로 거부한다")
    void garbageKeys_refusesToStart() {
        assertThatThrownBy(() -> config("not-base64-der", "also-garbage", false).castKeyPair())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("키 로드 실패");
    }

    @Test
    @DisplayName("로컬 탈출구(allow-generated-keys=true)에서만 임시 키를 만든다")
    void escapeHatch_generatesDevKeys() {
        KeyPair kp = config("", "", true).castKeyPair();
        assertThat(kp.getPrivate()).isNotNull();
        assertThat(kp.getPublic().getAlgorithm()).containsIgnoringCase("Ed");
    }

    @Test
    @DisplayName("설정된 Ed25519 DER Base64 키는 그대로 복원한다")
    void configuredKeys_areLoaded() {
        KeyPair gen = CryptoProviders.current().generateKeyPair("Ed25519");
        String priv = Base64.getEncoder().encodeToString(gen.getPrivate().getEncoded());
        String pub  = Base64.getEncoder().encodeToString(gen.getPublic().getEncoded());
        KeyPair kp = config(priv, pub, false).castKeyPair();
        assertThat(kp.getPublic().getEncoded()).isEqualTo(gen.getPublic().getEncoded());
        assertThat(kp.getPrivate().getEncoded()).isEqualTo(gen.getPrivate().getEncoded());
    }
}

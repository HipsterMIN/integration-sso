package io.github.hipstermin.idem.hub.auth.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AuthCredentialsValidator — 본인인증 자격증명 부팅 검증")
class AuthCredentialsValidatorTest {

    private AuthProperties props(String niceId, String niceSecret, String oacxPath) {
        return new AuthProperties(
                new AuthProperties.Nice(niceId, niceSecret, "http://localhost/cb", 10),
                new AuthProperties.Oacx(oacxPath, false),
                new AuthProperties.Integration("http://intg", 10));
    }

    @Test
    @DisplayName("모든 자격증명 설정 → 통과")
    void allPresent_ok() {
        assertThatCode(() -> AuthCredentialsValidator.validate(
                props("nice-id", "nice-secret", "/app/oacx.json"), false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NICE 자격증명 비어 있음 → 기동 차단(IllegalStateException)")
    void missingNice_throws() {
        assertThatThrownBy(() -> AuthCredentialsValidator.validate(
                props("", "", "/app/oacx.json"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NICE_CLIENT_ID")
                .hasMessageContaining("NICE_CLIENT_SECRET");
    }

    @Test
    @DisplayName("OACX provider-key 비어 있음 → 기동 차단")
    void missingOacx_throws() {
        assertThatThrownBy(() -> AuthCredentialsValidator.validate(
                props("nice-id", "nice-secret", ""), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OACX_PROVIDER_KEY_PATH");
    }

    @Test
    @DisplayName("미설정이어도 allow-missing-credentials=true → 기동 계속")
    void missing_butAllowed_ok() {
        assertThatCode(() -> AuthCredentialsValidator.validate(
                props("", "", ""), true))
                .doesNotThrowAnyException();
    }
}

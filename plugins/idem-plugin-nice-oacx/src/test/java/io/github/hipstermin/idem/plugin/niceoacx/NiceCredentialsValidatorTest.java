package io.github.hipstermin.idem.plugin.niceoacx;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NiceCredentialsValidator — prod 부팅 자격증명 검증 (S5a)")
class NiceCredentialsValidatorTest {

    private static NiceProperties nice(String id, String secret) {
        NiceProperties p = new NiceProperties();
        p.setClientId(id); p.setClientSecret(secret);
        return p;
    }

    private static OacxProperties oacx(String path) {
        OacxProperties p = new OacxProperties();
        p.setProviderKeyPath(path);
        return p;
    }

    @Test
    void allSet_passes() {
        assertThatCode(() -> NiceCredentialsValidator.validate(nice("id", "sec"), oacx("/k.json"), true, false)).doesNotThrowAnyException();
    }

    @Test
    void missingNice_blocksBoot() {
        assertThatThrownBy(() -> NiceCredentialsValidator.validate(nice("", ""), oacx("/k.json"), false, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("NICE_CLIENT_ID").hasMessageContaining("NICE_CLIENT_SECRET");
    }

    @Test
    void oacxOnlyCheckedWhenSdkPresent() {
        assertThatCode(() -> NiceCredentialsValidator.validate(nice("id", "sec"), oacx(""), false, false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> NiceCredentialsValidator.validate(nice("id", "sec"), oacx(""), true, false))
                .hasMessageContaining("OACX_PROVIDER_KEY_PATH");
    }

    @Test
    void allowMissing_continues() {
        assertThatCode(() -> NiceCredentialsValidator.validate(nice("", ""), oacx(""), true, true)).doesNotThrowAnyException();
    }
}

package io.github.hipstermin.idem.hub.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** D2: 탈출구(escape hatch) 는 로컬·테스트 전용 — 운영·스테이지에서는 기동 거부. */
class FailSecureBootGuardTest {

    private static MockEnvironment env(String... profiles) {
        MockEnvironment e = new MockEnvironment();
        e.setActiveProfiles(profiles);
        e.setProperty("idem.hub.gate.internal-sig-secret", "0123456789abcdef0123456789abcdef");
        return e;
    }

    @Test
    @DisplayName("내부 서명 비밀키가 비면 어떤 프로파일이든 기동 거부 (allow-empty-sig-secret 없이)")
    void emptySigSecret_rejected() {
        MockEnvironment e = new MockEnvironment();
        assertThatThrownBy(() -> new FailSecureBootGuard(e).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDEM_HUB_INTERNAL_SIG_SECRET");

        e.setProperty("idem.hub.internal.allow-empty-sig-secret", "true");
        assertThatCode(() -> new FailSecureBootGuard(e).verify()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod 에서 탈출구가 하나라도 true 면 기동 거부 — 위반 항목을 전부 나열")
    void prod_escapeHatchesRejected() {
        MockEnvironment e = env("prod");
        e.setProperty("idem.plugins.mock-auth.enabled", "true");
        e.setProperty("idem.hub.internal.allow-empty-callers", "true");
        e.setProperty("idem.hub.audit.db-save-enabled", "false");

        assertThatThrownBy(() -> new FailSecureBootGuard(e).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3건")
                .hasMessageContaining("idem.plugins.mock-auth.enabled")
                .hasMessageContaining("idem.hub.internal.allow-empty-callers")
                .hasMessageContaining("idem.hub.audit.db-save-enabled");
    }

    @Test
    @DisplayName("stage 도 prod 와 같이 강화 프로파일이다")
    void stage_isHardened() {
        MockEnvironment e = env("stage");
        e.setProperty("idem.hub.cast.allow-generated-keys", "true");
        assertThatThrownBy(() -> new FailSecureBootGuard(e).verify()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("local 에서는 탈출구가 허용된다 (비밀키만 있으면 통과)")
    void local_allowsEscapeHatches() {
        MockEnvironment e = env("local");
        e.setProperty("idem.plugins.mock-auth.enabled", "true");
        e.setProperty("idem.hub.registry.allow-empty-aes-key", "true");
        assertThatCode(() -> new FailSecureBootGuard(e).verify()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod 에서 탈출구가 모두 꺼져 있으면 통과")
    void prod_clean() {
        assertThatCode(() -> new FailSecureBootGuard(env("prod")).verify()).doesNotThrowAnyException();
    }
}

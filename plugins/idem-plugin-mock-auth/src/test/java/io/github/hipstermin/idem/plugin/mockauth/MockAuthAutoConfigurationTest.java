package io.github.hipstermin.idem.plugin.mockauth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MockAuthAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MockAuthAutoConfiguration.class));

    @Test
    @DisplayName("idem.plugins.mock-auth.enabled=true 일 때만 MOCK 제공자 빈이 등록된다")
    void enabledByProperty() {
        runner.withPropertyValues("idem.plugins.mock-auth.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(IdentityVerificationProvider.class);
            assertThat(ctx.getBean(IdentityVerificationProvider.class).code()).isEqualTo("MOCK");
        });
    }

    @Test
    @DisplayName("기본값(미설정)·false 에서는 빈이 없다 — 운영 프로파일 안전장치")
    void disabledByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(IdentityVerificationProvider.class));
        runner.withPropertyValues("idem.plugins.mock-auth.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(IdentityVerificationProvider.class));
    }
}

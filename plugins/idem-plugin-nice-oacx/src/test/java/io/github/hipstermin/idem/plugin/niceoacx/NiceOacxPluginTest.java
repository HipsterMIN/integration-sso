package io.github.hipstermin.idem.plugin.niceoacx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationException;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationProvider;
import io.github.hipstermin.idem.common.spi.identity.VerificationCallback;
import io.github.hipstermin.idem.common.spi.identity.VerificationRequest;
import io.github.hipstermin.idem.common.spi.identity.VerificationStart;
import io.github.hipstermin.idem.common.spi.identity.VerifiedIdentity;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class NiceOacxPluginTest {

    static class FakeGateway implements NicePhoneGateway {
        @Override public Started start(String returnUrl) { return new Started("REQ-1", "https://nice/auth?r=" + returnUrl); }
        @Override public Result result(String webTransactionId, String requestNo) {
            return new Result("CI-1", "DI-1", "홍길동", "19900101", "1", "0", "01012345678", "1");
        }
    }

    @Configuration
    static class GatewayConfig {
        @Bean NicePhoneGateway nicePhoneGateway() { return new FakeGateway(); }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NiceOacxAutoConfiguration.class));

    @Test
    @DisplayName("기본값(미설정)에서는 아무 제공자도 등록되지 않는다")
    void disabledByDefault() {
        runner.withUserConfiguration(GatewayConfig.class)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(IdentityVerificationProvider.class));
    }

    private static boolean oacxSdkPresent() {
        try { Class.forName("OACX.OacxUtil"); return true; } catch (ClassNotFoundException e) { return false; }
    }

    @Test
    @DisplayName("enabled=true + 게이트웨이 빈 → NICE_PHONE 제공자(L2, EzAuth 위젯) 등록; OACX 제공자는 SDK 가 클래스패스에 있을 때만")
    void enabledWithGateway() {
        runner.withUserConfiguration(GatewayConfig.class)
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.databind.ObjectMapper::new)
                .withPropertyValues("idem.plugins.nice-oacx.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasBean("nicePhoneIdentityVerificationProvider");
                    IdentityVerificationProvider p = ctx.getBean("nicePhoneIdentityVerificationProvider", IdentityVerificationProvider.class);
                    assertThat(p.code()).isEqualTo("NICE_PHONE");
                    assertThat(p.level()).isEqualTo(AuthResult.AuthLevel.L2);
                    assertThat(p.widget()).isPresent();
                    assertThat(p.widget().get().globalName()).isEqualTo("EzAuth");
                    assertThat(p.widget().get().scriptUrl()).isEqualTo(NiceEzAuthWidget.DEFAULT_SCRIPT_URL);
                    if (oacxSdkPresent()) {
                        assertThat(ctx).hasBean("oacxEasySignIdentityVerificationProvider");
                    } else {
                        assertThat(ctx).doesNotHaveBean("oacxEasySignIdentityVerificationProvider");
                    }
                });
    }

    @Test
    @DisplayName("enabled=true 이고 게이트웨이 빈이 없으면 기본 구성(NICE API 클라이언트·저장소·서비스)을 만들며 Redis·Redisson 빈이 필요하다")
    void enabledWithoutGateway_buildsDefaultGateway() {
        runner.withPropertyValues("idem.plugins.nice-oacx.enabled=true")
                .withBean(org.springframework.data.redis.core.StringRedisTemplate.class, () -> org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class))
                .withBean(org.redisson.api.RedissonClient.class, () -> org.mockito.Mockito.mock(org.redisson.api.RedissonClient.class))
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.databind.ObjectMapper::new)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(NicePhoneGateway.class);
                    assertThat(ctx.getBean(NicePhoneGateway.class)).isInstanceOf(NicePhoneService.class);
                    assertThat(ctx).hasBean("nicePhoneIdentityVerificationProvider");
                });
    }

    @Test
    @DisplayName("SPI 매핑: start → txId=requestNo, result → subjectKey=CI(CI 스킴), di·nationalInfo 는 속성")
    void mapping() {
        var provider = new NicePhoneIdentityVerificationProvider(new FakeGateway(), NiceEzAuthWidget.descriptor(null));
        VerificationStart s = provider.initiate(new VerificationRequest("c", "https://fe", Map.of()));
        assertThat(s.txId()).isEqualTo("REQ-1");
        assertThat(s.redirectUrl()).startsWith("https://nice/auth");

        VerifiedIdentity id = provider.complete(new VerificationCallback("NICE_PHONE", "REQ-1", "c", Map.of("web_transaction_id", "W")));
        assertThat(id.subjectKey()).isEqualTo("CI-1");
        assertThat(id.subjectScheme()).isEqualTo(io.github.hipstermin.idem.common.identity.SubjectScheme.CI);
        assertThat(id.attributes()).containsEntry("nationalInfo", "0").containsEntry("di", "DI-1");

        assertThatThrownBy(() -> provider.complete(new VerificationCallback("NICE_PHONE", "REQ-1", "c", Map.of())))
                .isInstanceOf(IdentityVerificationException.class)
                .extracting("reasonCode").isEqualTo("MISSING_PARAM");
    }
}

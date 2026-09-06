package kr.go.smes.plugin.niceoacx;

import kr.go.smes.common.domain.AuthResult;
import kr.go.smes.common.spi.identity.IdentityVerificationException;
import kr.go.smes.common.spi.identity.IdentityVerificationProvider;
import kr.go.smes.common.spi.identity.VerificationCallback;
import kr.go.smes.common.spi.identity.VerificationRequest;
import kr.go.smes.common.spi.identity.VerificationStart;
import kr.go.smes.common.spi.identity.VerifiedIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NiceOacxPluginSkeletonTest {

    static class FakeGateway implements NicePhoneGateway {
        @Override public Started start(String returnUrl) { return new Started("REQ-1", "https://nice/auth?r=" + returnUrl); }
        @Override public Result result(String webTransactionId, String requestNo) {
            return new Result("DI-1", "홍길동", "19900101", "1", "0", "01012345678", "1");
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

    @Test
    @DisplayName("enabled=true + 게이트웨이 빈 → NICE_PHONE 제공자(L2, EzAuth 위젯) 등록, OACX 는 SDK 부재로 미등록")
    void enabledWithGateway() {
        runner.withUserConfiguration(GatewayConfig.class)
                .withPropertyValues("idem.plugins.nice-oacx.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(IdentityVerificationProvider.class);
                    IdentityVerificationProvider p = ctx.getBean(IdentityVerificationProvider.class);
                    assertThat(p.code()).isEqualTo("NICE_PHONE");
                    assertThat(p.level()).isEqualTo(AuthResult.AuthLevel.L2);
                    assertThat(p.widget()).isPresent();
                    assertThat(p.widget().get().globalName()).isEqualTo("EzAuth");
                    assertThat(p.widget().get().scriptUrl()).isEqualTo(NiceEzAuthWidget.DEFAULT_SCRIPT_URL);
                });
    }

    @Test
    @DisplayName("enabled=true 지만 게이트웨이 빈이 없으면(골격 상태) 제공자를 등록하지 않는다")
    void enabledWithoutGateway() {
        runner.withPropertyValues("idem.plugins.nice-oacx.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(IdentityVerificationProvider.class));
    }

    @Test
    @DisplayName("SPI 매핑: start → txId=requestNo, result → subjectKey=DI")
    void mapping() {
        var provider = new NicePhoneIdentityVerificationProvider(new FakeGateway(), NiceEzAuthWidget.descriptor(null));
        VerificationStart s = provider.initiate(new VerificationRequest("c", "https://fe", Map.of()));
        assertThat(s.txId()).isEqualTo("REQ-1");
        assertThat(s.redirectUrl()).startsWith("https://nice/auth");

        VerifiedIdentity id = provider.complete(new VerificationCallback("NICE_PHONE", "REQ-1", "c", Map.of("web_transaction_id", "W")));
        assertThat(id.subjectKey()).isEqualTo("DI-1");
        assertThat(id.attributes()).containsEntry("nationalInfo", "0");

        assertThatThrownBy(() -> provider.complete(new VerificationCallback("NICE_PHONE", "REQ-1", "c", Map.of())))
                .isInstanceOf(IdentityVerificationException.class)
                .extracting("reasonCode").isEqualTo("MISSING_PARAM");
    }
}

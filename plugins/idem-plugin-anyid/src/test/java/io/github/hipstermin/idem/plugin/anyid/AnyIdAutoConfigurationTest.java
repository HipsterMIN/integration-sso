package io.github.hipstermin.idem.plugin.anyid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.spi.broker.BrokerAuthCompletion;
import io.github.hipstermin.idem.common.spi.broker.DirectBrokerAdapter;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

@DisplayName("AnyIdAutoConfiguration — 플러그인 활성화·SDK 유무·기본 설정")
class AnyIdAutoConfigurationTest {

    private static final boolean SDK_PRESENT;

    static {
        boolean present;
        try {
            Class.forName("kr.or.anyid.util.AnyidCertRef");
            present = true;
        } catch (ClassNotFoundException e) {
            present = false;
        }
        SDK_PRESENT = present;
    }

    @Configuration(proxyBeanMethods = false)
    static class CoreBeans {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean BrokerAuthCompletion brokerAuthCompletion() { return mock(BrokerAuthCompletion.class); }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AnyIdAutoConfiguration.class))
            .withUserConfiguration(CoreBeans.class);

    @Test
    @DisplayName("idem.plugins.anyid.enabled 가 없거나 false 면 어떤 빈도 올리지 않는다")
    void disabledByDefaultInRunner() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(AnyIdBrokerAdapter.class);
            assertThat(ctx).doesNotHaveBean(AnyIdController.class);
        });
        runner.withPropertyValues("idem.plugins.anyid.enabled=false").run(ctx ->
                assertThat(ctx).doesNotHaveBean(DirectBrokerAdapter.class));
    }

    @Test
    @DisplayName("enabled=true → DirectBrokerAdapter(anyid)·컨트롤러 등록, KMS 는 app-key 있을 때만, SsobDecryptor 는 SDK 있을 때만")
    void enabled() {
        runner.withPropertyValues("idem.plugins.anyid.enabled=true", "ido.anyid.srvc-no=SRVC").run(ctx -> {
            assertThat(ctx).hasSingleBean(DirectBrokerAdapter.class);
            assertThat(ctx.getBean(DirectBrokerAdapter.class).id()).isEqualTo("anyid");
            assertThat(ctx).hasSingleBean(AnyIdController.class);
            assertThat(ctx).doesNotHaveBean(AnyIdKmsClient.class);
            assertThat(ctx.getBean(AnyIdProperties.class).getSrvcNo()).isEqualTo("SRVC");
            if (SDK_PRESENT) {
                assertThat(ctx).hasSingleBean(SsobDecryptor.class);
            } else {
                assertThat(ctx).doesNotHaveBean(SsobDecryptor.class);
            }
        });
        runner.withPropertyValues("idem.plugins.anyid.enabled=true", "ido.anyid.kms.app-key=k").run(ctx ->
                assertThat(ctx).hasSingleBean(AnyIdKmsClient.class));
    }

    @Test
    @DisplayName("기본 설정 파일이 가장 낮은 우선순위로 올라가고 ANYID_* 환경변수 이름이 그대로 매핑된다")
    void defaultsPostProcessor() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("fake-env", Map.of(
                "ANYID_SRVC_NO", "SRVC-ENV",
                "ANYID_AUTH_PORT", "9443",
                "ido.anyid.agency-name", "설치측기관")));

        new AnyIdDefaultsEnvironmentPostProcessor().postProcessEnvironment(env, null);
        new AnyIdDefaultsEnvironmentPostProcessor().postProcessEnvironment(env, null); // 멱등

        assertThat(env.getPropertySources().stream()
                .filter(ps -> ps.getName().startsWith(AnyIdDefaultsEnvironmentPostProcessor.SOURCE_NAME)).count()).isEqualTo(1);
        assertThat(env.getProperty("idem.plugins.anyid.enabled")).isEqualTo("true");
        assertThat(env.getProperty("ido.anyid.srvc-no")).isEqualTo("SRVC-ENV");
        assertThat(env.getProperty("ido.anyid.agency-code")).isEmpty();
        assertThat(env.getProperty("ido.anyid.agency-name")).isEqualTo("설치측기관");   // 상위 소스가 이긴다
        assertThat(env.getProperty("ido.anyid.auth.base-url")).isEqualTo("https://www.anyid.dev:9443");
        assertThat(env.getProperty("ido.anyid.sso.adaptor-conf")).isEqualTo("classpath:config/anyid/sso-adaptor-conf-local.properties");
    }
}

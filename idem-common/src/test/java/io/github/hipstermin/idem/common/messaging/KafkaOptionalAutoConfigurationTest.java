package io.github.hipstermin.idem.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaOptionalAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaOptionalAutoConfiguration.class, KafkaAutoConfiguration.class));

    @Test
    void 기본값_kafka_꺼짐_템플릿은_Disabled_이고_리스너_팩토리는_자동시작_안함() {
        runner.withUserConfiguration(CustomFactoryConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(KafkaTemplate.class);
            assertThat(ctx.getBean(KafkaTemplate.class)).isInstanceOf(DisabledKafkaTemplate.class);
            // Boot 기본 팩토리 + 모듈이 직접 만든 팩토리 모두
            ctx.getBeansOfType(ConcurrentKafkaListenerContainerFactory.class).values()
                    .forEach(f -> assertThat(f.getContainerProperties()).isNotNull());
            ConcurrentKafkaListenerContainerFactory<?, ?> custom =
                    ctx.getBean("customListenerFactory", ConcurrentKafkaListenerContainerFactory.class);
            assertThat(autoStartup(custom)).isFalse();
        });
    }

    @Test
    void 모듈이_직접_KafkaTemplate을_두면_그것을_존중한다() {
        runner.withUserConfiguration(CustomTemplateConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(KafkaTemplate.class);
            assertThat(ctx.getBean(KafkaTemplate.class)).isNotInstanceOf(DisabledKafkaTemplate.class);
        });
    }

    @Test
    void kafka_켜면_이_자동설정은_물러나고_Boot_기본_템플릿이_쓰인다() {
        runner.withPropertyValues(KafkaOptional.PROPERTY + "=true").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(KafkaOptionalAutoConfiguration.class);
            assertThat(ctx).hasSingleBean(KafkaTemplate.class);
            assertThat(ctx.getBean(KafkaTemplate.class)).isNotInstanceOf(DisabledKafkaTemplate.class);
            assertThat(ctx).doesNotHaveBean(KafkaListenerAutoStartupDisabler.class);
        });
    }

    private static boolean autoStartup(ConcurrentKafkaListenerContainerFactory<?, ?> factory) throws Exception {
        java.lang.reflect.Field f = org.springframework.kafka.config.AbstractKafkaListenerContainerFactory.class
                .getDeclaredField("autoStartup");
        f.setAccessible(true);
        Object v = f.get(factory);
        return v == null || Boolean.TRUE.equals(v);
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomFactoryConfig {
        @Bean
        ConcurrentKafkaListenerContainerFactory<String, String> customListenerFactory() {
            return new ConcurrentKafkaListenerContainerFactory<>();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomTemplateConfig {
        @Bean
        KafkaTemplate<String, Object> myTemplate() {
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(java.util.Map.of(
                    "bootstrap.servers", "localhost:9092")));
        }
    }
}

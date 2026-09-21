package io.github.hipstermin.idem.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

class KafkaOptionalEnvironmentPostProcessorTest {

    private final KafkaOptionalEnvironmentPostProcessor sut = new KafkaOptionalEnvironmentPostProcessor();

    @Test
    void kafka_꺼짐_기본값이면_파생_기본값_소스가_systemEnvironment_바로_아래에_들어간다() {
        StandardEnvironment env = new StandardEnvironment();
        // 모듈 application.yml 을 흉내 — 최하위
        env.getPropertySources().addLast(new MapPropertySource("app-yml", Map.of(
                "qsign.outbox.relay-enabled", "true",
                "ido.audit.kafka-publish-enabled", "true")));

        sut.postProcessEnvironment(env, null);

        assertThat(env.getPropertySources().contains(KafkaOptionalEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isTrue();
        assertThat(env.getProperty("qsign.outbox.relay-enabled")).isEqualTo("false");
        assertThat(env.getProperty("ido.audit.kafka-publish-enabled")).isEqualTo("false");
        assertThat(env.getProperty("spring.kafka.listener.auto-startup")).isEqualTo("false");
        assertThat(env.getProperty("batch.relay.qsign.kafka.enabled")).isEqualTo("false");
        // hub ido.outbox 릴레이(F-13)는 건드리지 않는다 — 프로세스 내 배달로 계속 돈다
        assertThat(env.getProperty("ido.outbox.relay-enabled")).isNull();

        int sysEnvIdx = indexOf(env, StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        int oursIdx   = indexOf(env, KafkaOptionalEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
        assertThat(oursIdx).isEqualTo(sysEnvIdx + 1);
    }

    @Test
    void kafka_켜져_있으면_아무것도_넣지_않는다() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addLast(new MapPropertySource("app-yml", Map.of(
                KafkaOptional.PROPERTY, "true",
                "qsign.outbox.relay-enabled", "true")));

        sut.postProcessEnvironment(env, null);

        assertThat(env.getPropertySources().contains(KafkaOptionalEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
        assertThat(env.getProperty("qsign.outbox.relay-enabled")).isEqualTo("true");
    }

    @Test
    void 명령행_등_상위_소스가_있으면_그_값이_이긴다() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("commandLineArgs", Map.of(
                "qsign.outbox.relay-enabled", "true")));

        sut.postProcessEnvironment(env, null);

        assertThat(env.getProperty("qsign.outbox.relay-enabled")).isEqualTo("true");
        assertThat(env.getProperty("qim.outbox.relay-enabled")).isEqualTo("false");
    }

    @Test
    void 두_번_실행해도_소스는_하나다() {
        StandardEnvironment env = new StandardEnvironment();
        sut.postProcessEnvironment(env, null);
        sut.postProcessEnvironment(env, null);
        long n = env.getPropertySources().stream()
                .map(PropertySource::getName)
                .filter(KafkaOptionalEnvironmentPostProcessor.PROPERTY_SOURCE_NAME::equals)
                .count();
        assertThat(n).isEqualTo(1);
    }

    private static int indexOf(StandardEnvironment env, String name) {
        int i = 0;
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (ps.getName().equals(name)) return i;
            i++;
        }
        return -1;
    }
}

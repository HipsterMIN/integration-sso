package io.github.hipstermin.idem.hub.kr.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

/** D3: KR 에디션 기본값은 코어 yml 기본값을 이기고, 환경변수·명령행은 그 위에 선다. */
@DisplayName("KrHubEditionEnvironmentPostProcessor — 에디션 기본값의 우선순위")
class KrHubEditionEnvironmentPostProcessorTest {

    private static MockEnvironment envWithCoreYml() {
        MockEnvironment env = new MockEnvironment();
        // 실제 Boot 환경처럼 systemEnvironment 소스가 먼저 있고(비어 있음), 그 뒤에 코어 application.yml 이 온다
        env.getPropertySources().addFirst(new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of()));
        // 코어 application.yml 흉내: 플러그인 기본 false, 비OIDC 사업자 없음
        env.getPropertySources().addLast(new MapPropertySource("coreYml", Map.of(
                "idem.plugins.nice-oacx.enabled", "false",
                "idem.plugins.anyid.enabled", "false")));
        return env;
    }

    @Test
    @DisplayName("코어 yml 이 false 여도 KR 에디션은 벤더 플러그인을 켠다")
    void editionDefaultsBeatCoreYml() {
        MockEnvironment env = envWithCoreYml();
        new KrHubEditionEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertThat(env.getProperty("idem.plugins.nice-oacx.enabled")).isEqualTo("true");
        assertThat(env.getProperty("idem.plugins.anyid.enabled")).isEqualTo("true");
        assertThat(env.getProperty("idem.hub.broker.nonoidc.providers.PASS.auth-level")).isEqualTo("L2");
        assertThat(env.getProperty("idem.hub.broker.nonoidc.providers.PASS.initiate-url")).contains("{callbackUrl}");
    }

    @Test
    @DisplayName("환경변수(IDEM_PLUGINS_*_ENABLED=false)가 있으면 그 값이 이긴다")
    void environmentVariableWins() {
        MockEnvironment env = envWithCoreYml();
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of("IDEM_PLUGINS_ANYID_ENABLED", "false")));
        new KrHubEditionEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertThat(env.getProperty("idem.plugins.anyid.enabled")).isEqualTo("false");
        assertThat(env.getProperty("idem.plugins.nice-oacx.enabled")).isEqualTo("true");
    }

    @Test
    void idempotent() {
        MockEnvironment env = envWithCoreYml();
        KrHubEditionEnvironmentPostProcessor pp = new KrHubEditionEnvironmentPostProcessor();
        pp.postProcessEnvironment(env, null);
        pp.postProcessEnvironment(env, null);
        assertThat(env.getPropertySources().stream().filter(s -> s.getName().equals(KrHubEditionEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).count()).isEqualTo(1);
    }
}

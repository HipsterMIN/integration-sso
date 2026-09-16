package io.github.hipstermin.idem.plugin.anyid;

import java.io.IOException;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 플러그인 기본 설정({@code idem-plugin-anyid-defaults.yml})을 가장 낮은 우선순위로 환경에 올린다.
 *
 * <p>코어 {@code application.yml} 에는 AnyID 설정이 없다(S5b). 환경변수 {@code ANYID_*} 이름은 종전과 같고,
 * 설치 측 {@code application-*.yml}·환경변수·Secret 이 이 기본값보다 항상 우선한다.
 */
public class AnyIdDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String RESOURCE = "idem-plugin-anyid-defaults.yml";
    static final String SOURCE_NAME = "idem-plugin-anyid-defaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(SOURCE_NAME)) return;
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) return;
        try {
            List<PropertySource<?>> loaded = new YamlPropertySourceLoader().load(SOURCE_NAME, resource);
            for (PropertySource<?> ps : loaded) {
                environment.getPropertySources().addLast(ps);
            }
        } catch (IOException e) {
            throw new IllegalStateException("idem-plugin-anyid 기본 설정을 읽을 수 없습니다: " + RESOURCE, e);
        }
    }
}

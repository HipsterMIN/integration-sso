package io.github.hipstermin.idem.common.naming;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * 개명 4b 호환 계층 (S9, 1 릴리스 유지) — 구 설정 키·환경변수를 새 이름으로 비춰 준다.
 *
 * <ul>
 *   <li><b>환경변수</b>: {@code IDO_HANDOFF_AES_KEY} 처럼 구 이름만 있으면 {@code IDEM_HUB_HANDOFF_AES_KEY} 를 같은 값으로 공급한다
 *       (systemEnvironment 바로 뒤에 {@link SystemEnvironmentPropertySource} 로 넣어, 새 이름이 실제 환경에 있으면 그것이 이기고
 *       {@code idem.hub.x.y} 같은 느슨한 이름 대응도 실제 환경변수와 같다).</li>
 *   <li><b>설정 키</b>: 어떤 프로퍼티 소스(외부 yml·시스템 프로퍼티·명령행 등)에 {@code ido.x} 가 있고 어디에도 {@code idem.hub.x} 가 없으면
 *       그 소스 바로 앞에 {@code idem.hub.x} 를 같은 값으로 넣는다.</li>
 * </ul>
 * 사용된 구 이름은 기동 시 WARN 으로 한 번 나열한다. 새 이름이 있으면 구 이름은 무시된다(중복 정의 시 새 이름 우선).
 * 설정 파일 로딩({@link ConfigDataEnvironmentPostProcessor}) 뒤에 실행된다.
 */
public class LegacyNamesEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String ENV_SOURCE = "idem-legacy-env-aliases";
    static final String KEY_SOURCE_PREFIX = "idem-legacy-key-aliases:";

    private final Log log;

    public LegacyNamesEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(LegacyNamesEnvironmentPostProcessor.class);
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        TreeSet<String> used = new TreeSet<>();
        aliasEnvironmentVariables(environment, used);
        aliasPropertyKeys(environment, used);
        if (!used.isEmpty()) {
            log.warn("[Idem 개명] 구 이름 " + used.size() + "개를 새 이름으로 비춰 적용했습니다 — 다음 릴리스에서 호환 계층이 사라집니다 "
                    + "(docs/naming.md §3): " + String.join(", ", used));
        }
    }

    private void aliasEnvironmentVariables(ConfigurableEnvironment environment, TreeSet<String> used) {
        MutablePropertySources sources = environment.getPropertySources();
        PropertySource<?> sysEnv = sources.get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        if (!(sysEnv instanceof EnumerablePropertySource<?> env) || sources.contains(ENV_SOURCE)) return;
        Map<String, Object> aliases = new LinkedHashMap<>();
        for (String name : env.getPropertyNames()) {
            if (!LegacyNames.isLegacyEnv(name)) continue;
            String target = LegacyNames.mapEnv(name);
            if (target.equals(name) || env.containsProperty(target)) continue;
            Object value = env.getProperty(name);
            if (value != null) { aliases.put(target, value); used.add(name + "→" + target); }
        }
        if (!aliases.isEmpty()) {
            // SystemEnvironmentPropertySource: 실제 환경변수처럼 느슨한 이름 대응이 된다 — @Value("${idem.hub.cast.private-key}") 가
            // yml 자리표시자 없이 IDEM_HUB_CAST_PRIVATE_KEY 를 직접 읽는 곳(CastKeyConfig 등)도 구 이름으로 동작한다
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new SystemEnvironmentPropertySource(ENV_SOURCE, aliases));
        }
    }

    private void aliasPropertyKeys(ConfigurableEnvironment environment, TreeSet<String> used) {
        MutablePropertySources sources = environment.getPropertySources();
        // 순회 중 삽입하므로 이름 목록을 먼저 복사한다
        java.util.List<String> names = new java.util.ArrayList<>();
        for (PropertySource<?> ps : sources) names.add(ps.getName());
        for (String name : names) {
            PropertySource<?> ps = sources.get(name);
            if (!(ps instanceof EnumerablePropertySource<?> eps)) continue;
            if (name.equals(ENV_SOURCE) || name.startsWith(KEY_SOURCE_PREFIX)
                    || name.equals(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) continue;
            Map<String, Object> aliases = new LinkedHashMap<>();
            for (String key : eps.getPropertyNames()) {
                if (!LegacyNames.isLegacyKey(key)) continue;
                String target = LegacyNames.mapKey(key);
                if (target.equals(key) || environment.containsProperty(target)) continue;
                Object value = eps.getProperty(key);
                if (value != null) { aliases.put(target, value); used.add(key + "→" + target); }
            }
            if (!aliases.isEmpty()) {
                sources.addBefore(name, new MapPropertySource(KEY_SOURCE_PREFIX + name, aliases));
            }
        }
    }
}

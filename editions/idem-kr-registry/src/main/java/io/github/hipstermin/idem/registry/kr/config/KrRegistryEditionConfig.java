package io.github.hipstermin.idem.registry.kr.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.Location;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * KR 에디션 registry 설정 — KR 전용 Flyway 마이그레이션 location 을 코어 location 뒤에 더한다.
 *
 * <p>코어는 {@code classpath:db/migration/postgresql}(V1~V999), KR 은 {@code classpath:db/migration/kr/postgresql}(V1000+).
 * 코어 application.yml 을 건드리지 않고 jar 존재만으로 활성화된다 (S8-a).
 */
@Slf4j
@Configuration
public class KrRegistryEditionConfig {

    public static final String KR_MIGRATION_LOCATION = "classpath:db/migration/kr/postgresql";

    @Bean
    public FlywayConfigurationCustomizer krEditionFlywayLocations() {
        return configuration -> {
            List<String> locations = new ArrayList<>(
                    Arrays.stream(configuration.getLocations()).map(Location::getDescriptor).toList());
            if (!locations.contains(KR_MIGRATION_LOCATION)) {
                locations.add(KR_MIGRATION_LOCATION);
            }
            configuration.locations(locations.toArray(String[]::new));
            log.info("[KR-Edition] Flyway locations={}", locations);
        };
    }
}

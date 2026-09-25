package io.github.hipstermin.idem.common.naming;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot 자동 Flyway(hub·gate·registry·authz)에 {@link LegacySchemaRename} 을 끼운다. 개명 5단계 호환 — 1 릴리스 뒤 제거.
 */
@AutoConfiguration(before = FlywayAutoConfiguration.class)
@ConditionalOnClass({Flyway.class, FlywayMigrationStrategy.class})
public class LegacySchemaRenameAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(FlywayMigrationStrategy.class)
    public FlywayMigrationStrategy legacySchemaRenameMigrationStrategy() {
        return LegacySchemaRename::migrate;
    }
}

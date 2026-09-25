package io.github.hipstermin.idem.relay.config;

import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * 배치 서비스 Flyway 마이그레이션 설정
 *
 * <h2>마이그레이션 전략</h2>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  ido DataSource  → V19__add_shedlock_table.sql                      │
 * │                     (idem.hub.shedlock 테이블 생성 — ShedLock JDBC용)    │
 * │                                                                      │
 * │  q-im DataSource → 마이그레이션 없음 (읽기 전용, 기존 스키마 사용)  │
 * │  q-sign DataSource → 마이그레이션 없음 (읽기 전용, 기존 스키마 사용)│
 * └──────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Spring Boot 자동 Flyway 비활성화</h2>
 * application.yml에서 {@code spring.flyway.enabled=false}로 설정하여
 * Spring Boot 자동 Flyway를 비활성화하고 이 설정 클래스에서 수동으로 제어함.
 * 이유: 멀티 DataSource 환경에서 Primary DS만 마이그레이션해야 함.
 */
@Slf4j
@Configuration
public class BatchFlywayConfig {

    /**
     * ido DataSource Flyway 마이그레이션
     *
     * <p>shedlock 테이블이 ido 스키마에 존재하므로 ido DataSource를 사용.
     * locations: classpath:db/migration — V19__add_shedlock_table.sql 포함.
     *
     * <p>baseline-on-migrate: ido 서비스가 이미 V18까지 마이그레이션을 완료했으므로
     * 배치 서비스가 처음 실행될 때 V19부터 적용.
     * baseline-version=18 으로 설정하여 기존 마이그레이션 이력과 충돌 방지.
     *
     * <p>⚠️ ido 서비스도 동일 DB를 바라보므로 V19 스크립트 이름 충돌 주의:
     * ido 서비스의 마이그레이션과 번호가 겹치지 않도록 관리 필요.
     * (운영 시: ido 서비스 V19를 먼저 적용하거나, 배치 서비스 V19를 ido로 이관 권장)
     */
    // S9 PR-2: 구 스키마(ido) 가 남아 있으면 idem_hub 로 rename → repair → migrate (LegacySchemaRename). 1 릴리스 뒤 initMethod = "migrate" 로 되돌린다
    @Bean(name = "idoFlyway")
    @DependsOn("idoDataSource")
    public Flyway idoFlyway(@Qualifier("idoDataSource") DataSource idoDataSource) {
        log.info("[BatchFlyway] ido DataSource Flyway 마이그레이션 시작");
        Flyway flyway = Flyway.configure()
                .dataSource(idoDataSource)
                .locations("classpath:db/migration")
                .schemas("idem_hub")
                .defaultSchema("idem_hub")
                .baselineOnMigrate(true)
                .baselineVersion("18")          // ido 서비스 V18까지 완료 기준
                .validateOnMigrate(true)
                .outOfOrder(false)
                .connectRetries(5)
                .load();
        io.github.hipstermin.idem.common.naming.LegacySchemaRename.migrate(flyway);
        return flyway;
    }
}

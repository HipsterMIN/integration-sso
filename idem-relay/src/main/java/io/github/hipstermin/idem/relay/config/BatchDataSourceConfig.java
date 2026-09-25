package io.github.hipstermin.idem.relay.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 배치 서비스 멀티 DataSource 설정
 *
 * <h2>구성</h2>
 * <pre>
 * ┌─────────────────────┬──────────┬─────────────────────────────────────┐
 * │ DataSource Bean     │ DB 종류  │ 대상 테이블                           │
 * ├─────────────────────┼──────────┼─────────────────────────────────────┤
 * │ idoDataSource       │ PgSQL    │ idem.hub.outbox                           │
 * │                     │          │ ido.provisioning_outbox              │
 * │                     │          │ ido.webhook_dispatch_outbox          │
 * │                     │          │ idem.hub.shedlock  (락 메타 테이블)        │
 * ├─────────────────────┼──────────┼─────────────────────────────────────┤
 * │ qimDataSource       │ PostgreSQL│ idem.registry.outbox                           │
 * ├─────────────────────┼──────────┼─────────────────────────────────────┤
 * │ qsignDataSource     │ PgSQL    │ idem.gate.outbox                         │
 * ├─────────────────────┼──────────┼─────────────────────────────────────┤
 * │ authzDataSource     │ PgSQL    │ authz.authz_outbox                   │
 * └─────────────────────┴──────────┴─────────────────────────────────────┘
 * </pre>
 *
 * <h2>Primary DataSource</h2>
 * ido DataSource를 Primary로 지정 — Spring Boot 자동 설정(Flyway, JdbcTemplate)의
 * 기본 DataSource로 사용됨. idem.hub.shedlock 테이블도 여기에 생성.
 *
 * <h2>커넥션 풀 전략</h2>
 * 배치 릴레이는 순차 폴링 방식이므로 풀 크기를 작게 유지.
 * 각 DS 별 최대 5개 커넥션으로 충분 (배치 자체가 단일 스레드 폴링).
 */
@Slf4j
@Configuration
public class BatchDataSourceConfig {

    // ─────────────────────────────────────────────────────────────────────
    // ido DataSource (Primary)
    // ─────────────────────────────────────────────────────────────────────

    @Bean
    @Primary
    @ConfigurationProperties(prefix = "idem.relay.datasource.ido")
    public HikariConfig idoHikariConfig() {
        return new HikariConfig();
    }

    /**
     * ido PostgreSQL DataSource (Primary)
     *
     * <p>ShedLock JDBC Provider, Flyway, idem.hub.outbox / ido.provisioning_outbox /
     * ido.webhook_dispatch_outbox 접근에 사용.
     */
    @Bean(name = "idoDataSource", destroyMethod = "close")
    @Primary
    public HikariDataSource idoDataSource(@Qualifier("idoHikariConfig") HikariConfig config) {
        config.setPoolName("batch-ido-hikari");
        HikariDataSource ds = new HikariDataSource(config);
        log.info("[BatchDS] ido DataSource 초기화: jdbcUrl={}", config.getJdbcUrl());
        return ds;
    }

    @Bean(name = "idoJdbcTemplate")
    @Primary
    public JdbcTemplate idoJdbcTemplate(@Qualifier("idoDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    // ─────────────────────────────────────────────────────────────────────
    // q-im DataSource (PostgreSQL qim 스키마 — D1)
    // ─────────────────────────────────────────────────────────────────────

    @Bean
    @ConfigurationProperties(prefix = "idem.relay.datasource.qim")
    public HikariConfig qimHikariConfig() {
        return new HikariConfig();
    }

    /**
     * q-im PostgreSQL DataSource
     *
     * <p>idem.registry.outbox 테이블 접근 전용.
     * D1 부터 hub 와 같은 PostgreSQL 인스턴스의 qim 스키마를 쓴다(종전 MariaDB 는 IDEM_REGISTRY_DB_URL/IDEM_REGISTRY_DB_DRIVER 로 1 릴리스 호환).
     */
    @Bean(name = "qimDataSource", destroyMethod = "close")
    public HikariDataSource qimDataSource(@Qualifier("qimHikariConfig") HikariConfig config) {
        config.setPoolName("batch-qim-hikari");
        HikariDataSource ds = new HikariDataSource(config);
        log.info("[BatchDS] q-im DataSource 초기화: jdbcUrl={}", config.getJdbcUrl());
        return ds;
    }

    @Bean(name = "qimJdbcTemplate")
    public JdbcTemplate qimJdbcTemplate(@Qualifier("qimDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    // ─────────────────────────────────────────────────────────────────────
    // q-sign DataSource (PostgreSQL)
    // ─────────────────────────────────────────────────────────────────────

    @Bean
    @ConfigurationProperties(prefix = "idem.relay.datasource.qsign")
    public HikariConfig qsignHikariConfig() {
        return new HikariConfig();
    }

    /**
     * q-sign PostgreSQL DataSource
     *
     * <p>idem.gate.outbox 테이블 접근 전용.
     */
    @Bean(name = "qsignDataSource", destroyMethod = "close")
    public HikariDataSource qsignDataSource(@Qualifier("qsignHikariConfig") HikariConfig config) {
        config.setPoolName("batch-qsign-hikari");
        HikariDataSource ds = new HikariDataSource(config);
        log.info("[BatchDS] q-sign DataSource 초기화: jdbcUrl={}", config.getJdbcUrl());
        return ds;
    }

    @Bean(name = "qsignJdbcTemplate")
    public JdbcTemplate qsignJdbcTemplate(@Qualifier("qsignDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    // ─────────────────────────────────────────────────────────────────────
    // q-authz DataSource (PostgreSQL)
    // ─────────────────────────────────────────────────────────────────────

    @Bean
    @ConfigurationProperties(prefix = "idem.relay.datasource.authz")
    public HikariConfig authzHikariConfig() {
        return new HikariConfig();
    }

    /**
     * q-authz PostgreSQL DataSource
     *
     * <p>authz.authz_outbox 테이블 접근 전용 — 인가 부여/회수/만료 이벤트 릴레이.
     */
    @Bean(name = "authzDataSource", destroyMethod = "close")
    public HikariDataSource authzDataSource(@Qualifier("authzHikariConfig") HikariConfig config) {
        config.setPoolName("batch-authz-hikari");
        HikariDataSource ds = new HikariDataSource(config);
        log.info("[BatchDS] q-authz DataSource 초기화: jdbcUrl={}", config.getJdbcUrl());
        return ds;
    }

    @Bean(name = "authzJdbcTemplate")
    public JdbcTemplate authzJdbcTemplate(@Qualifier("authzDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}

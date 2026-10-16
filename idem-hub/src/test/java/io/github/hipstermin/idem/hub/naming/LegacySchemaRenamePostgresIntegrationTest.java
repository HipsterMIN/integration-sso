package io.github.hipstermin.idem.hub.naming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.hipstermin.idem.common.naming.LegacySchemaRename;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * 시험 항목 A-5 "업그레이드(0.x→1.0)" (G2-14, 2026-10-16 — 리허설(수동) → 자동).
 *
 * <p>구 설치본(스키마 {@code ido}, 개명 전 마이그레이션 파일로 적용된 Flyway 이력)으로 1.0 이상의 hub 가 기동할 때
 * {@code LegacySchemaRename}(Flyway 전략)이 하는 일을 <b>실제 PostgreSQL</b> 에서 검사한다 — 스키마 자동 rename, 개명 전 적용분의 체크섬
 * 불일치만 1회 repair, 데이터 보존, 로그 {@code [Idem 개명]}, 구·신 스키마 공존 시 기동 거부, 이관 끝난 잔재는 경고만, 체크섬 이외의 검증
 * 오류는 repair 없이 거부. 구 마이그레이션 파일은 저장소에 없으므로(이름이 바뀌었다) 현재 파일로 적용한 뒤 스키마를 {@code ido} 로 되돌리고
 * 이력의 체크섬을 바꿔 "개명 전 적용분" 을 만든다.
 *
 * <p>{@code @Tag("integration")} — {@code ./gradlew :idem-hub:integrationTest}(Docker 필요). 순수 함수 쪽은 idem-common 의
 * {@code LegacySchemaRenameTest}, 설치본 절차는 {@code scripts/upgrade/rename-db-1.0.sh}·{@code docs/install.md} §7.
 */
@Tag("integration")
@DisplayName("A-5 업그레이드(0.x→1.0) — 구 스키마 ido 자동 rename·체크섬 repair·기동 거부 (실제 PostgreSQL)")
class LegacySchemaRenamePostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                    .withDatabaseName("idem_a5").withUsername("idem").withPassword("idem")
                    .waitingFor(Wait.forListeningPort());

    static {
        POSTGRES.start();
        Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop));
    }

    private static final String NEW = "idem_hub";
    private static final String OLD = "ido";
    private static ListAppender<ILoggingEvent> logs;
    private final DataSource dataSource = dataSource();
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    static DataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    /** hub 의 application.yml 과 같은 Flyway 설정(위치·스키마·baseline) */
    static Flyway flyway(DataSource ds) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .schemas(NEW).defaultSchema(NEW).baselineOnMigrate(true).baselineVersion("0").load();
    }

    @BeforeAll
    static void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(LegacySchemaRename.class)).addAppender(logs);
    }

    @AfterAll
    static void releaseLogs() {
        ((Logger) LoggerFactory.getLogger(LegacySchemaRename.class)).detachAppender(logs);
    }

    @BeforeEach
    void cleanSchemas() {
        jdbc.execute("DROP SCHEMA IF EXISTS \"" + NEW + "\" CASCADE");
        jdbc.execute("DROP SCHEMA IF EXISTS \"" + OLD + "\" CASCADE");
        logs.list.clear();
    }

    /** 구 설치본 흉내: 현재 파일로 적용 → 표지 행 → 스키마를 ido 로 → 이력 체크섬을 바꿔 "개명 전 파일로 적용된" 상태 */
    private int makeLegacyInstall() {
        flyway(dataSource).migrate();
        jdbc.execute("CREATE TABLE " + NEW + ".a5_marker (id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO " + NEW + ".a5_marker (id) VALUES (1)");
        jdbc.execute("ALTER SCHEMA \"" + NEW + "\" RENAME TO \"" + OLD + "\"");
        return jdbc.update("UPDATE " + OLD + ".flyway_schema_history SET checksum = checksum + 1 WHERE version IS NOT NULL AND checksum IS NOT NULL");
    }

    private boolean schemaExists(String schema) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = ?)", Boolean.class, schema));
    }

    private int tableCount(String schema) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM pg_catalog.pg_tables WHERE schemaname = ?", Integer.class, schema);
        return n == null ? 0 : n;
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("구 스키마 ido 만 있고 이력 체크섬이 개명 전 값이면: rename → 체크섬 repair 1회 → migrate, 데이터 보존, 로그 [Idem 개명]")
    void legacyOnly_renamedRepairedAndMigrated() {
        int changed = makeLegacyInstall();
        assertThat(changed).as("개명 전 적용분으로 만든 이력 행").isGreaterThan(5);
        assertThat(schemaExists(OLD)).isTrue();
        assertThat(schemaExists(NEW)).isFalse();

        LegacySchemaRename.migrate(flyway(dataSource));   // hub 기동 때 FlywayMigrationStrategy 가 하는 호출

        assertThat(schemaExists(NEW)).as("새 스키마").isTrue();
        assertThat(schemaExists(OLD)).as("구 스키마는 이름이 바뀌어 사라진다").isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + NEW + ".a5_marker", Integer.class)).as("데이터 보존").isEqualTo(1);
        assertThat(flyway(dataSource).validateWithResult().validationSuccessful).as("repair 뒤 검증 통과").isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + NEW + ".flyway_schema_history WHERE success = FALSE", Integer.class)).isZero();
        assertThat(messages()).anySatisfy(m -> assertThat(m).contains("[Idem 개명]").contains("스키마 " + OLD + " → " + NEW));
        assertThat(messages()).anySatisfy(m -> assertThat(m).contains("[Idem 개명]").contains("1회 repair"));
        // 두 번째 기동(이미 이관됨)은 아무 일도 하지 않는다
        logs.list.clear();
        LegacySchemaRename.migrate(flyway(dataSource));
        assertThat(messages()).noneMatch(m -> m.contains("[Idem 개명]"));
    }

    @Test
    @DisplayName("구·신 스키마가 둘 다 있고 새 쪽에 Flyway 이력이 없으면 기동 거부 — 아무것도 바꾸지 않는다(구 데이터 고아 방지)")
    void bothWithoutHistory_refused() {
        makeLegacyInstall();
        jdbc.execute("CREATE SCHEMA \"" + NEW + "\"");   // Helm db-init 훅·수동 CREATE 가 먼저 만든 빈 스키마

        assertThatThrownBy(() -> LegacySchemaRename.migrate(flyway(dataSource)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[Idem 개명]").hasMessageContaining("둘 다 있고").hasMessageContaining("rename-db-1.0.sh");

        assertThat(schemaExists(OLD)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + OLD + ".a5_marker", Integer.class)).isEqualTo(1);
        assertThat(tableCount(NEW)).as("빈 새 스키마에 아무것도 만들지 않는다").isZero();
    }

    @Test
    @DisplayName("둘 다 있고 새 쪽에 이력이 있으면(이관이 끝난 잔재) 경고만 남기고 정상 기동, 구 스키마는 건드리지 않는다")
    void leftoverLegacy_warnsOnly() {
        flyway(dataSource).migrate();
        jdbc.execute("CREATE SCHEMA \"" + OLD + "\"");
        jdbc.execute("CREATE TABLE " + OLD + ".leftover (id INT)");

        LegacySchemaRename.migrate(flyway(dataSource));

        assertThat(schemaExists(OLD)).isTrue();
        assertThat(tableCount(OLD)).isEqualTo(1);
        assertThat(flyway(dataSource).validateWithResult().validationSuccessful).isTrue();
        assertThat(messages()).anySatisfy(m -> assertThat(m).contains("[Idem 개명]").contains("아직 남아 있습니다").contains("DROP SCHEMA " + OLD));
    }

    @Test
    @DisplayName("체크섬 불일치 이외의 검증 오류(실패한 마이그레이션 이력)는 repair 하지 않고 기동 거부")
    void nonChecksumValidationError_refused() {
        makeLegacyInstall();
        jdbc.update("UPDATE " + OLD + ".flyway_schema_history SET success = FALSE WHERE installed_rank = (SELECT MAX(installed_rank) FROM " + OLD + ".flyway_schema_history)");

        assertThatThrownBy(() -> LegacySchemaRename.migrate(flyway(dataSource)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("repair 하지 않습니다").hasMessageContaining("체크섬 불일치 이외의 오류");

        // rename 은 이미 됐지만(스키마 이름만) repair·migrate 는 하지 않았다 — 체크섬은 그대로, 실패 행도 그대로
        assertThat(schemaExists(NEW)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + NEW + ".flyway_schema_history WHERE success = FALSE", Integer.class)).isEqualTo(1);
        assertThat(flyway(dataSource).validateWithResult().validationSuccessful).isFalse();
        assertThat(messages()).noneMatch(m -> m.contains("1회 repair"));
    }
}

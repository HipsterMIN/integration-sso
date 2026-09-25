package io.github.hipstermin.idem.common.naming;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 개명 5단계(S9 PR-2) — PostgreSQL 스키마 자동 rename + Flyway repair/migrate.
 *
 * <p>구 스키마({@code ido}·{@code qsign}·{@code qim}·{@code authz})가 있고 새 스키마({@code idem_hub}·{@code idem_gate}·{@code idem_registry}·
 * {@code idem_authz})가 없으면 {@code ALTER SCHEMA … RENAME TO …} 로 옮긴다(Flyway 이력 테이블도 함께 옮겨진다). 마이그레이션 파일의 스키마
 * 접두를 새 이름으로 고쳐 체크섬이 바뀌었으므로 {@link Flyway#repair()} 뒤에 {@link Flyway#migrate()} 한다. 새 설치는 rename 할 것이 없어
 * repair 는 no-op 이다. 1 릴리스 뒤 제거 (docs/naming.md §3).
 */
public final class LegacySchemaRename {

    private static final Logger log = LoggerFactory.getLogger(LegacySchemaRename.class);

    /** 새 스키마 → 구 스키마 */
    static final Map<String, String> LEGACY = new LinkedHashMap<>();

    static {
        LEGACY.put("idem_hub", "ido");
        LEGACY.put("idem_gate", "qsign");
        LEGACY.put("idem_registry", "qim");
        LEGACY.put("idem_authz", "authz");
    }

    private LegacySchemaRename() {}

    /** Flyway 가 관리하는 스키마마다 구 스키마가 남아 있으면 rename 한 뒤 repair + migrate. {@code FlywayMigrationStrategy} 와 relay 가 쓴다 */
    public static void migrate(Flyway flyway) {
        boolean renamed = false;
        for (String schema : flyway.getConfiguration().getSchemas()) {
            renamed |= renameIfLegacy(flyway.getConfiguration().getDataSource(), schema);
        }
        if (renamed) {
            log.warn("[Idem 개명] 구 스키마를 새 이름으로 옮겼습니다 — Flyway 이력을 repair 한 뒤 migrate 합니다");
        }
        // 마이그레이션 파일의 스키마 접두가 바뀌어 체크섬이 다르다(기존 설치본). 새 설치에서는 no-op
        flyway.repair();
        flyway.migrate();
    }

    /** @return rename 했으면 true */
    static boolean renameIfLegacy(DataSource dataSource, String newSchema) {
        String legacy = LEGACY.get(newSchema);
        if (legacy == null) return false;
        try (Connection c = dataSource.getConnection()) {
            if (!schemaExists(c, legacy) || schemaExists(c, newSchema)) return false;
            try (Statement st = c.createStatement()) {
                st.execute("ALTER SCHEMA \"" + legacy + "\" RENAME TO \"" + newSchema + "\"");
            }
            log.warn("[Idem 개명] 스키마 {} → {} (docs/naming.md §3, 5단계)", legacy, newSchema);
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("[Idem 개명] 스키마 " + legacy + " → " + newSchema + " rename 실패", e);
        }
    }

    private static boolean schemaExists(Connection c, String schema) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }
}

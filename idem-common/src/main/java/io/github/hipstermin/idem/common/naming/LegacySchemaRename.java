package io.github.hipstermin.idem.common.naming;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.ValidateOutput;
import org.flywaydb.core.api.output.ValidateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 개명 5단계(S9 PR-2) — PostgreSQL 스키마 자동 rename + Flyway 검증/repair/migrate.
 *
 * <p>구 스키마({@code ido}·{@code qsign}·{@code qim}·{@code authz})가 있고 새 스키마({@code idem_hub}·{@code idem_gate}·{@code idem_registry}·
 * {@code idem_authz})가 없으면 {@code ALTER SCHEMA … RENAME TO …} 로 옮긴다(Flyway 이력 테이블도 함께 옮겨진다). 마이그레이션 파일의 스키마
 * 접두를 새 이름으로 고쳐 체크섬이 바뀌었으므로 옮긴 이력은 {@link Flyway#repair()} 가 필요하다. 1 릴리스 뒤 제거 (docs/naming.md §3).
 *
 * <p><b>1.0.1 (3차 점검 H7·M1)</b>:
 * <ul>
 *   <li>구·신 스키마가 <b>둘 다</b> 있는데 새 쪽에 Flyway 이력이 없으면 기동을 거부한다 — 새 스키마가 먼저 생겨(Helm db-init 훅, 수동 CREATE) 빈 스키마에
 *       새 테이블이 만들어지면 구 데이터가 고아가 된다. 둘 다 있고 새 쪽에 이력이 있으면(이미 이관 완료, 구 스키마만 남음) WARN 으로 알린다.</li>
 *   <li>{@code repair()} 는 매 기동이 아니라 {@link Flyway#validateWithResult()} 가 <b>체크섬 불일치만</b> 보고할 때 1회 한다(개명 전 적용분).
 *       빠진·실패한·무시된 마이그레이션은 repair 하지 않고 검증 오류로 드러낸다. {@code IDEM_NAMING_LEGACY_REPAIR=false} 면 아예 repair 하지 않는다 —
 *       업그레이드가 끝난 설치본은 이 값을 false 로 두어 변조된 마이그레이션이 조용히 통과하지 않게 한다.</li>
 * </ul>
 */
public final class LegacySchemaRename {

    private static final Logger log = LoggerFactory.getLogger(LegacySchemaRename.class);

    /** 새 스키마 → 구 스키마 */
    static final Map<String, String> LEGACY = new LinkedHashMap<>();

    /** 개명 체크섬 repair 허용 스위치 — 환경변수 {@code IDEM_NAMING_LEGACY_REPAIR}(기본 true, 1 릴리스 뒤 제거) */
    static final String LEGACY_REPAIR_ENV = "IDEM_NAMING_LEGACY_REPAIR";

    static {
        LEGACY.put("idem_hub", "ido");
        LEGACY.put("idem_gate", "qsign");
        LEGACY.put("idem_registry", "qim");
        LEGACY.put("idem_authz", "authz");
    }

    private LegacySchemaRename() {}

    /** 스키마 상태에 따른 결정 — 순수 함수(테스트용) */
    enum Decision { NOTHING, RENAME, REFUSE_BOTH_NO_HISTORY, WARN_LEGACY_LEFTOVER }

    static Decision decide(boolean legacyExists, boolean newExists, boolean newHasHistory) {
        if (!legacyExists) return Decision.NOTHING;
        if (!newExists) return Decision.RENAME;
        return newHasHistory ? Decision.WARN_LEGACY_LEFTOVER : Decision.REFUSE_BOTH_NO_HISTORY;
    }

    /** {@code CoreErrorCode} 이름들 — 순수 함수가 Flyway 클래스를 끌어오지 않도록 문자열로 둔다(idem-common 에서 flyway 는 compileOnly) */
    static final String CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH";
    /** 아직 적용되지 않은(pending) 마이그레이션 — migrate 전 validate 에서는 정상이라 무시한다(새 설치·버전 업그레이드) */
    static final List<String> PENDING = List.of("RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED", "RESOLVED_REPEATABLE_MIGRATION_NOT_APPLIED");

    enum Verdict { OK, REPAIR_CHECKSUM, REFUSE }

    /** 검증 오류 코드 → 결정(순수 함수): pending 은 무시, 체크섬 불일치만 남으면 repair, 그 밖의 오류(빠짐·실패·타입 변경)는 거부 */
    static Verdict classify(List<String> errorCodes) {
        List<String> real = errorCodes.stream().filter(c -> !PENDING.contains(c)).toList();
        if (real.isEmpty()) return Verdict.OK;
        return real.stream().allMatch(CHECKSUM_MISMATCH::equals) ? Verdict.REPAIR_CHECKSUM : Verdict.REFUSE;
    }

    /** 검증 결과로 repair 여부 결정 — 체크섬 불일치만 있을 때 true(순수 함수) */
    static boolean onlyChecksumMismatches(List<String> errorCodes) {
        return classify(errorCodes) == Verdict.REPAIR_CHECKSUM;
    }

    static boolean legacyRepairAllowed(String envValue) {
        return envValue == null || envValue.isBlank() || !envValue.trim().equalsIgnoreCase("false");
    }

    /** Flyway 가 관리하는 스키마마다 구 스키마가 남아 있으면 rename 한 뒤 검증 → (필요할 때만) repair → migrate. {@code FlywayMigrationStrategy} 가 쓴다 */
    public static void migrate(Flyway flyway) {
        boolean renamed = false;
        for (String schema : flyway.getConfiguration().getSchemas()) {
            renamed |= renameIfLegacy(flyway.getConfiguration().getDataSource(), schema);
        }
        if (renamed) {
            log.warn("[Idem 개명] 구 스키마를 새 이름으로 옮겼습니다 — Flyway 이력의 체크섬을 repair 한 뒤 migrate 합니다");
        }
        ValidateResult validation = flyway.validateWithResult();
        if (!validation.validationSuccessful) {
            List<String> codes = new ArrayList<>();
            List<String> details = new ArrayList<>();
            if (validation.invalidMigrations != null) {
                for (ValidateOutput o : validation.invalidMigrations) {
                    codes.add(o.errorDetails != null && o.errorDetails.errorCode != null ? String.valueOf(o.errorDetails.errorCode) : "UNKNOWN");
                    details.add(o.version + " " + (o.errorDetails != null ? o.errorDetails.errorMessage : ""));
                }
            }
            Verdict verdict = classify(codes);
            boolean allowed = legacyRepairAllowed(System.getenv(LEGACY_REPAIR_ENV));
            if (verdict == Verdict.REPAIR_CHECKSUM && allowed) {
                log.warn("[Idem 개명] 적용된 마이그레이션의 체크섬이 파일과 다릅니다(개명 전 적용분) — 1회 repair 합니다: {}", details);
                flyway.repair();
            } else if (verdict != Verdict.OK) {
                throw new IllegalStateException("[Idem] Flyway 검증 실패 — repair 하지 않습니다("
                        + (verdict == Verdict.REPAIR_CHECKSUM ? LEGACY_REPAIR_ENV + "=false" : "체크섬 불일치 이외의 오류")
                        + "): " + validation.getAllErrorMessages());
            }
            // verdict OK: pending 뿐 — 아래 migrate 가 적용한다
        }
        flyway.migrate();
    }

    /** @return rename 했으면 true. 구·신 스키마가 둘 다 있고 새 쪽에 이력이 없으면 {@link IllegalStateException} (기동 거부) */
    static boolean renameIfLegacy(DataSource dataSource, String newSchema) {
        String legacy = LEGACY.get(newSchema);
        if (legacy == null) return false;
        try (Connection c = dataSource.getConnection()) {
            boolean legacyExists = schemaExists(c, legacy);
            boolean newExists = schemaExists(c, newSchema);
            switch (decide(legacyExists, newExists, newExists && tableExists(c, newSchema, "flyway_schema_history"))) {
                case NOTHING -> { return false; }
                case WARN_LEGACY_LEFTOVER -> {
                    log.warn("[Idem 개명] 구 스키마 {} 가 새 스키마 {} 옆에 아직 남아 있습니다 — 이관이 끝났는지 확인한 뒤 DROP SCHEMA {} 하세요", legacy, newSchema, legacy);
                    return false;
                }
                case REFUSE_BOTH_NO_HISTORY -> throw new IllegalStateException("[Idem 개명] 구 스키마 " + legacy + " 와 새 스키마 " + newSchema
                        + " 가 둘 다 있고 새 쪽에 Flyway 이력이 없습니다 — 새 스키마가 먼저 만들어져 구 데이터가 고아가 됩니다. 앱을 내리고 새(빈) 스키마를 DROP 한 뒤"
                        + " 다시 기동하거나 scripts/upgrade/rename-db-1.0.sh 를 실행하세요 (docs/install.md §7)");
                case RENAME -> {
                    try (Statement st = c.createStatement()) {
                        st.execute("ALTER SCHEMA \"" + legacy + "\" RENAME TO \"" + newSchema + "\"");
                    }
                    log.warn("[Idem 개명] 스키마 {} → {} (docs/naming.md §3, 5단계)", legacy, newSchema);
                    return true;
                }
            }
            return false;
        } catch (SQLException e) {
            throw new IllegalStateException("[Idem 개명] 스키마 " + legacy + " → " + newSchema + " rename 실패", e);
        }
    }

    /** {@code pg_namespace} — information_schema.schemata 는 권한 있는 스키마만 보여 바깥 PostgreSQL 에서 구 스키마를 "없음"으로 오판할 수 있다 */
    private static boolean schemaExists(Connection c, String schema) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = ?")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static boolean tableExists(Connection c, String schema, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM pg_catalog.pg_class t JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace WHERE n.nspname = ? AND t.relname = ? AND t.relkind IN ('r','p')")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }
}

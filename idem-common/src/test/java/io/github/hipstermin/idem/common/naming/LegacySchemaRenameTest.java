package io.github.hipstermin.idem.common.naming;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LegacySchemaRename — 1.0.1 (3차 점검 H7·M1) 결정 규칙")
class LegacySchemaRenameTest {

    @Test
    @DisplayName("구 스키마 없음 → 아무것도; 구만 있음 → rename; 둘 다 + 새 쪽 이력 없음 → 기동 거부; 둘 다 + 이력 있음 → 경고")
    void decide() {
        assertThat(LegacySchemaRename.decide(false, false, false)).isEqualTo(LegacySchemaRename.Decision.NOTHING);
        assertThat(LegacySchemaRename.decide(false, true, true)).isEqualTo(LegacySchemaRename.Decision.NOTHING);
        assertThat(LegacySchemaRename.decide(true, false, false)).isEqualTo(LegacySchemaRename.Decision.RENAME);
        assertThat(LegacySchemaRename.decide(true, true, false)).isEqualTo(LegacySchemaRename.Decision.REFUSE_BOTH_NO_HISTORY);
        assertThat(LegacySchemaRename.decide(true, true, true)).isEqualTo(LegacySchemaRename.Decision.WARN_LEGACY_LEFTOVER);
    }

    @Test
    @DisplayName("repair 는 체크섬 불일치만 있을 때 — 빠진/실패한 마이그레이션이 섞이면 안 한다, 검증 통과면 안 한다")
    void repairOnlyForChecksumMismatch() {
        assertThat(LegacySchemaRename.onlyChecksumMismatches(List.of("CHECKSUM_MISMATCH", "CHECKSUM_MISMATCH"))).isTrue();
        assertThat(LegacySchemaRename.onlyChecksumMismatches(List.of("CHECKSUM_MISMATCH", "FAILED_REPEATABLE_MIGRATION"))).isFalse();
        assertThat(LegacySchemaRename.onlyChecksumMismatches(List.of("RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED"))).isFalse();
        assertThat(LegacySchemaRename.onlyChecksumMismatches(List.of())).isFalse();
    }

    @Test
    @DisplayName("pending(아직 적용 안 된) 마이그레이션은 정상 — 새 설치·버전 업그레이드에서 migrate 가 적용한다 (CI 설치 스모크가 잡은 회귀)")
    void pendingIsNotAnError() {
        assertThat(LegacySchemaRename.classify(List.of())).isEqualTo(LegacySchemaRename.Verdict.OK);
        assertThat(LegacySchemaRename.classify(List.of("RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED", "RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED"))).isEqualTo(LegacySchemaRename.Verdict.OK);
        assertThat(LegacySchemaRename.classify(List.of("RESOLVED_REPEATABLE_MIGRATION_NOT_APPLIED"))).isEqualTo(LegacySchemaRename.Verdict.OK);
        assertThat(LegacySchemaRename.classify(List.of("CHECKSUM_MISMATCH", "RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED"))).isEqualTo(LegacySchemaRename.Verdict.REPAIR_CHECKSUM);
        assertThat(LegacySchemaRename.classify(List.of("CHECKSUM_MISMATCH", "FAILED_VERSIONED_MIGRATION"))).isEqualTo(LegacySchemaRename.Verdict.REFUSE);
        assertThat(LegacySchemaRename.classify(List.of("APPLIED_VERSIONED_MIGRATION_NOT_RESOLVED"))).isEqualTo(LegacySchemaRename.Verdict.REFUSE);
    }

    @Test
    @DisplayName("IDEM_NAMING_LEGACY_REPAIR: 없음/빈값/true 는 허용, false 만 거부")
    void legacyRepairSwitch() {
        assertThat(LegacySchemaRename.legacyRepairAllowed(null)).isTrue();
        assertThat(LegacySchemaRename.legacyRepairAllowed("")).isTrue();
        assertThat(LegacySchemaRename.legacyRepairAllowed("true")).isTrue();
        assertThat(LegacySchemaRename.legacyRepairAllowed("false")).isFalse();
        assertThat(LegacySchemaRename.legacyRepairAllowed(" FALSE ")).isFalse();
    }

    @Test
    void legacyMap() {
        assertThat(LegacySchemaRename.LEGACY).containsEntry("idem_hub", "ido").containsEntry("idem_gate", "qsign")
                .containsEntry("idem_registry", "qim").containsEntry("idem_authz", "authz");
    }
}

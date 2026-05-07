package kr.go.smes.ido.qim.sp.infrastructure;

import kr.go.smes.ido.qim.sp.domain.InstMbrIdMapping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * instMbrId 매핑 Repository
 *
 * ido.inst_mbr_id_mapping 테이블 CRUD
 * Q-IM SP 수신 API 처리 시 instMbrId ↔ qimUserId 매핑 관리
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class InstMbrIdMappingRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String SCHEMA = "ido";

    // ── 조회 ────────────────────────────────────────────────────────────────

    public Optional<InstMbrIdMapping> findByInstMbrId(String instMbrId) {
        try {
            InstMbrIdMapping mapping = jdbcTemplate.queryForObject(
                    "SELECT * FROM " + SCHEMA + ".inst_mbr_id_mapping WHERE inst_mbr_id = ?",
                    new InstMbrIdMappingRowMapper(), instMbrId);
            return Optional.ofNullable(mapping);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<InstMbrIdMapping> findByQimUserId(String qimUserId) {
        try {
            InstMbrIdMapping mapping = jdbcTemplate.queryForObject(
                    "SELECT * FROM " + SCHEMA + ".inst_mbr_id_mapping WHERE qim_user_id = ?",
                    new InstMbrIdMappingRowMapper(), qimUserId);
            return Optional.ofNullable(mapping);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<InstMbrIdMapping> findByIdentifierHash(String identifierHash) {
        try {
            InstMbrIdMapping mapping = jdbcTemplate.queryForObject(
                    "SELECT * FROM " + SCHEMA + ".inst_mbr_id_mapping WHERE identifier_hash = ?",
                    new InstMbrIdMappingRowMapper(), identifierHash);
            return Optional.ofNullable(mapping);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<InstMbrIdMapping> findByMbrUuid(String mbrUuid) {
        try {
            InstMbrIdMapping mapping = jdbcTemplate.queryForObject(
                    "SELECT * FROM " + SCHEMA + ".inst_mbr_id_mapping WHERE mbr_uuid = ?",
                    new InstMbrIdMappingRowMapper(), mbrUuid);
            return Optional.ofNullable(mapping);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    // ── 저장 ────────────────────────────────────────────────────────────────

    public void save(InstMbrIdMapping mapping) {
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + ".inst_mbr_id_mapping "
                + "(inst_mbr_id, qim_user_id, mbr_uuid, mbr_no, identifier_hash, "
                + " member_type, status, reg_mode, registered_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW()) "
                + "ON CONFLICT (inst_mbr_id) DO UPDATE SET "
                + "  mbr_uuid         = EXCLUDED.mbr_uuid, "
                + "  mbr_no           = EXCLUDED.mbr_no, "
                + "  identifier_hash  = EXCLUDED.identifier_hash, "
                + "  status           = EXCLUDED.status, "
                + "  updated_at       = NOW()",
                mapping.getInstMbrId(),
                mapping.getQimUserId(),
                mapping.getMbrUuid(),
                mapping.getMbrNo(),
                mapping.getIdentifierHash(),
                mapping.getMemberType() != null ? mapping.getMemberType().name() : "PERSONAL",
                mapping.getStatus() != null ? mapping.getStatus().name() : "ACTIVE",
                mapping.getRegMode() != null ? mapping.getRegMode().name() : null,
                mapping.getRegisteredAt() != null
                        ? Timestamp.from(mapping.getRegisteredAt()) : Timestamp.from(Instant.now())
        );
    }

    public void markWithdrawn(String instMbrId) {
        jdbcTemplate.update(
                "UPDATE " + SCHEMA + ".inst_mbr_id_mapping "
                + "SET status = 'WITHDRAWN', withdrawn_at = NOW(), updated_at = NOW() "
                + "WHERE inst_mbr_id = ?",
                instMbrId);
    }

    // ── RowMapper ────────────────────────────────────────────────────────────

    private static class InstMbrIdMappingRowMapper implements RowMapper<InstMbrIdMapping> {
        @Override
        public InstMbrIdMapping mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp withdrawnAt = rs.getTimestamp("withdrawn_at");
            Timestamp registeredAt = rs.getTimestamp("registered_at");

            String memberTypeStr = rs.getString("member_type");
            String statusStr = rs.getString("status");
            String regModeStr = rs.getString("reg_mode");

            return InstMbrIdMapping.builder()
                    .instMbrId(rs.getString("inst_mbr_id"))
                    .qimUserId(rs.getString("qim_user_id"))
                    .mbrUuid(rs.getString("mbr_uuid"))
                    .mbrNo(rs.getString("mbr_no"))
                    .identifierHash(rs.getString("identifier_hash"))
                    .memberType(memberTypeStr != null
                            ? InstMbrIdMapping.MemberType.valueOf(memberTypeStr) : null)
                    .status(statusStr != null
                            ? InstMbrIdMapping.MappingStatus.valueOf(statusStr) : null)
                    .regMode(regModeStr != null
                            ? InstMbrIdMapping.RegMode.valueOf(regModeStr) : null)
                    .registeredAt(registeredAt != null ? registeredAt.toInstant() : null)
                    .withdrawnAt(withdrawnAt != null ? withdrawnAt.toInstant() : null)
                    .build();
        }
    }
}

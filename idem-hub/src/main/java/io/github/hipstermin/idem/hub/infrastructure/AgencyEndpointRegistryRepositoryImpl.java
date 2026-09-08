package io.github.hipstermin.idem.hub.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * AgencyEndpointRegistryRepository JdbcTemplate 구현체
 *
 * <p>JPA 대신 JdbcTemplate 선택 이유:
 * <ul>
 *   <li>복합 PK (agency_code, endpoint_type) 엔티티의 JPA 복잡도 회피</li>
 *   <li>idx_aer_active_type 부분 인덱스(WHERE is_active=TRUE) 직접 활용</li>
 *   <li>Upsert (INSERT … ON CONFLICT DO UPDATE) 직접 제어</li>
 * </ul>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class AgencyEndpointRegistryRepositoryImpl implements AgencyEndpointRegistryRepository {

    private final JdbcTemplate jdbcTemplate;

    // ─────────────────────────────────────────────────────────────────────
    // 조회
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public Optional<AgencyEndpointRecord> findByAgencyAndType(String agencyCode, String endpointType) {
        String sql = """
                SELECT agency_code, endpoint_type, endpoint_url, http_method,
                       auth_type, auth_credential_ref, timeout_ms, is_active,
                       note, created_at, updated_at
                FROM   ido.agency_endpoint_registry
                WHERE  agency_code    = ?
                  AND  endpoint_type  = ?
                  AND  is_active      = TRUE
                """;
        List<AgencyEndpointRecord> result = jdbcTemplate.query(sql, new EndpointRowMapper(), agencyCode, endpointType);
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    @Override
    public List<AgencyEndpointRecord> findAllActiveByType(String endpointType) {
        // idx_aer_active_type 인덱스(endpoint_type, is_active WHERE is_active=TRUE) 활용
        String sql = """
                SELECT agency_code, endpoint_type, endpoint_url, http_method,
                       auth_type, auth_credential_ref, timeout_ms, is_active,
                       note, created_at, updated_at
                FROM   ido.agency_endpoint_registry
                WHERE  endpoint_type = ?
                  AND  is_active     = TRUE
                ORDER BY agency_code ASC
                """;
        List<AgencyEndpointRecord> records = jdbcTemplate.query(sql, new EndpointRowMapper(), endpointType);
        log.debug("[AgencyEndpointRegistry] 활성 엔드포인트 조회: type={} count={}", endpointType, records.size());
        return records;
    }

    @Override
    public List<AgencyEndpointRecord> findAllActiveByAgency(String agencyCode) {
        String sql = """
                SELECT agency_code, endpoint_type, endpoint_url, http_method,
                       auth_type, auth_credential_ref, timeout_ms, is_active,
                       note, created_at, updated_at
                FROM   ido.agency_endpoint_registry
                WHERE  agency_code = ?
                  AND  is_active   = TRUE
                ORDER BY endpoint_type ASC
                """;
        return jdbcTemplate.query(sql, new EndpointRowMapper(), agencyCode);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 쓰기
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void upsert(AgencyEndpointRecord record) {
        String sql = """
                INSERT INTO ido.agency_endpoint_registry
                    (agency_code, endpoint_type, endpoint_url, http_method,
                     auth_type, auth_credential_ref, timeout_ms, is_active, note,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                ON CONFLICT (agency_code, endpoint_type)
                DO UPDATE SET
                    endpoint_url       = EXCLUDED.endpoint_url,
                    http_method        = EXCLUDED.http_method,
                    auth_type          = EXCLUDED.auth_type,
                    auth_credential_ref = EXCLUDED.auth_credential_ref,
                    timeout_ms         = EXCLUDED.timeout_ms,
                    is_active          = EXCLUDED.is_active,
                    note               = EXCLUDED.note,
                    updated_at         = NOW()
                """;
        jdbcTemplate.update(sql,
                record.getAgencyCode(),
                record.getEndpointType(),
                record.getEndpointUrl(),
                record.getHttpMethod(),
                record.getAuthType(),
                record.getAuthCredentialRef(),
                record.getTimeoutMs(),
                record.isActive(),
                record.getNote()
        );
        log.debug("[AgencyEndpointRegistry] Upsert: agencyCode={} type={}", record.getAgencyCode(), record.getEndpointType());
    }

    @Override
    public void deactivate(String agencyCode, String endpointType) {
        jdbcTemplate.update("""
                UPDATE ido.agency_endpoint_registry
                SET    is_active  = FALSE,
                       updated_at = NOW()
                WHERE  agency_code   = ?
                  AND  endpoint_type = ?
                """, agencyCode, endpointType);
        log.info("[AgencyEndpointRegistry] 엔드포인트 비활성화: agencyCode={} type={}", agencyCode, endpointType);
    }

    // ─────────────────────────────────────────────────────────────────────
    // RowMapper
    // ─────────────────────────────────────────────────────────────────────

    private static class EndpointRowMapper implements RowMapper<AgencyEndpointRecord> {
        @Override
        public AgencyEndpointRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return AgencyEndpointRecord.builder()
                    .agencyCode(rs.getString("agency_code"))
                    .endpointType(rs.getString("endpoint_type"))
                    .endpointUrl(rs.getString("endpoint_url"))
                    .httpMethod(rs.getString("http_method"))
                    .authType(rs.getString("auth_type"))
                    .authCredentialRef(rs.getString("auth_credential_ref"))
                    .timeoutMs(rs.getInt("timeout_ms"))
                    .active(rs.getBoolean("is_active"))
                    .note(rs.getString("note"))
                    .createdAt(rs.getTimestamp("created_at") != null
                            ? rs.getTimestamp("created_at").toInstant() : Instant.now())
                    .updatedAt(rs.getTimestamp("updated_at") != null
                            ? rs.getTimestamp("updated_at").toInstant() : Instant.now())
                    .build();
        }
    }
}

package io.github.hipstermin.idem.hub.provision;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * ProvisioningOutboxRepository JdbcTemplate 구현체
 *
 * <p>핵심 설계 결정:
 * <ul>
 *   <li>FOR UPDATE SKIP LOCKED: ProvisioningOutboxRelay(@Scheduled) 다중 인스턴스 간 중복 처리 방지</li>
 *   <li>지수 백오프 DB 계산: retry_count → next_retry_at을 DB 내에서 CASE WHEN으로 계산 (클럭 스큐 회피)</li>
 *   <li>ON CONFLICT DO NOTHING: (idempotency_key, agency_code) UNIQUE → 멱등 삽입 보장</li>
 * </ul>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ProvisioningOutboxRepositoryImpl implements ProvisioningOutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    // ─────────────────────────────────────────────────────────────────────
    // 쓰기
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public int insert(ProvisioningOutboxRecord record) {
        String sql = """
                INSERT INTO ido.provisioning_outbox
                    (id, qim_user_id, agency_code, event_type, payload,
                     idempotency_key, status, retry_count, max_retry,
                     next_retry_at, created_at, correlation_id, source_event_id)
                VALUES (gen_random_uuid(), ?, ?, ?, ?::jsonb,
                        ?, 'PENDING', 0, ?,
                        NOW(), NOW(), ?, ?)
                ON CONFLICT (idempotency_key, agency_code) DO NOTHING
                """;
        try {
            int rows = jdbcTemplate.update(sql,
                    record.getQimUserId(),
                    record.getAgencyCode(),
                    record.getEventType(),
                    record.getPayloadJson(),
                    record.getIdempotencyKey(),
                    record.getMaxRetry(),
                    record.getCorrelationId(),
                    record.getSourceEventId()
            );
            if (rows == 0) {
                log.debug("[ProvisioningOutbox] 중복 삽입 무시 (UNIQUE 충돌): idempotencyKey={} agencyCode={}",
                        record.getIdempotencyKey(), record.getAgencyCode());
            }
            return rows;
        } catch (DuplicateKeyException e) {
            // ON CONFLICT DO NOTHING 에도 불구하고 race condition 방어
            log.debug("[ProvisioningOutbox] DuplicateKey 방어: idempotencyKey={}", record.getIdempotencyKey());
            return 0;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 조회
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public List<ProvisioningOutboxRecord> findPendingBatch(int batchSize) {
        // idx_prov_outbox_pending 인덱스 활용 (next_retry_at ASC WHERE status='PENDING')
        String sql = """
                SELECT id, qim_user_id, agency_code, event_type,
                       payload::text AS payload_json,
                       idempotency_key, status, retry_count, max_retry,
                       next_retry_at, created_at, last_attempted_at, completed_at,
                       error_message, correlation_id, source_event_id
                FROM   ido.provisioning_outbox
                WHERE  status         = 'PENDING'
                  AND  next_retry_at <= NOW()
                ORDER BY next_retry_at ASC
                LIMIT  ?
                FOR UPDATE SKIP LOCKED
                """;
        List<ProvisioningOutboxRecord> batch = jdbcTemplate.query(sql, new OutboxRowMapper(), batchSize);
        log.debug("[ProvisioningOutbox] PENDING 배치 조회: {} 건", batch.size());
        return batch;
    }

    @Override
    public int countDeadLetterByUser(String qimUserId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ido.provisioning_outbox
                WHERE  qim_user_id = ?
                  AND  status      = 'DEAD_LETTER'
                """, Integer.class, qimUserId);
        return count != null ? count : 0;
    }

    @Override
    public int countBySourceEventId(String sourceEventId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ido.provisioning_outbox
                WHERE  source_event_id = ?
                """, Integer.class, sourceEventId);
        return count != null ? count : 0;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 상태 변경
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void markCompleted(String id) {
        jdbcTemplate.update("""
                UPDATE ido.provisioning_outbox
                SET    status            = 'COMPLETED',
                       completed_at      = NOW(),
                       last_attempted_at = NOW()
                WHERE  id = ?
                """, id);
        log.debug("[ProvisioningOutbox] COMPLETED: id={}", id);
    }

    @Override
    public void incrementRetryWithBackoff(String id, String errorMessage) {
        // 지수 백오프: retry_count 0→1분, 1→5분, ≥2→30분
        // DB CASE WHEN으로 계산 → 서버 클럭 스큐 없음, 원자적 갱신
        String sql = """
                UPDATE ido.provisioning_outbox
                SET    retry_count       = retry_count + 1,
                       last_attempted_at = NOW(),
                       error_message     = ?,
                       next_retry_at     = NOW() + CASE
                           WHEN retry_count = 0 THEN INTERVAL '1 minute'
                           WHEN retry_count = 1 THEN INTERVAL '5 minutes'
                           ELSE                      INTERVAL '30 minutes'
                       END
                WHERE  id = ?
                """;
        jdbcTemplate.update(sql, truncate(errorMessage, 2000), id);
        log.debug("[ProvisioningOutbox] 재시도 예약 (지수 백오프): id={}", id);
    }

    @Override
    public void markDeadLetter(String id, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE ido.provisioning_outbox
                SET    status            = 'DEAD_LETTER',
                       last_attempted_at = NOW(),
                       error_message     = ?
                WHERE  id = ?
                """, truncate(errorMessage, 2000), id);
        log.error("[ProvisioningOutbox] DEAD_LETTER 전환: id={} error={}", id, errorMessage);
    }

    @Override
    public void reschedule(String id, Instant nextRetryAt) {
        jdbcTemplate.update("""
                UPDATE ido.provisioning_outbox
                SET    next_retry_at = ?,
                       status        = 'PENDING'
                WHERE  id = ?
                """, Timestamp.from(nextRetryAt), id);
        log.info("[ProvisioningOutbox] 수동 재시도 예약: id={} nextRetryAt={}", id, nextRetryAt);
    }

    @Override
    public String findIdByIdempotencyKeyAndAgency(String idempotencyKey, String agencyCode) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT id FROM ido.provisioning_outbox
                    WHERE  idempotency_key = ?
                      AND  agency_code     = ?
                    """, String.class, idempotencyKey, agencyCode);
        } catch (Exception e) {
            log.debug("[ProvisioningOutbox] ID 조회 실패: idempotencyKey={} agencyCode={}: {}",
                    idempotencyKey, agencyCode, e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // RowMapper
    // ─────────────────────────────────────────────────────────────────────

    private static class OutboxRowMapper implements RowMapper<ProvisioningOutboxRecord> {
        @Override
        public ProvisioningOutboxRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return ProvisioningOutboxRecord.builder()
                    .id(rs.getString("id"))
                    .qimUserId(rs.getString("qim_user_id"))
                    .agencyCode(rs.getString("agency_code"))
                    .eventType(rs.getString("event_type"))
                    .payloadJson(rs.getString("payload_json"))
                    .idempotencyKey(rs.getString("idempotency_key"))
                    .status(rs.getString("status"))
                    .retryCount(rs.getInt("retry_count"))
                    .maxRetry(rs.getInt("max_retry"))
                    .nextRetryAt(toInstant(rs, "next_retry_at"))
                    .createdAt(toInstant(rs, "created_at"))
                    .lastAttemptedAt(toInstant(rs, "last_attempted_at"))
                    .completedAt(toInstant(rs, "completed_at"))
                    .errorMessage(rs.getString("error_message"))
                    .correlationId(rs.getString("correlation_id"))
                    .sourceEventId(rs.getString("source_event_id"))
                    .build();
        }

        private Instant toInstant(ResultSet rs, String col) throws SQLException {
            Timestamp ts = rs.getTimestamp(col);
            return ts != null ? ts.toInstant() : null;
        }
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }
}

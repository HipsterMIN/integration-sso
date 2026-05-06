package kr.go.smes.ido.infrastructure.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * ido.outbox 테이블 JDBC 리포지토리
 *
 * <p>q-sign의 QSignOutboxRepository에 대응하는 ido 측 구현.
 * JPA 대신 JdbcTemplate 사용 — JSONB 컬럼 및 직접 SQL 제어 목적.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class IdoOutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * PENDING 상태 레코드를 created_at 오름차순으로 batchSize 개 조회.
     * idx_ido_outbox_pending 인덱스(status, created_at WHERE status='PENDING') 활용.
     */
    public List<IdoOutboxRecord> findPendingBatch(int batchSize) {
        String sql = """
                SELECT event_id, event_type, partition_key, aggregate_id,
                       event_version, payload::text, topic, status,
                       retry_count, error_message, created_at, published_at
                FROM ido.outbox
                WHERE status = 'PENDING'
                ORDER BY created_at ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """;
        return jdbcTemplate.query(sql, new OutboxRowMapper(), batchSize);
    }

    /**
     * 발행 성공 → PUBLISHED 로 상태 변경
     */
    public void markPublished(String eventId) {
        jdbcTemplate.update("""
                UPDATE ido.outbox
                SET status = 'PUBLISHED', published_at = NOW()
                WHERE event_id = ?
                """, eventId);
    }

    /**
     * 발행 실패 → retry_count 증가 + error_message 갱신.
     * 최대 재시도 초과 시 FAILED 로 변경.
     */
    public void markFailed(String eventId, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE ido.outbox
                SET status = 'FAILED',
                    error_message = ?,
                    retry_count = retry_count + 1
                WHERE event_id = ?
                """, truncate(errorMessage, 2000), eventId);
    }

    /**
     * 재시도 카운트 증가 (PENDING 유지)
     */
    public void incrementRetry(String eventId, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE ido.outbox
                SET retry_count   = retry_count + 1,
                    error_message = ?
                WHERE event_id = ?
                """, truncate(errorMessage, 2000), eventId);
    }

    // ── Row Mapper ───────────────────────────────────────────────────────

    private static class OutboxRowMapper implements RowMapper<IdoOutboxRecord> {
        @Override
        public IdoOutboxRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return IdoOutboxRecord.builder()
                    .eventId(rs.getString("event_id"))
                    .eventType(rs.getString("event_type"))
                    .partitionKey(rs.getString("partition_key"))
                    .aggregateId(rs.getString("aggregate_id"))
                    .eventVersion(rs.getLong("event_version"))
                    .payload(rs.getString("payload"))
                    .topic(rs.getString("topic"))
                    .status(rs.getString("status"))
                    .retryCount(rs.getInt("retry_count"))
                    .errorMessage(rs.getString("error_message"))
                    .createdAt(rs.getTimestamp("created_at") != null
                            ? rs.getTimestamp("created_at").toInstant() : Instant.now())
                    .publishedAt(rs.getTimestamp("published_at") != null
                            ? rs.getTimestamp("published_at").toInstant() : null)
                    .build();
        }
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }
}

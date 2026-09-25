package io.github.hipstermin.idem.hub.infrastructure.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * idem.hub.outbox 테이블 JDBC 리포지토리
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
     *
     * <p>QIM-OUTBOX-SPEC-001 대응: {@code next_retry_at} 컬럼 추가로 지수 백오프 지원.
     * {@code next_retry_at IS NULL} (최초 시도) 또는
     * {@code next_retry_at <= NOW()} (재시도 대기 완료) 조건을 추가하여
     * 실패한 레코드를 즉시 재조회하는 문제를 해결한다.
     *
     * <p>idx_ido_outbox_pending 인덱스(status, created_at WHERE status='PENDING') 활용.
     */
    public List<IdoOutboxRecord> findPendingBatch(int batchSize) {
        String sql = """
                SELECT event_id, event_type, partition_key, aggregate_id,
                       event_version, payload::text, topic, status,
                       retry_count, error_message, created_at, published_at,
                       next_retry_at
                FROM idem_hub.outbox
                WHERE status = 'PENDING'
                  AND (next_retry_at IS NULL OR next_retry_at <= NOW())
                ORDER BY created_at ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """;
        return jdbcTemplate.query(sql, new OutboxRowMapper(), batchSize);
    }

    /**
     * 지정 토픽을 제외한 PENDING 레코드 조회 — IdoOutboxRelay 전용.
     *
     * <p>idem.registry.user.events는 {@link QimOutboxRelay}가 전담하므로 IdoOutboxRelay에서 제외한다.
     * 새로운 전용 릴레이 추가 시 excludedTopics Set에 토픽명을 추가하면 된다.
     *
     * <p>IN 절 바인딩을 위해 Spring JdbcTemplate의 {@code queryForList} 대신
     * NamedParameterJdbcTemplate 사용을 권장하나, 현재 JdbcTemplate 기반으로
     * 동적 placeholder 생성 방식을 사용한다.
     */
    public List<IdoOutboxRecord> findPendingBatchExcludingTopics(
            java.util.Set<String> excludedTopics, int batchSize) {

        if (excludedTopics == null || excludedTopics.isEmpty()) {
            return findPendingBatch(batchSize);
        }

        // IN (?, ?, ...) 동적 생성
        String placeholders = excludedTopics.stream()
                .map(t -> "?")
                .collect(java.util.stream.Collectors.joining(", "));

        String sql = """
                SELECT event_id, event_type, partition_key, aggregate_id,
                       event_version, payload::text, topic, status,
                       retry_count, error_message, created_at, published_at,
                       next_retry_at
                FROM idem_hub.outbox
                WHERE status = 'PENDING'
                  AND topic NOT IN (%s)
                  AND (next_retry_at IS NULL OR next_retry_at <= NOW())
                ORDER BY created_at ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """.formatted(placeholders);

        Object[] params = new Object[excludedTopics.size() + 1];
        int i = 0;
        for (String t : excludedTopics) {
            params[i++] = t;
        }
        params[i] = batchSize;

        return jdbcTemplate.query(sql, new OutboxRowMapper(), params);
    }

    /**
     * 특정 토픽의 PENDING 레코드만 조회 — QimOutboxRelay 전용.
     *
     * <p>IdoOutboxRelay(idem.gate.auth.events)와 QimOutboxRelay(idem.registry.user.events)가
     * 동일한 {@code idem.hub.outbox} 테이블을 공유하므로 토픽 필터로 구분한다.
     * 이를 통해 두 릴레이가 서로의 레코드를 처리하지 않는다.
     */
    public List<IdoOutboxRecord> findPendingBatchByTopic(String topic, int batchSize) {
        String sql = """
                SELECT event_id, event_type, partition_key, aggregate_id,
                       event_version, payload::text, topic, status,
                       retry_count, error_message, created_at, published_at,
                       next_retry_at
                FROM idem_hub.outbox
                WHERE status = 'PENDING'
                  AND topic  = ?
                  AND (next_retry_at IS NULL OR next_retry_at <= NOW())
                ORDER BY created_at ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """;
        return jdbcTemplate.query(sql, new OutboxRowMapper(), topic, batchSize);
    }

    /**
     * 발행 성공 → PUBLISHED 로 상태 변경
     */
    public void markPublished(String eventId) {
        jdbcTemplate.update("""
                UPDATE idem_hub.outbox
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
                UPDATE idem_hub.outbox
                SET status = 'FAILED',
                    error_message = ?,
                    retry_count = retry_count + 1
                WHERE event_id = ?
                """, truncate(errorMessage, 2000), eventId);
    }

    /**
     * 재시도 카운트 증가 + 지수 백오프 next_retry_at 설정 (PENDING 유지).
     *
     * <p>백오프 공식: {@code NOW() + 2^retryCount 초}
     * <pre>
     *   retry 1 → +2s
     *   retry 2 → +4s
     *   retry 3 → +8s (이후 markFailed로 전환)
     * </pre>
     *
     * <p>이전 {@code incrementRetry}는 즉시 재조회 문제가 있었음.
     * {@code next_retry_at}을 설정하여 {@code findPendingBatch}의
     * {@code next_retry_at <= NOW()} 조건에서 제외된다.
     */
    public void incrementRetryWithBackoff(String eventId, String errorMessage, int currentRetryCount) {
        // 2^(currentRetryCount+1) 초 백오프 (최대 64초)
        long backoffSeconds = Math.min((long) Math.pow(2, currentRetryCount + 1), 64L);
        jdbcTemplate.update("""
                UPDATE idem_hub.outbox
                SET retry_count   = retry_count + 1,
                    error_message = ?,
                    next_retry_at = NOW() + (? || ' seconds')::interval
                WHERE event_id = ?
                """,
                truncate(errorMessage, 2000),
                backoffSeconds,
                eventId
        );
    }

    /**
     * @deprecated {@link #incrementRetryWithBackoff(String, String, int)} 사용 권장.
     *             백오프 없이 즉시 재조회되어 실패 폭풍(Thundering Herd) 유발 위험.
     */
    @Deprecated(since = "QIM-OUTBOX-SPEC-001", forRemoval = true)
    public void incrementRetry(String eventId, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE idem_hub.outbox
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
                    .nextRetryAt(rs.getTimestamp("next_retry_at") != null
                            ? rs.getTimestamp("next_retry_at").toInstant() : null)
                    .build();
        }
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }
}

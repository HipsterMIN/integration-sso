package io.github.hipstermin.idem.hub.gateway;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ido.gateway_inbound_audit JdbcTemplate 리포지토리
 *
 * <p>인바운드 이벤트 감사 이력 INSERT/조회/상태갱신.
 * DB UNIQUE(idempotency_key)가 Redis 장애 시 2차 중복 방어.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class GatewayInboundRepository {

    private final JdbcTemplate jdbcTemplate;

    // ─────────────────────────────────────────────────────────────────────
    // 쓰기
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 인바운드 이벤트 레코드 삽입
     * UNIQUE(idempotency_key) 충돌 시 0 반환 (ON CONFLICT DO NOTHING).
     *
     * @return 삽입된 행 수 (0이면 중복)
     */
    public int insert(GatewayInboundRecord record) {
        try {
            return jdbcTemplate.update("""
                    INSERT INTO ido.gateway_inbound_audit
                        (agency_code, event_type, idempotency_key, payload,
                         status, source_ip, correlation_id, received_at)
                    VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, NOW())
                    ON CONFLICT (idempotency_key) DO NOTHING
                    """,
                    record.getAgencyCode(),
                    record.getEventType(),
                    record.getIdempotencyKey(),
                    record.getPayloadJson(),
                    record.getStatus(),
                    record.getSourceIp(),
                    record.getCorrelationId()
            );
        } catch (DuplicateKeyException e) {
            log.debug("[GatewayInbound] 중복 멱등성 키: {}", record.getIdempotencyKey());
            return 0;
        }
    }

    /** 처리 완료 → PROCESSED 상태 갱신 */
    public void markProcessed(String idempotencyKey) {
        jdbcTemplate.update("""
                UPDATE ido.gateway_inbound_audit
                SET    status       = 'PROCESSED',
                       processed_at = NOW()
                WHERE  idempotency_key = ?
                """, idempotencyKey);
    }

    /** 처리 거부 → REJECTED 상태 갱신 */
    public void markRejected(String idempotencyKey, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE ido.gateway_inbound_audit
                SET    status        = 'REJECTED',
                       error_message = ?
                WHERE  idempotency_key = ?
                """, truncate(errorMessage, 2000), idempotencyKey);
    }

    /** 중복 수신 → DUPLICATE 상태 갱신 (Redis 통과했지만 DB에서 잡힌 경우) */
    public void markDuplicate(String idempotencyKey) {
        jdbcTemplate.update("""
                UPDATE ido.gateway_inbound_audit
                SET    status = 'DUPLICATE'
                WHERE  idempotency_key = ?
                """, idempotencyKey);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 조회
    // ─────────────────────────────────────────────────────────────────────

    /** 기관별 RECEIVED 미처리 건수 (모니터링용) */
    public int countUnprocessedByAgency(String agencyCode) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ido.gateway_inbound_audit
                WHERE  agency_code = ?
                  AND  status      = 'RECEIVED'
                """, Integer.class, agencyCode);
        return count != null ? count : 0;
    }

    /** 기관별 마지막 수신 시각 */
    public Optional<Instant> findLastReceivedAt(String agencyCode) {
        List<Instant> result = jdbcTemplate.query("""
                SELECT received_at FROM ido.gateway_inbound_audit
                WHERE  agency_code = ?
                ORDER BY received_at DESC
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getTimestamp("received_at") != null
                        ? rs.getTimestamp("received_at").toInstant() : null,
                agencyCode);
        return result.isEmpty() ? Optional.empty() : Optional.ofNullable(result.get(0));
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }
}

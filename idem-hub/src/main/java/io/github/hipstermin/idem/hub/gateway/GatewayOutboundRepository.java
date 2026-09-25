package io.github.hipstermin.idem.hub.gateway;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * idem_hub.gateway_outbound_audit JdbcTemplate 리포지토리
 *
 * <p>아웃바운드 발송 이력 INSERT/조회/상태갱신.
 * payload_hash = SHA-256(payload) — 평문 페이로드 저장 금지.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class GatewayOutboundRepository {

    private final JdbcTemplate jdbcTemplate;

    // ─────────────────────────────────────────────────────────────────────
    // 쓰기
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 아웃바운드 발송 이력 삽입 (SENT 상태)
     * UNIQUE(idempotency_key, agency_code) 충돌 시 ON CONFLICT DO NOTHING.
     *
     * @param agencyCode     대상 기관 코드
     * @param eventType      이벤트 타입
     * @param idempotencyKey 발송 멱등성 키
     * @param endpointUrl    실제 발송 URL
     * @param httpStatus     기관 응답 HTTP 상태 코드
     * @param payloadHash    SHA-256(payload) — 감사용
     * @param correlationId  흐름 추적 ID
     * @param delivered      발송 성공 여부
     */
    public void insert(String agencyCode, String eventType, String idempotencyKey,
                       String endpointUrl, int httpStatus, String payloadHash,
                       String correlationId, boolean delivered) {
        String status = delivered ? "DELIVERED" : "FAILED";
        jdbcTemplate.update("""
                INSERT INTO idem_hub.gateway_outbound_audit
                    (agency_code, event_type, idempotency_key, endpoint_url,
                     http_status, status, payload_hash, correlation_id,
                     sent_at, delivered_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?)
                ON CONFLICT (idempotency_key, agency_code) DO NOTHING
                """,
                agencyCode, eventType, idempotencyKey, endpointUrl,
                httpStatus, status, payloadHash, correlationId,
                delivered ? java.sql.Timestamp.from(Instant.now()) : null
        );
        log.debug("[GatewayOutbound] 이력 기록: agencyCode={} eventType={} status={} httpStatus={}",
                agencyCode, eventType, status, httpStatus);
    }

    /** FAILED 레코드 상태를 DELIVERED로 갱신 (수동 재처리용) */
    public void markDelivered(String idempotencyKey, String agencyCode) {
        jdbcTemplate.update("""
                UPDATE idem_hub.gateway_outbound_audit
                SET    status       = 'DELIVERED',
                       delivered_at = NOW()
                WHERE  idempotency_key = ?
                  AND  agency_code     = ?
                """, idempotencyKey, agencyCode);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 조회
    // ─────────────────────────────────────────────────────────────────────

    /** 기관별 마지막 발송 시각 */
    public Optional<Instant> findLastSentAt(String agencyCode) {
        List<Instant> result = jdbcTemplate.query("""
                SELECT sent_at FROM idem_hub.gateway_outbound_audit
                WHERE  agency_code = ?
                ORDER BY sent_at DESC
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getTimestamp("sent_at") != null
                        ? rs.getTimestamp("sent_at").toInstant() : null,
                agencyCode);
        return result.isEmpty() ? Optional.empty() : Optional.ofNullable(result.get(0));
    }

    /** 기관별 최근 FAILED 발송 건수 */
    public int countRecentFailedByAgency(String agencyCode) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM idem_hub.gateway_outbound_audit
                WHERE  agency_code = ?
                  AND  status      = 'FAILED'
                  AND  sent_at    >= NOW() - INTERVAL '24 hours'
                """, Integer.class, agencyCode);
        return count != null ? count : 0;
    }
}

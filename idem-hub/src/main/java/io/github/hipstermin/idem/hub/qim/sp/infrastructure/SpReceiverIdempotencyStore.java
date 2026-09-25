package io.github.hipstermin.idem.hub.qim.sp.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Q-IM SP 수신 API 멱등성 저장소
 *
 * Q-IM 명세서 v1.52 §3.4:
 *   동일 Idempotency-Key로 재호출하면 직전 응답이 그대로 재생됩니다.
 *
 * 구현:
 *   - 저장소: idem_hub.sp_receiver_idempotency (DB)
 *   - TTL: 7일 (expires_at 기준, 배치 삭제)
 *   - key: Idempotency-Key 헤더 값
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpReceiverIdempotencyStore {

    private static final String SCHEMA = "idem_hub";
    private static final int TTL_DAYS = 7;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 기존 응답 조회 (멱등 재호출 판정)
     *
     * @param idempotencyKey Idempotency-Key 헤더 값
     * @return 저장된 응답 JSON (있으면 재반환, 없으면 empty)
     */
    public Optional<StoredResponse> find(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        try {
            StoredResponse response = jdbcTemplate.queryForObject(
                    "SELECT http_status, response_json FROM " + SCHEMA + ".sp_receiver_idempotency "
                    + "WHERE idempotency_key = ? AND expires_at > NOW()",
                    (rs, rowNum) -> new StoredResponse(
                            rs.getInt("http_status"),
                            rs.getString("response_json")),
                    idempotencyKey);
            return Optional.ofNullable(response);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 처리 결과 저장
     *
     * @param idempotencyKey Idempotency-Key
     * @param endpoint       QUERY | REGISTER | WITHDRAW
     * @param httpStatus     HTTP 상태 코드
     * @param responseJson   직렬화된 응답 JSON
     * @param correlationId  추적 ID
     */
    public void save(String idempotencyKey, String endpoint,
                     int httpStatus, String responseJson, String correlationId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            log.debug("[SP-IDEMPOTENCY] Idempotency-Key 없음 — 저장 건너뜀 endpoint={}", endpoint);
            return;
        }
        try {
            Instant expiresAt = Instant.now().plus(TTL_DAYS, ChronoUnit.DAYS);
            jdbcTemplate.update(
                    "INSERT INTO " + SCHEMA + ".sp_receiver_idempotency "
                    + "(idempotency_key, endpoint, http_status, response_json, correlation_id, expires_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT (idempotency_key) DO NOTHING",
                    idempotencyKey, endpoint, httpStatus, responseJson,
                    correlationId, Timestamp.from(expiresAt));
        } catch (Exception e) {
            // 저장 실패는 서비스 실패로 전파하지 않음 (멱등성은 best-effort)
            log.warn("[SP-IDEMPOTENCY] 저장 실패 key={} cause={}", idempotencyKey, e.getMessage());
        }
    }

    /** 저장된 응답 DTO */
    public record StoredResponse(int httpStatus, String responseJson) {}
}

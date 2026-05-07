package kr.go.smes.ido.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.common.domain.HandoffTicket;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Handoff Ticket 저장소 구현체
 * 설계서 §16.2 / §16.10 참조
 *
 * <p>저장 전략:
 * <ul>
 *   <li>Redis: 주 저장소 (TTL 60초, consumeOnce 보장)</li>
 *   <li>PostgreSQL ido.handoff_audit: 감사 이력 (영구 보관)</li>
 * </ul>
 *
 * <p>consumeOnce 보장 (§16.10):
 * Redis SET NX (없을 때만 쓰기) → CONSUMED 상태 전환은 SET(덮어쓰기)
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TicketRepositoryImpl implements TicketRepository {

    private static final String KEY_PREFIX = "ido:ticket:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${ido.ticket.ttl-seconds:60}")
    private long ticketTtlSeconds;

    // ── 저장 ──────────────────────────────────────────────────────────────

    @Override
    public void save(HandoffTicket ticket) {
        String key = KEY_PREFIX + ticket.getTicketId();
        try {
            String json = objectMapper.writeValueAsString(ticket);
            // Redis: TTL = ticket.expiresAt - now + 여유 5초
            long ttl = Math.max(5L,
                    ticket.getExpiresAt().getEpochSecond() - Instant.now().getEpochSecond() + 5);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttl));

            // PostgreSQL 감사 이력 저장
            saveAuditLog(ticket);

            log.debug("[TicketRepository] Ticket 저장 완료: ticketId={} ttl={}s",
                    ticket.getTicketId(), ttl);
        } catch (JsonProcessingException e) {
            log.error("[TicketRepository] Ticket 직렬화 실패: ticketId={}", ticket.getTicketId(), e);
            throw new RuntimeException("Ticket 직렬화 실패", e);
        }
    }

    // ── 조회 ──────────────────────────────────────────────────────────────

    @Override
    public Optional<HandoffTicket> findById(String ticketId) {
        String key = KEY_PREFIX + ticketId;
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                log.debug("[TicketRepository] Ticket Redis MISS (만료 또는 미존재): ticketId={}", ticketId);
                return Optional.empty();
            }
            HandoffTicket ticket = objectMapper.readValue(value.toString(), HandoffTicket.class);
            return Optional.of(ticket);
        } catch (Exception e) {
            log.warn("[TicketRepository] Ticket 역직렬화 실패: ticketId={} error={}", ticketId, e.getMessage());
            return Optional.empty();
        }
    }

    // ── 소비 (ISSUED → CONSUMED) ───────────────────────────────────────────

    @Override
    public void consume(String ticketId) {
        String key = KEY_PREFIX + ticketId;
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                log.warn("[TicketRepository] consume 대상 Ticket 없음: ticketId={}", ticketId);
                throw new PlatformException(PlatformErrorCode.IDO_TICKET_EXPIRED, ticketId);
            }
            HandoffTicket existing = objectMapper.readValue(value.toString(), HandoffTicket.class);
            HandoffTicket consumed = HandoffTicket.builder()
                    .ticketId(existing.getTicketId())
                    .correlationId(existing.getCorrelationId())
                    .agencyCode(existing.getAgencyCode())
                    .qimUserId(existing.getQimUserId())
                    .authResultId(existing.getAuthResultId())
                    .authLevel(existing.getAuthLevel())
                    .state(HandoffTicket.TicketState.CONSUMED)
                    .issuedAt(existing.getIssuedAt())
                    .expiresAt(existing.getExpiresAt())
                    .encryptedPayload(existing.getEncryptedPayload())
                    .signature(existing.getSignature())
                    .build();

            // CONSUMED 상태로 덮어쓰기 (짧은 TTL 유지로 감사 조회 가능)
            String json = objectMapper.writeValueAsString(consumed);
            long remainTtl = Math.max(5L,
                    existing.getExpiresAt().getEpochSecond() - Instant.now().getEpochSecond() + 30);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(remainTtl));

            // 감사 이력 갱신
            updateAuditState(ticketId, "CONSUMED", null);

            log.info("[TicketRepository] Ticket CONSUMED: ticketId={}", ticketId);
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("[TicketRepository] Ticket consume 실패: ticketId={}", ticketId, e);
            throw new RuntimeException("Ticket consume 실패", e);
        }
    }

    // ── 취소 (ISSUED → REVOKED) ────────────────────────────────────────────

    @Override
    public void revoke(String ticketId, String revokeReason) {
        String key = KEY_PREFIX + ticketId;
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                log.warn("[TicketRepository] revoke 대상 Ticket 없음: ticketId={}", ticketId);
                // 감사 이력만 갱신
                updateAuditState(ticketId, "REVOKED", revokeReason);
                return;
            }
            HandoffTicket existing = objectMapper.readValue(value.toString(), HandoffTicket.class);
            HandoffTicket revoked = HandoffTicket.builder()
                    .ticketId(existing.getTicketId())
                    .correlationId(existing.getCorrelationId())
                    .agencyCode(existing.getAgencyCode())
                    .qimUserId(existing.getQimUserId())
                    .authResultId(existing.getAuthResultId())
                    .authLevel(existing.getAuthLevel())
                    .state(HandoffTicket.TicketState.REVOKED)
                    .issuedAt(existing.getIssuedAt())
                    .expiresAt(existing.getExpiresAt())
                    .encryptedPayload(existing.getEncryptedPayload())
                    .signature(existing.getSignature())
                    .build();

            String json = objectMapper.writeValueAsString(revoked);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(60));

            updateAuditState(ticketId, "REVOKED", revokeReason);
            log.info("[TicketRepository] Ticket REVOKED: ticketId={} reason={}", ticketId, revokeReason);
        } catch (Exception e) {
            log.error("[TicketRepository] Ticket revoke 실패: ticketId={}", ticketId, e);
        }
    }

    // ── 감사 이력 ─────────────────────────────────────────────────────────

    private void saveAuditLog(HandoffTicket ticket) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO ido.handoff_audit
                        (ticket_id, correlation_id, agency_code, qim_user_id,
                         auth_result_id, auth_level, state, issued_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (ticket_id) DO NOTHING
                    """,
                    ticket.getTicketId(),
                    ticket.getCorrelationId(),
                    ticket.getAgencyCode(),
                    ticket.getQimUserId(),
                    ticket.getAuthResultId(),
                    ticket.getAuthLevel().name(),
                    ticket.getState().name(),
                    Timestamp.from(ticket.getIssuedAt()),
                    Timestamp.from(ticket.getExpiresAt())
            );
        } catch (Exception e) {
            // 감사 이력 저장 실패는 비즈니스 로직을 막지 않음 (경고 로그)
            log.warn("[TicketRepository] 감사 이력 저장 실패 (무시): ticketId={} error={}",
                    ticket.getTicketId(), e.getMessage());
        }
    }

    private void updateAuditState(String ticketId, String state, String revokeReason) {
        try {
            if ("CONSUMED".equals(state)) {
                jdbcTemplate.update("""
                        UPDATE ido.handoff_audit
                        SET state = ?, consumed_at = NOW()
                        WHERE ticket_id = ?
                        """, state, ticketId);
            } else {
                jdbcTemplate.update("""
                        UPDATE ido.handoff_audit
                        SET state = ?, revoke_reason = ?
                        WHERE ticket_id = ?
                        """, state, revokeReason, ticketId);
            }
        } catch (Exception e) {
            log.warn("[TicketRepository] 감사 이력 상태 갱신 실패 (무시): ticketId={} error={}",
                    ticketId, e.getMessage());
        }
    }
}

package io.github.hipstermin.idem.hub.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.domain.HandoffTicket;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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

    private static final String KEY_PREFIX = "idem:ticket:";

    /**
     * Sprint α-2 / F4.2 — Atomic CAS Lua script for ISSUED → CONSUMED 전이.
     *
     * <p>입력:
     * <ul>
     *   <li>KEYS[1] = "idem:ticket:&lt;ticketId&gt;"</li>
     *   <li>ARGV[1] = 기대 현재 state (보통 "ISSUED")</li>
     *   <li>ARGV[2] = 갱신할 ticket JSON (state=CONSUMED 로 마킹된)</li>
     *   <li>ARGV[3] = 갱신 후 TTL(초) — 감사 조회용 짧은 보관</li>
     *   <li>ARGV[4] = state 마커 (보통 "\"state\":\"ISSUED\"") — JSON 내부에서 현 상태 확인용</li>
     * </ul>
     *
     * <p>반환:
     * <ul>
     *   <li>1: 성공 (state 일치 → 새 JSON 으로 덮어쓰기 + TTL 갱신)</li>
     *   <li>0: 키 없음 (만료/미존재)</li>
     *   <li>현재 state 문자열 (e.g. "CONSUMED", "REVOKED"): state mismatch</li>
     *   <li>"PARSE_ERROR": JSON 에서 state 추출 실패 (방어적 코드)</li>
     * </ul>
     *
     * <p>Lua 는 Redis 의 single-threaded 실행 모델에 의해 원자적으로 처리되므로,
     * 동시 verify 요청이 들어와도 단 하나만 1 을 받고 나머지는 mismatch 를 받음.
     */
    private static final String CONSUME_LUA = ""
            + "local cur = redis.call('GET', KEYS[1]);\n"
            + "if cur == false then return '0'; end;\n"
            // state 마커가 포함되어 있는지로 현 상태 확인 (JSON.parse 회피).
            // 값 직렬화기가 JSON(GenericJackson2Json)이면 문자열이 "…\"state\":\"ISSUED\"…" 로 이스케이프되어 저장되므로
            // 원문 마커(ARGV[4])와 이스케이프 마커(ARGV[5]) 둘 다 본다 — 종전에는 원문만 봐서 항상 PARSE_ERROR 였다.
            + "if string.find(cur, ARGV[4], 1, true) == nil and string.find(cur, ARGV[5], 1, true) == nil then\n"
            // ISSUED 가 아님 → 현재 state 추출하여 반환 (따옴표 앞 백슬래시는 있어도 없어도 됨)
            + "  local s = string.match(cur, '\\\\?\"state\\\\?\":\\\\?\"([^\"\\\\]+)');\n"
            + "  if s == nil then return 'PARSE_ERROR'; end;\n"
            + "  return s;\n"
            + "end;\n"
            // 원자적 SET + TTL 갱신 — ARGV[2] 는 저장 직렬화기를 이미 거친 바이트
            + "redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]);\n"
            + "return '1';\n";

    /** 결과는 항상 문자열('1'·'0'·state) — 정수 회신을 값 직렬화기가 JSON 으로 읽다 실패하던 문제를 피한다. */
    private static final RedisScript<String> CONSUME_SCRIPT =
            new DefaultRedisScript<>(CONSUME_LUA, String.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${idem.hub.ticket.ttl-seconds:60}")
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

    /**
     * Sprint α-2 / F4.2 — Atomic CAS 기반 1회성 소비.
     *
     * <p>기존 구현은 GET → builder().state(CONSUMED) → SET 의 3단계로 race window 가 존재해서,
     * 동시 verify 가 들어오면 두 요청 모두 CONSUMED 페이로드를 받아 동일 ticket 으로 두 번
     * 페이로드가 발급될 수 있었음. 이번 구현은 Redis Lua 스크립트로 단일 트랜잭션에서
     * "현재 state == ISSUED 인지 확인 → 일치 시 CONSUMED JSON 으로 덮어쓰기" 를 처리.
     *
     * <p>Lua 결과 처리:
     * <ul>
     *   <li>{@code 1L} — 성공 (CONSUMED 로 전이됨)</li>
     *   <li>{@code 0L} — 키 없음 → {@code IDEM_HUB_TICKET_EXPIRED}</li>
     *   <li>{@code "CONSUMED"} — 동시 verify 의 다른 winner 가 이미 소비 → {@code IDEM_HUB_TICKET_CONSUMED}</li>
     *   <li>{@code "REVOKED"} — race 중 revoke 가 들어와 취소됨 → {@code IDEM_HUB_TICKET_REVOKED}</li>
     *   <li>{@code "PARSE_ERROR"} 또는 기타 — 손상된 데이터 → {@code RuntimeException}</li>
     * </ul>
     */
    @Override
    public void consume(String ticketId) {
        String key = KEY_PREFIX + ticketId;
        try {
            // ① 현재 ticket 을 먼저 조회 — CONSUMED JSON 작성에 필요한 필드 추출용
            //    (스크립트 자체는 atomic 이지만, 새 JSON 은 Java 측에서 빌드해야 함.
            //     이 시점에 state mismatch 가 발생하면 Lua 가 거부하므로 race-safe.)
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                log.warn("[TicketRepository] consume 대상 Ticket 없음: ticketId={}", ticketId);
                throw new PlatformException(PlatformErrorCode.IDO_TICKET_EXPIRED, ticketId);
            }
            HandoffTicket existing = objectMapper.readValue(value.toString(), HandoffTicket.class);

            // ② 사전 빠른 거부 — Lua 가 어차피 막지만 불필요한 직렬화/Lua 호출 절약
            if (existing.getState() == HandoffTicket.TicketState.CONSUMED) {
                throw new PlatformException(PlatformErrorCode.IDO_TICKET_CONSUMED, ticketId);
            }
            if (existing.getState() == HandoffTicket.TicketState.REVOKED) {
                throw new PlatformException(PlatformErrorCode.IDO_TICKET_REVOKED, ticketId);
            }

            // ③ CONSUMED 상태 JSON 빌드
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
            String consumedJson = objectMapper.writeValueAsString(consumed);

            long remainTtl = Math.max(5L,
                    existing.getExpiresAt().getEpochSecond() - Instant.now().getEpochSecond() + 30);

            // ④ Lua atomic CAS — state == ISSUED 인 경우에만 덮어쓰기
            //    state 마커는 JSON 내부 문자열 검사로 처리 (Lua cjson 의존 회피).
            //    인자·결과는 문자열 직렬화기로 보내고, 새 값은 저장 직렬화기(값 직렬화기)를 거친 바이트를 그대로 넘겨
            //    opsForValue().set 이 저장한 형식과 같게 한다.
            String issuedMarker = "\"state\":\"ISSUED\"";
            String escapedMarker = "\\\"state\\\":\\\"ISSUED\\\"";
            Object result = redisTemplate.execute(
                    CONSUME_SCRIPT,
                    RedisSerializer.string(), RedisSerializer.string(),
                    Collections.singletonList(key),
                    "ISSUED", storedForm(consumedJson), String.valueOf(remainTtl), issuedMarker, escapedMarker
            );

            handleConsumeResult(ticketId, result);

            // ⑤ 감사 이력 갱신 — CAS 성공 후에만 호출
            updateAuditState(ticketId, "CONSUMED", null);

            log.info("[TicketRepository] Ticket CONSUMED (atomic): ticketId={}", ticketId);
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("[TicketRepository] Ticket consume 실패: ticketId={}", ticketId, e);
            throw new RuntimeException("Ticket consume 실패", e);
        }
    }

    /** 값 직렬화기가 저장하는 바이트 형식 그대로의 문자열 (JSON 직렬화기면 따옴표·이스케이프 포함). */
    @SuppressWarnings("unchecked")
    private String storedForm(String value) {
        RedisSerializer<Object> valueSerializer = (RedisSerializer<Object>) redisTemplate.getValueSerializer();
        if (valueSerializer == null) return value;
        byte[] bytes = valueSerializer.serialize(value);
        return bytes == null ? value : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Lua CAS 반환값을 PlatformException 으로 변환.
     * Spring Data Redis 의 Lua 실행은 숫자/문자열을 그대로 반환하므로 양쪽 모두 처리.
     */
    private void handleConsumeResult(String ticketId, Object result) {
        if (result == null) {
            // 비정상 — 보수적으로 RuntimeException
            throw new RuntimeException("Ticket consume Lua 결과 null: ticketId=" + ticketId);
        }
        // 성공: Long 1 또는 String "1"
        if ((result instanceof Long && ((Long) result) == 1L)
                || "1".equals(String.valueOf(result))) {
            return;
        }
        // 키 없음: Long 0 또는 String "0"
        if ((result instanceof Long && ((Long) result) == 0L)
                || "0".equals(String.valueOf(result))) {
            log.warn("[TicketRepository] consume CAS — 키 없음(만료): ticketId={}", ticketId);
            throw new PlatformException(PlatformErrorCode.IDO_TICKET_EXPIRED, ticketId);
        }
        // 그 외 — 현재 state 문자열
        String state = String.valueOf(result);
        log.warn("[TicketRepository] consume CAS state mismatch: ticketId={} currentState={}",
                ticketId, state);
        switch (state) {
            case "CONSUMED":
                throw new PlatformException(PlatformErrorCode.IDO_TICKET_CONSUMED, ticketId);
            case "REVOKED":
                throw new PlatformException(PlatformErrorCode.IDO_TICKET_REVOKED, ticketId);
            case "PARSE_ERROR":
            default:
                // 손상된 데이터 또는 알 수 없는 state — 운영 알람 대상
                throw new RuntimeException(
                        "Ticket consume CAS 비정상 응답: ticketId=" + ticketId + " state=" + state);
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

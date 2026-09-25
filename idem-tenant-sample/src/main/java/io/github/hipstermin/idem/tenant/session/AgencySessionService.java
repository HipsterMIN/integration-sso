package io.github.hipstermin.idem.tenant.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.util.UuidV7;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기관 로컬 세션 서비스 (DB 기반 완전 구현)
 *
 * <p><b>이전 상태</b>: {@code AgencyLocalSession}은 메모리 전용 Builder 객체 — DB 미저장
 * <p><b>현재 구현</b>:
 * <ul>
 *   <li>세션 생성 → {@code agency_stub.agency_local_session} DB 저장</li>
 *   <li>사용자 조회/생성 → {@code agency_stub.agency_user} 관리</li>
 *   <li>AGSID 해시 저장 (원문 저장 금지)</li>
 *   <li>세션 무효화 — ticketId / qimUserId 기준 일괄 처리</li>
 *   <li>세션 갱신 — last_accessed_at + idle_expires_at sliding window</li>
 * </ul>
 *
 * <p><b>AGSID 보안 원칙</b> (설계서 §14.6):
 * <pre>
 *   rawAGSID: SecureRandom 192-bit → Base64URL (≥128bit 엔트로피)
 *   쿠키 저장: rawAGSID (클라이언트)
 *   DB 저장:   SHA-256(rawAGSID) (서버) — 원문 저장 금지
 *   검증:      SHA-256(쿠키값) == DB 저장값
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgencySessionService {

    private static final String SCHEMA = "agency_stub";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper  objectMapper;

    @Value("${idem.sample.code:AGENCY_STUB_001}")
    private String agencyCode;

    @Value("${idem.sample.session.idle-timeout-minutes:30}")
    private int idleTimeoutMinutes;

    @Value("${idem.sample.session.absolute-timeout-minutes:480}")
    private int absoluteTimeoutMinutes;

    // ══════════════════════════════════════════════════════════════════════
    // 세션 생성 (Handoff Verify 성공 직후)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Handoff Verify 성공 후 기관 로컬 세션 생성
     *
     * <p>처리 순서:
     * <ol>
     *   <li>agencyUser 조회 또는 신규 생성 (agencySubjectId 기반)</li>
     *   <li>rawAGSID 생성 (SecureRandom 192-bit)</li>
     *   <li>SHA-256(rawAGSID) 계산 후 DB 저장</li>
     *   <li>세션 이벤트 로그 기록</li>
     * </ol>
     *
     * @param payload     IdO Verify 응답 (APPROVED 상태)
     * @param ticketId    소비한 Handoff Ticket ID
     * @param correlationId 추적 ID
     * @param ipAddress   클라이언트 IP
     * @param userAgent   클라이언트 User-Agent
     * @return {@link SessionCreateResult} — rawAGSID(쿠키용) + sessionId(내부용)
     */
    @Transactional
    public SessionCreateResult createSession(HandoffPayload payload,
                                             String ticketId,
                                             String correlationId,
                                             String ipAddress,
                                             String userAgent) {
        String qimUserId       = payload.getSubject().getQimUserId();
        String agencySubjectId = payload.getSubject().getAgencySubjectId();
        String authLevel       = payload.getAuthContext().getAuthLevel().name();

        // ① AgencyUser 조회 또는 생성
        String agencyUserId = findOrCreateAgencyUser(qimUserId, agencySubjectId, authLevel);

        // ② rawAGSID 생성 (192-bit SecureRandom → Base64URL)
        String rawAgsid = generateSecureRandom();

        // ③ SHA-256(rawAGSID) — DB 저장값
        String agsidHash = sha256Hex(rawAgsid);

        // ④ 세션 ID 및 만료 시각 계산
        String  sessionId  = UuidV7.generate();
        Instant now        = Instant.now();
        Instant idleExp    = now.plusSeconds(idleTimeoutMinutes * 60L);
        Instant absExp     = now.plusSeconds(absoluteTimeoutMinutes * 60L);

        // ⑤ DB INSERT
        jdbcTemplate.update("""
                INSERT INTO agency_stub.agency_local_session
                    (session_id, agsid, agency_user_id, ticket_id, correlation_id,
                     auth_level, ip_address, user_agent,
                     created_at, last_accessed_at, idle_expires_at, absolute_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                sessionId, agsidHash, agencyUserId, ticketId, correlationId,
                authLevel, ipAddress, truncate(userAgent, 500),
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), java.sql.Timestamp.from(idleExp), java.sql.Timestamp.from(absExp)
        );

        // ⑥ last_login_at 갱신
        jdbcTemplate.update("""
                UPDATE agency_stub.agency_user
                SET last_login_at = ?, updated_at = ?
                WHERE agency_user_id = ?
                """, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), agencyUserId);

        // ⑦ 세션 이벤트 로그
        insertSessionEventLog(sessionId, agencyUserId, "SESSION_CREATED", correlationId, ipAddress,
                Map.of("ticketId", ticketId, "authLevel", authLevel));

        log.info("[AgencySessionService] 세션 생성 완료: sessionId={} agencyUserId={} authLevel={} correlationId={}",
                sessionId, agencyUserId, authLevel, correlationId);

        return new SessionCreateResult(sessionId, rawAgsid, agencyUserId, agencySubjectId, authLevel);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 세션 조회
    // ══════════════════════════════════════════════════════════════════════

    /**
     * AGSID 쿠키로 세션 조회 및 유효성 검증
     *
     * <p>검증 항목:
     * <ol>
     *   <li>SHA-256(rawAGSID) DB 매칭</li>
     *   <li>invalidated_at IS NULL</li>
     *   <li>idle_expires_at > NOW()</li>
     *   <li>absolute_expires_at > NOW()</li>
     * </ol>
     *
     * @param rawAgsid 쿠키에서 읽은 AGSID 원문
     * @return 유효한 세션 정보 Optional
     */
    public Optional<Map<String, Object>> findValidSession(String rawAgsid) {
        if (rawAgsid == null || rawAgsid.isBlank()) return Optional.empty();

        String agsidHash = sha256Hex(rawAgsid);
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    SELECT s.session_id, s.agency_user_id, s.ticket_id,
                           s.correlation_id, s.auth_level, s.ip_address,
                           s.created_at, s.last_accessed_at,
                           s.idle_expires_at, s.absolute_expires_at,
                           u.agency_subject_id, u.qim_user_id, u.status AS user_status
                    FROM   agency_stub.agency_local_session s
                    JOIN   agency_stub.agency_user u ON u.agency_user_id = s.agency_user_id
                    WHERE  s.agsid              = ?
                      AND  s.invalidated_at    IS NULL
                      AND  s.idle_expires_at    > NOW()
                      AND  s.absolute_expires_at > NOW()
                    """, agsidHash);

            return Optional.of(row);

        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[AgencySessionService] 세션 조회 오류: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Sliding Window 세션 갱신 (last_accessed_at + idle_expires_at 연장)
     */
    @Transactional
    public void touchSession(String rawAgsid) {
        if (rawAgsid == null) return;
        String agsidHash = sha256Hex(rawAgsid);
        Instant now    = Instant.now();
        Instant newExp = now.plusSeconds(idleTimeoutMinutes * 60L);

        jdbcTemplate.update("""
                UPDATE agency_stub.agency_local_session
                SET    last_accessed_at = ?, idle_expires_at = ?
                WHERE  agsid = ? AND invalidated_at IS NULL
                """, java.sql.Timestamp.from(now), java.sql.Timestamp.from(newExp), agsidHash);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 세션 무효화
    // ══════════════════════════════════════════════════════════════════════

    /**
     * AGSID 기준 세션 무효화 (로그아웃 / Session Fixation 방지)
     */
    @Transactional
    public void invalidateByAgsid(String rawAgsid, String reason, String correlationId) {
        if (rawAgsid == null) return;
        String agsidHash = sha256Hex(rawAgsid);

        int updated = jdbcTemplate.update("""
                UPDATE agency_stub.agency_local_session
                SET    invalidated_at = NOW(), invalidate_reason = ?
                WHERE  agsid = ? AND invalidated_at IS NULL
                """, reason, agsidHash);

        if (updated > 0) {
            log.info("[AgencySessionService] AGSID 세션 무효화: reason={} correlationId={}", reason, correlationId);
        }
    }

    /**
     * Handoff Ticket ID 기준 세션 무효화
     * HANDOFF_REVOKED 이벤트 수신 시 호출
     */
    @Transactional
    public int invalidateByTicketId(String ticketId, String reason, String correlationId) {
        int updated = jdbcTemplate.update("""
                UPDATE agency_stub.agency_local_session
                SET    invalidated_at = NOW(), invalidate_reason = ?
                WHERE  ticket_id = ? AND invalidated_at IS NULL
                """, reason, ticketId);

        if (updated > 0) {
            log.warn("[AgencySessionService] ticketId 기준 세션 무효화 {}건: ticketId={} reason={}",
                    updated, ticketId, reason);
            // 세션 이벤트 로그
            insertSessionEventLog(null, null, "SESSION_INVALIDATED", correlationId, null,
                    Map.of("reason", reason, "ticketId", ticketId, "count", updated));
        }
        return updated;
    }

    /**
     * qimUserId 기준 전체 세션 무효화
     * MANDATORY_SECURITY_TERMINATE Advisory 수신 시 호출
     */
    @Transactional
    public int invalidateByQimUserId(String qimUserId, String reason, String correlationId) {
        int updated = jdbcTemplate.update("""
                UPDATE agency_stub.agency_local_session als
                SET    invalidated_at = NOW(), invalidate_reason = ?
                FROM   agency_stub.agency_user au
                WHERE  als.agency_user_id = au.agency_user_id
                  AND  au.qim_user_id = ?
                  AND  als.invalidated_at IS NULL
                """, reason, qimUserId);

        if (updated > 0) {
            log.warn("[AgencySessionService] qimUserId 기준 세션 무효화 {}건: qimUserId={} reason={}",
                    updated, qimUserId, reason);
            insertSessionEventLog(null, null, "SESSION_INVALIDATED", correlationId, null,
                    Map.of("reason", reason, "qimUserId", qimUserId, "count", updated));
        }
        return updated;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 내부 헬퍼
    // ══════════════════════════════════════════════════════════════════════

    /**
     * agencySubjectId로 사용자 조회; 없으면 신규 생성
     */
    private String findOrCreateAgencyUser(String qimUserId, String agencySubjectId, String authLevel) {
        try {
            String existing = jdbcTemplate.queryForObject("""
                    SELECT agency_user_id
                    FROM   agency_stub.agency_user
                    WHERE  agency_subject_id = ?
                    """, String.class, agencySubjectId);

            if (existing != null) {
                log.debug("[AgencySessionService] 기존 사용자 조회: agencySubjectId={}", agencySubjectId);
                return existing;
            }
        } catch (org.springframework.dao.EmptyResultDataAccessException ignored) {
            // 신규 사용자
        }

        // 신규 사용자 생성
        String newUserId = UuidV7.generate();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO agency_stub.agency_user
                    (agency_user_id, agency_subject_id, qim_user_id,
                     agency_code, status, first_login_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """,
                newUserId, agencySubjectId, qimUserId,
                agencyCode, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

        log.info("[AgencySessionService] 신규 사용자 생성: agencyUserId={} qimUserId={}", newUserId, qimUserId);
        return newUserId;
    }

    /**
     * 세션 이벤트 로그 기록
     */
    private void insertSessionEventLog(String sessionId, String agencyUserId,
                                       String eventType, String correlationId,
                                       String ipAddress, Map<String, Object> detail) {
        try {
            String detailJson = objectMapper.writeValueAsString(detail);
            jdbcTemplate.update("""
                    INSERT INTO agency_stub.session_event_log
                        (log_id, session_id, agency_user_id, event_type,
                         correlation_id, ip_address, detail, occurred_at)
                    VALUES (gen_random_uuid()::text, ?, ?, ?, ?, ?, ?::jsonb, NOW())
                    """,
                    sessionId, agencyUserId, eventType, correlationId, ipAddress, detailJson);
        } catch (Exception e) {
            log.warn("[AgencySessionService] 세션 이벤트 로그 기록 실패: {}", e.getMessage());
        }
    }

    /** SecureRandom 192-bit → Base64URL 문자열 */
    private String generateSecureRandom() {
        byte[] bytes = new byte[24]; // 192 bit
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String truncate(String s, int max) {
        return (s != null && s.length() > max) ? s.substring(0, max) : s;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Kafka 이벤트 멱등 처리 (HandoffEventConsumer 위임)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 이벤트 처리 여부 조회
     *
     * @param eventId      Kafka 이벤트 ID
     * @param consumerGroup 컨슈머 그룹 식별자
     * @return 처리 건수 (0 이면 미처리)
     */
    public Integer countProcessedEvent(String eventId, String consumerGroup) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM agency_stub.processed_event " +
                "WHERE event_id = ? AND consumer_group = ?",
                Integer.class, eventId, consumerGroup);
    }

    /**
     * 이벤트 처리 완료 마킹 (ON CONFLICT DO NOTHING — 멱등)
     *
     * @param eventId      Kafka 이벤트 ID
     * @param consumerGroup 컨슈머 그룹 식별자
     * @param eventType    이벤트 타입 (감사용)
     * @param resultCode   처리 결과 코드 (예: "OK", "SKIPPED")
     */
    @Transactional
    public void markEventProcessed(String eventId, String consumerGroup,
                                   String eventType, String resultCode) {
        jdbcTemplate.update("""
                INSERT INTO agency_stub.processed_event
                    (event_id, consumer_group, event_type, result_code, processed_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (event_id, consumer_group) DO NOTHING
                """, eventId, consumerGroup, eventType, resultCode, java.sql.Timestamp.from(Instant.now()));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Result Records
    // ══════════════════════════════════════════════════════════════════════

    /** 세션 생성 결과 (rawAGSID는 쿠키로 발급, 이후 서버에서 보관하지 않음) */
    public record SessionCreateResult(
            String sessionId,
            String rawAgsid,        // 쿠키 값으로 전달 — 이후 서버 미보관
            String agencyUserId,
            String agencySubjectId,
            String authLevel
    ) {}
}

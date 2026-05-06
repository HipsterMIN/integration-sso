package kr.go.smes.agency.kafka;

import kr.go.smes.common.event.HandoffEvent;
import kr.go.smes.common.event.SessionAdvisoryEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Agency-Stub ← ido.handoff.events 컨슈머
 * 설계서 §16.3 Handoff 이벤트 / §14.9 세션 보안 이벤트
 *
 * <p>HANDOFF_REVOKED 수신 시: 해당 ticketId 로 생성된 기관 세션 즉시 무효화
 * <p>REUSE_ATTEMPT 수신 시: 감사 로그 기록 + 보안 알림
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HandoffEventConsumer {

    private static final String CONSUMER_GROUP_HANDOFF  = "agency-stub-consumer-handoff";
    private static final String CONSUMER_GROUP_ADVISORY = "agency-stub-consumer-advisory";

    private final JdbcTemplate jdbcTemplate;

    // ── Handoff 이벤트 ─────────────────────────────────────────────────

    @KafkaListener(
            topics           = "${agency-stub.kafka.topic-handoff-events:ido.handoff.events}",
            groupId          = "${agency-stub.kafka.consumer-group:agency-stub-consumer}-handoff",
            containerFactory = "agencyHandoffListenerFactory"
    )
    public void consumeHandoff(ConsumerRecord<String, HandoffEvent> record,
                               Acknowledgment ack) {
        HandoffEvent event = record.value();
        if (event == null) { ack.acknowledge(); return; }

        String eventId   = event.getEventId();
        String eventType = event.getEventType();
        String ticketId  = event.getTicketId();

        try {
            // ① 멱등 처리
            if (isProcessed(eventId, CONSUMER_GROUP_HANDOFF)) {
                log.debug("[AgencyHandoffConsumer] 중복 스킵: eventId={}", eventId);
                ack.acknowledge();
                return;
            }

            switch (eventType) {
                case HandoffEvent.TYPE_HANDOFF_REVOKED -> handleRevoked(event);
                case HandoffEvent.TYPE_REUSE_ATTEMPT   -> handleReuseAttempt(event);
                default -> log.debug("[AgencyHandoffConsumer] 스킵 타입: {}", eventType);
            }

            markProcessed(eventId, CONSUMER_GROUP_HANDOFF, eventType, "OK");
            log.info("[AgencyHandoffConsumer] 처리 완료: eventType={} ticketId={}", eventType, ticketId);

        } catch (Exception e) {
            log.error("[AgencyHandoffConsumer] 처리 실패: eventId={}", eventId, e);
            throw e;
        } finally {
            ack.acknowledge();
        }
    }

    // ── Session Advisory 이벤트 ────────────────────────────────────────

    @KafkaListener(
            topics           = "${agency-stub.kafka.topic-session-advisory:platform.session.advisory}",
            groupId          = "${agency-stub.kafka.consumer-group:agency-stub-consumer}-advisory",
            containerFactory = "agencyAdvisoryListenerFactory"
    )
    public void consumeAdvisory(ConsumerRecord<String, SessionAdvisoryEvent> record,
                                Acknowledgment ack) {
        SessionAdvisoryEvent event = record.value();
        if (event == null) { ack.acknowledge(); return; }

        String eventId   = event.getEventId();
        String eventType = event.getEventType();
        String qimUserId = event.getQimUserId();

        try {
            if (isProcessed(eventId, CONSUMER_GROUP_ADVISORY)) {
                ack.acknowledge();
                return;
            }

            if (SessionAdvisoryEvent.TYPE_MANDATORY_SECURITY.equals(eventType)) {
                // MANDATORY: 해당 qimUserId 의 기관 세션 즉시 무효화
                invalidateSessionsByQimUser(qimUserId, "MANDATORY_SECURITY");
                log.warn("[AgencyAdvisoryConsumer] MANDATORY 세션 무효화: qimUserId={}", qimUserId);
            } else {
                // ADVISORY: 감사 로그만 기록
                log.info("[AgencyAdvisoryConsumer] Advisory 수신: qimUserId={} reason={}",
                        qimUserId, event.getReason());
            }

            markProcessed(eventId, CONSUMER_GROUP_ADVISORY, eventType, "OK");

        } catch (Exception e) {
            log.error("[AgencyAdvisoryConsumer] 처리 실패: eventId={}", eventId, e);
            throw e;
        } finally {
            ack.acknowledge();
        }
    }

    // ── Private ────────────────────────────────────────────────────────

    /** HANDOFF_REVOKED: ticketId 로 생성된 세션 무효화 */
    private void handleRevoked(HandoffEvent event) {
        int updated = jdbcTemplate.update("""
                UPDATE agency_stub.agency_local_session
                SET invalidated_at = ?, invalidate_reason = 'HANDOFF_REVOKED'
                WHERE ticket_id = ? AND invalidated_at IS NULL
                """, Instant.now(), event.getTicketId());

        if (updated > 0) {
            log.warn("[AgencyHandoffConsumer] REVOKED 세션 무효화 {} 건: ticketId={} reason={}",
                    updated, event.getTicketId(), event.getRevokeReason());
        }

        // 세션 이벤트 감사 로그
        jdbcTemplate.update("""
                INSERT INTO agency_stub.session_event_log
                    (log_id, event_type, correlation_id, detail, occurred_at)
                VALUES (gen_random_uuid()::text, 'SESSION_INVALIDATED', ?, ?::jsonb, ?)
                """,
                event.getCorrelationId(),
                String.format("{\"reason\":\"HANDOFF_REVOKED\",\"ticketId\":\"%s\"}", event.getTicketId()),
                Instant.now()
        );
    }

    /** REUSE_ATTEMPT: 재사용 시도 감사 로그 */
    private void handleReuseAttempt(HandoffEvent event) {
        log.warn("[AgencyHandoffConsumer] REUSE_ATTEMPT 감지: ticketId={} agencyCode={}",
                event.getTicketId(), event.getAgencyCode());

        jdbcTemplate.update("""
                INSERT INTO agency_stub.session_event_log
                    (log_id, event_type, correlation_id, detail, occurred_at)
                VALUES (gen_random_uuid()::text, 'HANDOFF_VERIFY_FAILED', ?, ?::jsonb, ?)
                """,
                event.getCorrelationId(),
                String.format("{\"reason\":\"REUSE_ATTEMPT\",\"ticketId\":\"%s\"}", event.getTicketId()),
                Instant.now()
        );
    }

    /** qimUserId 기반 기관 세션 일괄 무효화 */
    private void invalidateSessionsByQimUser(String qimUserId, String reason) {
        jdbcTemplate.update("""
                UPDATE agency_stub.agency_local_session als
                SET invalidated_at = ?, invalidate_reason = ?
                FROM agency_stub.agency_user au
                WHERE als.agency_user_id = au.agency_user_id
                  AND au.qim_user_id = ?
                  AND als.invalidated_at IS NULL
                """, Instant.now(), reason, qimUserId);
    }

    // ── Idempotent helpers ─────────────────────────────────────────────

    private boolean isProcessed(String eventId, String group) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM agency_stub.processed_event " +
                "WHERE event_id = ? AND consumer_group = ?",
                Integer.class, eventId, group);
        return count != null && count > 0;
    }

    private void markProcessed(String eventId, String group, String type, String code) {
        jdbcTemplate.update("""
                INSERT INTO agency_stub.processed_event
                    (event_id, consumer_group, event_type, result_code, processed_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (event_id, consumer_group) DO NOTHING
                """, eventId, group, type, code, Instant.now());
    }
}

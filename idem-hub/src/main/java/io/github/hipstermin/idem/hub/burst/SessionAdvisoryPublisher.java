package io.github.hipstermin.idem.hub.burst;

import io.github.hipstermin.idem.common.event.AuditLogEvent;
import io.github.hipstermin.idem.common.event.SessionAdvisoryEvent;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * SESSION ADVISORY 이벤트 발행기
 *
 * <p><b>책임</b>:
 * AUTH_LOCKED 이벤트(Q-Sign) 수신 → {@code platform.session.advisory} 토픽 발행
 * → FeAdvisoryConsumer(FE 세션 무효화) + WebhookDispatcherService(기관 통보)
 *
 * <p><b>설계 원칙</b>:
 * <ul>
 *   <li>Kafka 발행 실패 시 IdO Outbox(ido.outbox)에 백업하여 at-least-once 보장</li>
 *   <li>MANDATORY_SECURITY_TERMINATE: FE 세션 강제 종료 + 모든 기관 통보</li>
 *   <li>SESSION_LOGOUT_HINT: 부드러운 로그아웃 권고 (기관이 결정)</li>
 * </ul>
 *
 * <p><b>이벤트 체인</b>:
 * <pre>
 * [Q-Sign Outbox] → qsign.auth.events → [QsignAuthEventConsumer]
 *   └─ AUTH_LOCKED → SessionAdvisoryPublisher.publishAuthLocked()
 *                        │
 *                        ▼
 *              platform.session.advisory
 *                        │
 *              ┌─────────┴──────────┐
 *              ▼                    ▼
 *     [FeAdvisoryConsumer]   [HandoffEventConsumer]
 *     FE 세션 즉시 무효화    → WebhookDispatcherService
 *                              → 기관 HTTPS webhook
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionAdvisoryPublisher {

    private static final String SOURCE_SYSTEM    = "ido";
    private static final String ADVISORY_TOPIC   = "platform.session.advisory";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final JdbcTemplate                  jdbcTemplate;
    private final AuditLogPublisher             auditLogPublisher;

    @Value("${ido.kafka.topic-session-advisory:platform.session.advisory}")
    private String advisoryTopic;

    /** D1-b: Kafka 선택 의존 — 꺼지면 Kafka 를 건너뛰고 ido.outbox 에 적재, IdoOutboxRelay 가 FeAdvisoryConsumer 로 프로세스 내 배달 */
    @Value("${idem.messaging.kafka.enabled:false}")
    private boolean kafkaEnabled;

    // ── AUTH_LOCKED → MANDATORY_SECURITY_TERMINATE ────────────────────────

    /**
     * 인증 잠금 이벤트 수신 시 세션 강제 종료 Advisory 발행
     *
     * <p>AUTH_LOCKED는 비밀번호 5회 오류, 의심 IP 등 보안 이벤트이므로
     * MANDATORY_SECURITY_TERMINATE(강제 종료) 등급으로 발행.
     *
     * @param qimUserId     잠금 대상 사용자
     * @param correlationId 추적 ID
     * @param reason        잠금 사유 (AUTH_FAIL_EXCEEDED / SUSPICIOUS_IP 등)
     */
    public void publishAuthLocked(String qimUserId, String correlationId, String reason) {
        log.warn("[SessionAdvisoryPublisher] AUTH_LOCKED Advisory 발행: qimUserId={} reason={}",
                qimUserId, reason);

        SessionAdvisoryEvent event = new SessionAdvisoryEvent(
                SessionAdvisoryEvent.TYPE_MANDATORY_SECURITY,
                SOURCE_SYSTEM,
                correlationId,
                qimUserId,
                1L,
                "MANDATORY",                   // severity
                null,                           // agencyCode = null → 전체 기관 대상
                reason != null ? reason : "AUTH_LOCKED",
                null                            // authResultId
        );

        sendToKafkaWithFallback(event, qimUserId, correlationId);

        // 감사 로그 기록
        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_SESSION)
                        .eventAction("SESSION_MANDATORY_TERMINATE")
                        .actorType(AuditLogEvent.ACTOR_SYSTEM)
                        .actorId(SOURCE_SYSTEM)
                        .resourceType("SESSION")
                        .resourceId(qimUserId)
                        .correlationId(correlationId)
                        .outcome(AuditLogEvent.OUTCOME_SUCCESS)
                        .outcomeDetail("AUTH_LOCKED → MANDATORY_SECURITY_TERMINATE")
                        .metadata(Map.of(
                                "reason", reason != null ? reason : "AUTH_LOCKED",
                                "severity", "MANDATORY"
                        ))
                        .build()
        );
    }

    // ── 일반 Advisory (로그아웃 권고) ─────────────────────────────────────

    /**
     * 일반 세션 로그아웃 권고 Advisory 발행
     *
     * <p>Q-IM USER_SUSPENDED 이벤트 수신 등 비강제 상황에서 사용.
     * 기관이 수신 후 세션 종료 여부를 자체 결정.
     *
     * @param qimUserId     대상 사용자
     * @param agencyCode    특정 기관만 대상 (null = 전체 기관)
     * @param correlationId 추적 ID
     * @param reason        권고 사유
     */
    public void publishLogoutHint(String qimUserId, String agencyCode,
                                  String correlationId, String reason) {
        log.info("[SessionAdvisoryPublisher] LOGOUT_HINT Advisory 발행: qimUserId={} agencyCode={}",
                qimUserId, agencyCode);

        SessionAdvisoryEvent event = new SessionAdvisoryEvent(
                SessionAdvisoryEvent.TYPE_SESSION_LOGOUT_HINT,
                SOURCE_SYSTEM,
                correlationId,
                qimUserId,
                1L,
                "ADVISORY",                    // severity
                agencyCode,
                reason != null ? reason : "SESSION_LOGOUT_HINT",
                null
        );

        sendToKafkaWithFallback(event, qimUserId, correlationId);
    }

    // ── 내부 구현 ──────────────────────────────────────────────────────────

    /**
     * Kafka 발행 + 실패 시 ido.outbox Fallback
     *
     * <p>at-least-once 보장:
     * Kafka 직접 발행 성공 → 완료
     * 실패 → ido.outbox INSERT → IdoOutboxRelay 가 재발행
     */
    private void sendToKafkaWithFallback(SessionAdvisoryEvent event,
                                          String partitionKey,
                                          String correlationId) {
        if (!kafkaEnabled) {
            // Kafka 없음(D1-b): 아웃박스가 유일한 경로 — 릴레이가 같은 프로세스의 FeAdvisoryConsumer.handle() 로 배달
            insertOutboxFallback(event, partitionKey);
            return;
        }
        try {
            kafkaTemplate.send(advisoryTopic, partitionKey, event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("[SessionAdvisoryPublisher] Kafka 발행 실패 → Outbox 저장: correlationId={} error={}",
                                    correlationId, ex.getMessage());
                            insertOutboxFallback(event, partitionKey);
                        } else {
                            log.debug("[SessionAdvisoryPublisher] Kafka 발행 완료: partition={} offset={}",
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception e) {
            // Kafka 자체 예외 → Outbox 즉시 저장
            log.error("[SessionAdvisoryPublisher] Kafka 발행 예외 → Outbox 저장: correlationId={} error={}",
                    correlationId, e.getMessage());
            insertOutboxFallback(event, partitionKey);
        }
    }

    /**
     * Kafka 발행 실패 시 ido.outbox 에 PENDING 레코드 삽입
     * IdoOutboxRelay 가 500ms 간격으로 재발행
     */
    private void insertOutboxFallback(SessionAdvisoryEvent event, String partitionKey) {
        try {
            String payload = buildAdvisoryPayload(event);
            jdbcTemplate.update("""
                    INSERT INTO ido.outbox
                        (event_id, event_type, partition_key, aggregate_id,
                         event_version, payload, topic, status, retry_count, created_at)
                    VALUES (?, ?, ?, ?, 1, ?::jsonb, ?, 'PENDING', 0, NOW())
                    ON CONFLICT (event_id) DO NOTHING
                    """,
                    event.getEventId(),
                    event.getEventType(),
                    partitionKey,
                    event.getQimUserId(),
                    payload,
                    advisoryTopic
            );
            log.info("[SessionAdvisoryPublisher] Outbox 저장 완료: eventId={}", event.getEventId());
        } catch (Exception e) {
            log.error("[SessionAdvisoryPublisher] Outbox 저장도 실패: eventId={} error={}",
                    event.getEventId(), e.getMessage());
        }
    }

    private String buildAdvisoryPayload(SessionAdvisoryEvent event) {
        return String.format("""
                {"eventId":"%s","eventType":"%s","qimUserId":"%s",\
                "correlationId":"%s","severity":"%s","reason":"%s","occurredAt":"%s"}""",
                event.getEventId(), event.getEventType(),
                nullToEmpty(event.getQimUserId()),
                nullToEmpty(event.getCorrelationId()),
                nullToEmpty(event.getSeverity()),
                nullToEmpty(event.getReason()),
                event.getOccurredAt()
        );
    }

    private String nullToEmpty(String s) { return s != null ? s : ""; }
}

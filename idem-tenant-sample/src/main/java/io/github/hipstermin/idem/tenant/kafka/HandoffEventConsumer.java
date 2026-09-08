package io.github.hipstermin.idem.tenant.kafka;

import io.github.hipstermin.idem.common.event.HandoffEvent;
import io.github.hipstermin.idem.common.event.SessionAdvisoryEvent;
import io.github.hipstermin.idem.tenant.session.AgencySessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Agency-Stub Kafka 이벤트 컨슈머 — PoC 전용
 *
 * <p><b>⚠️ PoC 한정 코드 — 실 운영에서 사용 불가</b>
 *
 * <p>이 클래스는 PoC 시뮬레이션을 위해 내부 Kafka 브로커를 직접 구독합니다.
 * 실제 운영 환경에서 유관기관은 내부 Kafka에 접근할 수 없으며,
 * OnePass 플랫폼과의 통신은 반드시 IdO 공개 API(HTTPS)를 통해서만 이루어집니다.
 *
 * <p><b>운영 대체 방안 (설계서 §16.3, §14.9):</b>
 * <ul>
 *   <li><b>Option A (권장)</b>: IdO WebhookDispatcher → POST /api/v1/webhook/inbound → {@link io.github.hipstermin.idem.tenant.webhook.WebhookInboundController}</li>
 *   <li><b>Option B (대안)</b>: 기관 → GET /api/v1/events/poll → {@link io.github.hipstermin.idem.tenant.api.AgencyEventPollingController}</li>
 * </ul>
 *
 * <p>설계 상세: docs/agency-external-arch-supplement.md §3.1, §5.2
 *
 * <p><b>현재 처리 내용 (PoC):</b>
 * <ul>
 *   <li>HANDOFF_REVOKED → {@link AgencySessionService#invalidateByTicketId} 호출</li>
 *   <li>REUSE_ATTEMPT → 감사 로그 기록 + 보안 경고</li>
 *   <li>MANDATORY_SECURITY Advisory → {@link AgencySessionService#invalidateByQimUserId} 일괄 무효화</li>
 *   <li>기타 Advisory → 로그 기록만</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HandoffEventConsumer {

    private static final String CONSUMER_GROUP_HANDOFF  = "agency-stub-consumer-handoff";
    private static final String CONSUMER_GROUP_ADVISORY = "agency-stub-consumer-advisory";

    /** DB 기반 세션 서비스 — ticketId / qimUserId 기준 무효화 위임 */
    private final AgencySessionService agencySessionService;

    // ══════════════════════════════════════════════════════════════════════
    // Handoff 이벤트 컨슈머
    // ══════════════════════════════════════════════════════════════════════

    @KafkaListener(
            topics           = "${agency-stub.kafka.topic-handoff-events:ido.handoff.events}",
            groupId          = "${agency-stub.kafka.consumer-group:agency-stub-consumer}-handoff",
            containerFactory = "agencyHandoffListenerFactory"
    )
    public void consumeHandoff(ConsumerRecord<String, HandoffEvent> record,
                               Acknowledgment ack) {

        HandoffEvent event = record.value();
        if (event == null) {
            log.warn("[AgencyHandoffConsumer] null 이벤트 수신 — 스킵");
            ack.acknowledge();
            return;
        }

        String eventId   = event.getEventId();
        String eventType = event.getEventType();
        String ticketId  = event.getTicketId();

        try {
            // 멱등 처리
            if (isProcessed(eventId, CONSUMER_GROUP_HANDOFF)) {
                log.debug("[AgencyHandoffConsumer] 중복 스킵: eventId={}", eventId);
                ack.acknowledge();
                return;
            }

            switch (eventType) {
                case HandoffEvent.TYPE_HANDOFF_REVOKED -> handleRevoked(event);
                case HandoffEvent.TYPE_REUSE_ATTEMPT   -> handleReuseAttempt(event);
                default -> log.debug("[AgencyHandoffConsumer] 처리 불필요 타입: {} ticketId={}", eventType, ticketId);
            }

            markProcessed(eventId, CONSUMER_GROUP_HANDOFF, eventType, "OK");
            log.info("[AgencyHandoffConsumer] 처리 완료: eventType={} ticketId={} correlationId={}",
                    eventType, ticketId, event.getCorrelationId());

        } catch (Exception e) {
            log.error("[AgencyHandoffConsumer] 처리 실패: eventId={} eventType={} err={}",
                    eventId, eventType, e.getMessage(), e);
            throw e;  // DefaultErrorHandler 가 재시도 / DLQ 처리

        } finally {
            ack.acknowledge();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Session Advisory 컨슈머
    // ══════════════════════════════════════════════════════════════════════

    @KafkaListener(
            topics           = "${agency-stub.kafka.topic-session-advisory:platform.session.advisory}",
            groupId          = "${agency-stub.kafka.consumer-group:agency-stub-consumer}-advisory",
            containerFactory = "agencyAdvisoryListenerFactory"
    )
    public void consumeAdvisory(ConsumerRecord<String, SessionAdvisoryEvent> record,
                                Acknowledgment ack) {

        SessionAdvisoryEvent event = record.value();
        if (event == null) {
            ack.acknowledge();
            return;
        }

        String eventId   = event.getEventId();
        String eventType = event.getEventType();
        String qimUserId = event.getQimUserId();

        try {
            if (isProcessed(eventId, CONSUMER_GROUP_ADVISORY)) {
                ack.acknowledge();
                return;
            }

            if (SessionAdvisoryEvent.TYPE_MANDATORY_SECURITY.equals(eventType)) {
                // MANDATORY: qimUserId 기준 기관 세션 즉시 일괄 무효화
                int invalidated = agencySessionService.invalidateByQimUserId(
                        qimUserId, "MANDATORY_SECURITY_TERMINATE", event.getCorrelationId());
                log.warn("[AgencyAdvisoryConsumer] MANDATORY 세션 무효화 {}건: qimUserId={} correlationId={}",
                        invalidated, qimUserId, event.getCorrelationId());
            } else {
                // ADVISORY: 감사 로그만 기록 (세션은 유지)
                log.info("[AgencyAdvisoryConsumer] Advisory 수신 (세션 유지): eventType={} qimUserId={} reason={}",
                        eventType, qimUserId, event.getReason());
            }

            markProcessed(eventId, CONSUMER_GROUP_ADVISORY, eventType, "OK");

        } catch (Exception e) {
            log.error("[AgencyAdvisoryConsumer] 처리 실패: eventId={} err={}", eventId, e.getMessage(), e);
            throw e;

        } finally {
            ack.acknowledge();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 이벤트별 핸들러
    // ══════════════════════════════════════════════════════════════════════

    /** HANDOFF_REVOKED: ticketId 로 생성된 기관 세션 즉시 무효화 */
    private void handleRevoked(HandoffEvent event) {
        int invalidated = agencySessionService.invalidateByTicketId(
                event.getTicketId(),
                "HANDOFF_REVOKED:" + nullToEmpty(event.getRevokeReason()),
                event.getCorrelationId()
        );

        log.warn("[AgencyHandoffConsumer] REVOKED 세션 무효화 {}건: ticketId={} reason={} correlationId={}",
                invalidated, event.getTicketId(), event.getRevokeReason(), event.getCorrelationId());
    }

    /** REUSE_ATTEMPT: 재사용 시도 감지 → 보안 감사 로그만 (세션은 AgencySessionService에서 관리) */
    private void handleReuseAttempt(HandoffEvent event) {
        log.warn("[AgencyHandoffConsumer] REUSE_ATTEMPT 감지: ticketId={} agencyCode={} correlationId={}",
                event.getTicketId(), event.getAgencyCode(), event.getCorrelationId());
        // WebhookInboundController 또는 AgencySessionService 가 처리하는 보안 이벤트와 중복 방지
        // 여기서는 Kafka 감사 로그만 기록 (DB 직접 조작 없음)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 멱등 처리 헬퍼 — AgencySessionService 의 DB 사용
    // ══════════════════════════════════════════════════════════════════════

    private boolean isProcessed(String eventId, String group) {
        try {
            Integer count = agencySessionService.countProcessedEvent(eventId, group);
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("[AgencyHandoffConsumer] processed_event 조회 실패: eventId={} err={}", eventId, e.getMessage());
            return false;
        }
    }

    private void markProcessed(String eventId, String group, String type, String code) {
        try {
            agencySessionService.markEventProcessed(eventId, group, type, code);
        } catch (Exception e) {
            log.warn("[AgencyHandoffConsumer] processed_event 기록 실패: eventId={} err={}", eventId, e.getMessage());
        }
    }

    private String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}

package io.github.hipstermin.idem.hub.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.event.AuditLogEvent;
import io.github.hipstermin.idem.common.event.HandoffEvent;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.burst.AuthResultCacheService;
import io.github.hipstermin.idem.hub.webhook.WebhookDispatcherService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Handoff 이벤트 컨슈머 (idem.hub.handoff.events → 기관 Webhook 트리거)
 *
 * <p><b>책임</b>:
 * HandoffServiceImpl이 발행한 {@code idem.hub.handoff.events} 이벤트를 수신하여
 * {@link WebhookDispatcherService}를 통해 기관 webhook 발송 Outbox에 적재.
 *
 * <p><b>핵심 설계 — 기관 Kafka 직접 접근 불가 문제 해결</b>:
 * <pre>
 * [유관기관 요구] CI/DN으로 회원 여부를 Kafka로 조회하고 싶다
 *
 * [불가 이유]
 *   - Kafka 브로커는 내부망(172.20.0.x)만 노출
 *   - 기관별 SASL ACL 관리 = 운영 지옥
 *   - ACL 실수 → 기관 A가 기관 B의 CI/DN 조회 가능 (개인정보법 위반)
 *
 * [해결책 — 이 클래스의 역할]
 *   Handoff 이벤트 수신
 *     → WebhookDispatcherService.enqueueForHandoffEvent()
 *         → webhook_dispatch_outbox INSERT
 *             → WebhookDispatchOutboxRelay → HTTPS POST 기관 endpoint
 *
 * 기관은 Kafka 대신 HTTPS webhook을 수신 = 표준 HTTP만으로 연동
 * </pre>
 *
 * <p><b>이벤트 타입별 처리</b>:
 * <ul>
 *   <li>{@code HANDOFF_ISSUED}   — Handoff 티켓 발급 → 기관 webhook 즉시 통보</li>
 *   <li>{@code HANDOFF_CONSUMED} — 티켓 소비 완료 → 기관 webhook 통보 (선택)</li>
 *   <li>{@code HANDOFF_EXPIRED}  — 티켓 만료 → 로그 + 감사 기록</li>
 *   <li>{@code HANDOFF_REVOKED}  — 보안 취소 → 기관 webhook 즉시 통보 (HIGH)</li>
 * </ul>
 *
 * <p><b>Auth Result Cache 연계</b>:
 * HANDOFF_CONSUMED 수신 시 Redis 캐시 무효화 (보안: 1회용 인증 결과 재사용 방지).
 *
 * <p><b>토픽</b>: {@code idem.hub.handoff.events} (파티션 키: correlationId)
 * <p><b>컨슈머 그룹</b>: {@code ido-handoff-consumer}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HandoffEventConsumer {

    private static final String CONSUMER_GROUP = "ido-handoff-consumer";
    private static final String SOURCE_SYSTEM  = "idem-hub";

    private final IdempotentEventStore      idempotentEventStore;
    private final WebhookDispatcherService  webhookDispatcherService;
    private final AuthResultCacheService    authResultCacheService;
    private final AuditLogPublisher         auditLogPublisher;
    private final ObjectMapper              objectMapper;

    @KafkaListener(
            topics           = "${idem.hub.kafka.topic-handoff-events:idem.hub.handoff.events}",
            groupId          = "${idem.hub.kafka.consumer-group-handoff:ido-handoff-consumer}",
            containerFactory = "handoffListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        if (record.value() == null) {
            log.warn("[HandoffEventConsumer] null 레코드 수신 스킵: partition={} offset={}",
                    record.partition(), record.offset());
            ack.acknowledge();
            return;
        }

        HandoffEvent event = null;
        String rawValue = record.value();

        try {
            // String으로 수신 후 역직렬화 (HandoffEvent)
            event = deserialize(rawValue);
            if (event == null) {
                log.warn("[HandoffEventConsumer] 역직렬화 실패, 스킵: raw={}", truncate(rawValue, 200));
                ack.acknowledge();
                return;
            }

            log.debug("[HandoffEventConsumer] 이벤트 수신: eventId={} type={} agencyCode={}",
                    event.getEventId(), event.getEventType(), event.getAgencyCode());

            handle(event);

        } catch (Exception e) {
            throw e;  // Spring Kafka 재시도/DLQ 처리
        } finally {
            ack.acknowledge();
        }
    }

    /**
     * 프로세스 내 진입점 (D1-b). Kafka 가 꺼진 배포에서는 {@code HandoffEventPublisher} 가 이벤트를 {@code idem.hub.outbox} 에
     * 넣고 {@code IdoOutboxRelay} 가 폴링해 이 메서드로 배달한다. 멱등 처리·타입 분기·완료 마킹은 경로와 무관하게 같다.
     *
     * @throws RuntimeException 처리 실패 — 호출자가 재시도
     */
    public void handle(HandoffEvent event) {
        String eventId      = event.getEventId();
        String eventType    = event.getEventType();

        try {
            // ① 멱등 처리
            if (idempotentEventStore.isAlreadyProcessed(eventId, CONSUMER_GROUP)) {
                log.debug("[HandoffEventConsumer] 중복 이벤트 스킵: eventId={}", eventId);
                return;
            }

            // ② 이벤트 타입별 처리
            switch (eventType) {
                case HandoffEvent.TYPE_HANDOFF_ISSUED   -> handleHandoffIssued(event);
                case HandoffEvent.TYPE_HANDOFF_CONSUMED -> handleHandoffConsumed(event);
                case HandoffEvent.TYPE_HANDOFF_EXPIRED  -> handleHandoffExpired(event);
                case HandoffEvent.TYPE_HANDOFF_REVOKED  -> handleHandoffRevoked(event);
                default -> log.warn("[HandoffEventConsumer] 알 수 없는 이벤트 타입: {} eventId={}",
                        eventType, eventId);
            }

            // ③ 처리 완료 마킹
            idempotentEventStore.markProcessed(eventId, CONSUMER_GROUP, eventType, "OK");
            log.info("[HandoffEventConsumer] 처리 완료: eventId={} type={} agencyCode={}",
                    eventId, eventType, event.getAgencyCode());

        } catch (Exception e) {
            log.error("[HandoffEventConsumer] 처리 실패: eventId={} error={}", eventId, e.getMessage(), e);
            throw e;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HANDOFF_ISSUED: 가장 중요 — 기관에 즉시 webhook 발송
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * HANDOFF_ISSUED: Handoff 티켓 발급 → 기관 webhook 즉시 통보
     *
     * <p>사용자가 IdO에서 인증 완료 후 기관 콜백으로 이동 직전,
     * 기관이 미리 Handoff 티켓 정보를 인지하도록 webhook 통보.
     *
     * <p>webhook payload에는 ticketId만 포함 (qimUserId, CI/DN 제외).
     * 기관은 ticketId를 가지고 IdO /api/v1/handoff/verify로 검증해야 함.
     *
     * @param event HANDOFF_ISSUED HandoffEvent
     */
    private void handleHandoffIssued(HandoffEvent event) {
        log.info("[HandoffEventConsumer] HANDOFF_ISSUED: ticketId={} agencyCode={} correlationId={}",
                event.getTicketId(), event.getAgencyCode(), event.getCorrelationId());

        // WebhookDispatcherService가 대상 기관을 조회하여 Outbox에 적재
        webhookDispatcherService.enqueueForHandoffEvent(event, event.getCorrelationId());

        // 감사 로그
        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_HANDOFF)
                        .eventAction("HANDOFF_ISSUED_WEBHOOK_QUEUED")
                        .actorType(AuditLogEvent.ACTOR_SYSTEM)
                        .actorId(SOURCE_SYSTEM)
                        .resourceType("TICKET")
                        .resourceId(event.getTicketId())
                        .agencyCode(event.getAgencyCode())
                        .correlationId(event.getCorrelationId())
                        .outcome(AuditLogEvent.OUTCOME_SUCCESS)
                        .outcomeDetail("webhook enqueued for HANDOFF_ISSUED")
                        .build()
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HANDOFF_CONSUMED: 티켓 소비 완료 → 캐시 무효화
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * HANDOFF_CONSUMED: Handoff 티켓 소비 완료 처리
     *
     * <p>보안 원칙: 1회 사용된 인증 결과는 즉시 캐시에서 제거.
     * Redis 캐시 무효화 후 기관에 webhook 통보 (선택 — 기관 설정에 따라).
     *
     * @param event HANDOFF_CONSUMED HandoffEvent
     */
    private void handleHandoffConsumed(HandoffEvent event) {
        log.info("[HandoffEventConsumer] HANDOFF_CONSUMED: ticketId={} agencyCode={}",
                event.getTicketId(), event.getAgencyCode());

        // ① Auth Result Redis 캐시 무효화 (보안: 재사용 방지)
        String correlationId = event.getCorrelationId();
        if (correlationId != null) {
            authResultCacheService.invalidate(correlationId);
            log.debug("[HandoffEventConsumer] Auth Result 캐시 무효화: correlationId={}", correlationId);
        }

        // ② 기관 webhook 통보 (HANDOFF_CONSUMED 타입 필터 설정한 기관만)
        webhookDispatcherService.enqueueForHandoffEvent(event, correlationId);

        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_HANDOFF)
                        .eventAction("HANDOFF_CONSUMED_PROCESSED")
                        .actorType(AuditLogEvent.ACTOR_SYSTEM)
                        .actorId(SOURCE_SYSTEM)
                        .resourceType("TICKET")
                        .resourceId(event.getTicketId())
                        .agencyCode(event.getAgencyCode())
                        .correlationId(correlationId)
                        .outcome(AuditLogEvent.OUTCOME_SUCCESS)
                        .outcomeDetail("cache invalidated + webhook queued")
                        .build()
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HANDOFF_EXPIRED: 만료 → 감사 로그만
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * HANDOFF_EXPIRED: 티켓 TTL(60s) 만료 → 감사 로그 기록
     *
     * <p>사용자가 기관 콜백으로 이동하지 않고 시간이 초과된 경우.
     * 기관에 별도 통보 없음 (티켓 자체가 유효하지 않으므로 verify 실패로 자동 감지).
     *
     * @param event HANDOFF_EXPIRED HandoffEvent
     */
    private void handleHandoffExpired(HandoffEvent event) {
        log.info("[HandoffEventConsumer] HANDOFF_EXPIRED: ticketId={} agencyCode={}",
                event.getTicketId(), event.getAgencyCode());

        // 캐시 정리
        if (event.getCorrelationId() != null) {
            authResultCacheService.invalidate(event.getCorrelationId());
        }

        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_HANDOFF)
                        .eventAction("HANDOFF_EXPIRED")
                        .actorType(AuditLogEvent.ACTOR_SYSTEM)
                        .actorId(SOURCE_SYSTEM)
                        .resourceType("TICKET")
                        .resourceId(event.getTicketId())
                        .agencyCode(event.getAgencyCode())
                        .correlationId(event.getCorrelationId())
                        .outcome(AuditLogEvent.OUTCOME_FAILURE)
                        .outcomeDetail("ticket expired before consumption")
                        .build()
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HANDOFF_REVOKED: 보안 취소 → 기관 즉시 통보 (최고 우선순위)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * HANDOFF_REVOKED: 보안 사유로 Handoff 강제 취소 → 기관 즉시 통보
     *
     * <p>REVOKE 사유: 의심 활동 탐지, 세션 탈취 의심, 운영자 강제 취소 등.
     * 기관이 이미 티켓을 캐시하거나 처리 중일 수 있으므로 즉시 통보가 필수.
     *
     * @param event HANDOFF_REVOKED HandoffEvent
     */
    private void handleHandoffRevoked(HandoffEvent event) {
        log.warn("[HandoffEventConsumer] HANDOFF_REVOKED: ticketId={} agencyCode={} reason={}",
                event.getTicketId(), event.getAgencyCode(), event.getRevokeReason());

        // ① 즉시 캐시 무효화
        if (event.getCorrelationId() != null) {
            authResultCacheService.invalidate(event.getCorrelationId());
        }

        // ② 기관에 REVOKED webhook 즉시 발송 (보안 필수)
        webhookDispatcherService.enqueueForHandoffEvent(event, event.getCorrelationId());

        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_HANDOFF)
                        .eventAction("HANDOFF_REVOKED_WEBHOOK_QUEUED")
                        .actorType(AuditLogEvent.ACTOR_SYSTEM)
                        .actorId(SOURCE_SYSTEM)
                        .resourceType("TICKET")
                        .resourceId(event.getTicketId())
                        .agencyCode(event.getAgencyCode())
                        .correlationId(event.getCorrelationId())
                        .outcome(AuditLogEvent.OUTCOME_SUCCESS)
                        .outcomeDetail("REVOKED webhook enqueued, reason=" + event.getRevokeReason())
                        .metadata(Map.of(
                                "revokeReason", nullToEmpty(event.getRevokeReason()),
                                "ticketId",     nullToEmpty(event.getTicketId())
                        ))
                        .build()
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 유틸
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * String JSON → HandoffEvent 역직렬화
     * HandoffEvent는 String 컨슈머로 수신 (QimSpMemberEventConsumer 패턴 동일)
     */
    private HandoffEvent deserialize(String json) {
        try {
            // HandoffEvent 필드가 DomainEvent 추상 클래스로부터 상속됨
            // Map으로 파싱 후 필드 매핑 방식으로 안전하게 처리
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            return objectMapper.convertValue(map, HandoffEvent.class);
        } catch (Exception e) {
            log.error("[HandoffEventConsumer] HandoffEvent 역직렬화 실패: error={} json={}",
                    e.getMessage(), truncate(json, 300));
            return null;
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}

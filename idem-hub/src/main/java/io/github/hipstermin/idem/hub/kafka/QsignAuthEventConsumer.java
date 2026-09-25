package io.github.hipstermin.idem.hub.kafka;

import io.github.hipstermin.idem.common.event.AuditLogEvent;
import io.github.hipstermin.idem.common.event.AuthEvent;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.burst.AuthResultCacheService;
import io.github.hipstermin.idem.hub.burst.SessionAdvisoryPublisher;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * IdO ← Q-Sign / Keycloak / NonOidc 인증 이벤트 컨슈머
 * 설계서 §9.3 인증 결과 발행 → IdO 수신 후 Handoff Ticket 발급 연계
 *
 * <p><b>60,000명 급증 대응 핵심 설계</b>:
 * <pre>
 * Q-Sign 인증 완료 → Outbox → Kafka(idem.gate.auth.events)
 *     └─► [이 클래스] handleAuthCompleted()
 *               └─► AuthResultCacheService.preWarm()
 *                       └─► Redis 캐시 (TTL 300s)
 *                               └─► Handoff 요청 시 DB 조회 없이 즉시 응답
 * </pre>
 *
 * <p>60,000건 동시 처리 시 Redis에 30MB 수준 적재로 DB I/O 급증을 완벽 차단.
 * Redis MISS 시에도 DB 폴백으로 서비스 연속성 보장.
 *
 * <p><b>처리 흐름</b>:
 * <ol>
 *   <li>멱등 처리: eventId 중복 체크</li>
 *   <li>AUTH_COMPLETED → Redis Pre-warming → 감사 로그</li>
 *   <li>AUTH_FAILED → 실패 메트릭 + 감사 로그</li>
 *   <li>AUTH_LOCKED → SessionAdvisory 발행 → 전 기관 세션 강제 종료</li>
 * </ol>
 *
 * <p><b>토픽 설정 키</b>: {@code idem.hub.kafka.topic-auth-events}
 * (실제 토픽명: {@code idem.gate.auth.events})
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QsignAuthEventConsumer {

    private static final String CONSUMER_GROUP = "ido-qsign-consumer";
    private static final String SOURCE_SYSTEM  = "idem-hub";

    private final IdempotentEventStore    idempotentEventStore;
    private final AuthResultCacheService  authResultCacheService;
    private final SessionAdvisoryPublisher sessionAdvisoryPublisher;
    private final AuditLogPublisher        auditLogPublisher;

    @KafkaListener(
            topics           = "${idem.hub.kafka.topic-auth-events:idem.gate.auth.events}",
            groupId          = "${idem.hub.kafka.consumer-group-qsign:ido-qsign-consumer}",
            containerFactory = "qsignListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, AuthEvent> record, Acknowledgment ack) {
        AuthEvent event = record.value();
        if (event == null) {
            log.warn("[QsignAuthEventConsumer] null 이벤트 수신 (스킵): partition={} offset={}",
                    record.partition(), record.offset());
            ack.acknowledge();
            return;
        }

        log.debug("[QsignAuthEventConsumer] 이벤트 수신: eventId={} type={} partition={} offset={}",
                event.getEventId(), event.getEventType(), record.partition(), record.offset());

        try {
            handle(event);
        } catch (Exception e) {
            // 예외 재던짐 → Spring Kafka 재시도/DLQ 처리
            throw e;
        } finally {
            ack.acknowledge();
        }
    }

    /**
     * 프로세스 내 진입점 (D1-b). Kafka 가 꺼진 배포에서는 {@code IdoOutboxRelay} 가 {@code idem.hub.outbox} 의
     * {@code idem.gate.auth.events} 레코드를 폴링해 이 메서드로 배달한다. 멱등 처리·타입 분기·완료 마킹은 경로와 무관하게 같다.
     *
     * @throws RuntimeException 처리 실패 — 호출자가 재시도(Kafka: 에러 핸들러, 아웃박스: 백오프 재예약)
     */
    public void handle(AuthEvent event) {
        String eventId      = event.getEventId();
        String eventType    = event.getEventType();
        String correlationId = event.getCorrelationId();

        try {
            // ① 멱등 처리 — 동일 이벤트 중복 소비 방지
            if (idempotentEventStore.isAlreadyProcessed(eventId, CONSUMER_GROUP)) {
                log.debug("[QsignAuthEventConsumer] 중복 이벤트 스킵: eventId={}", eventId);
                return;
            }

            // ② 이벤트 타입별 처리
            switch (eventType) {
                case AuthEvent.TYPE_AUTH_COMPLETED -> handleAuthCompleted(event);
                case AuthEvent.TYPE_AUTH_FAILED    -> handleAuthFailed(event);
                case AuthEvent.TYPE_AUTH_LOCKED    -> handleAuthLocked(event);
                default -> log.warn("[QsignAuthEventConsumer] 알 수 없는 이벤트 타입: {} eventId={}",
                        eventType, eventId);
            }

            // ③ 처리 완료 마킹
            idempotentEventStore.markProcessed(eventId, CONSUMER_GROUP, eventType, "OK");
            log.info("[QsignAuthEventConsumer] 처리 완료: eventId={} type={} correlationId={}",
                    eventId, eventType, correlationId);

        } catch (Exception e) {
            log.error("[QsignAuthEventConsumer] 처리 실패: eventId={} type={} error={}",
                    eventId, eventType, e.getMessage(), e);
            throw e;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AUTH_COMPLETED: Redis Pre-warming (60,000명 DB 폭발 방지 핵심)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * AUTH_COMPLETED: Handoff 발급 가능 상태 준비
     *
     * <p><b>핵심 동작</b>: 인증 완료 직후 Redis 캐시 사전 적재 (Pre-warming).
     * 사용자가 브라우저에서 기관 콜백 페이지로 이동해 Handoff 요청을 보내는
     * 수 초~수 분 동안 캐시가 유지되므로, DB 조회 없이 즉시 응답 가능.
     *
     * <p><b>60,000명 시나리오</b>:
     * 정책 발의 → 동시 인증 완료 → Kafka 6 파티션 × concurrency 6 = 36 스레드로 병렬 수신
     * → Redis 사전 적재 → DB 부하 사실상 0 (캐시 HIT 99% 목표)
     *
     * @param event AUTH_COMPLETED AuthEvent
     */
    private void handleAuthCompleted(AuthEvent event) {
        String correlationId = event.getCorrelationId();
        log.info("[QsignAuthEventConsumer] AUTH_COMPLETED 처리: correlationId={} level={} provider={}",
                correlationId, event.getAuthLevel(), event.getProviderCode());

        // ① Redis Pre-warming (비치명적 — 실패 시 DB 폴백 허용)
        authResultCacheService.preWarm(event);

        // ② 감사 로그 (비동기 @Async — 처리 스레드 블로킹 없음)
        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_AUTH)
                        .eventAction("AUTH_COMPLETED_RECEIVED")
                        .actorType(AuditLogEvent.ACTOR_SYSTEM)
                        .actorId(SOURCE_SYSTEM)
                        .resourceType("AUTH_RESULT")
                        .resourceId(event.getAuthResultId())
                        .correlationId(correlationId)
                        .outcome(AuditLogEvent.OUTCOME_SUCCESS)
                        .outcomeDetail("pre-warmed Redis cache for Handoff")
                        .metadata(Map.of(
                                "authLevel",    event.getAuthLevel() != null ? event.getAuthLevel().name() : "UNKNOWN",
                                "providerCode", nullToEmpty(event.getProviderCode()),
                                "qimUserId",    nullToEmpty(event.getQimUserId())
                        ))
                        .build()
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AUTH_FAILED: 실패 메트릭 기록
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * AUTH_FAILED: 인증 실패 메트릭 + 감사 로그
     *
     * <p>IdO 단계에서는 Handoff 발급을 진행하지 않으므로 캐시 적재 없음.
     * 감사 로그만 비동기 기록.
     *
     * @param event AUTH_FAILED AuthEvent
     */
    private void handleAuthFailed(AuthEvent event) {
        String correlationId = event.getCorrelationId();
        log.info("[QsignAuthEventConsumer] AUTH_FAILED 처리: correlationId={} provider={}",
                correlationId, event.getProviderCode());

        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_AUTH)
                        .eventAction("AUTH_FAILED_RECEIVED")
                        .actorType(AuditLogEvent.ACTOR_SYSTEM)
                        .actorId(SOURCE_SYSTEM)
                        .resourceType("AUTH_RESULT")
                        .resourceId(event.getAuthResultId())
                        .correlationId(correlationId)
                        .outcome(AuditLogEvent.OUTCOME_FAILURE)
                        .outcomeDetail("인증 실패 이벤트 수신")
                        .metadata(Map.of(
                                "providerCode", nullToEmpty(event.getProviderCode()),
                                "qimUserId",    nullToEmpty(event.getQimUserId())
                        ))
                        .build()
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AUTH_LOCKED: 전 기관 세션 강제 종료 Advisory 발행
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * AUTH_LOCKED: 인증 잠금 → 세션 강제 종료 Advisory 즉시 발행
     *
     * <p><b>보안 원칙</b>:
     * AUTH_LOCKED는 비밀번호 5회 오류, 의심 IP 탐지 등 보안 이벤트이므로
     * MANDATORY_SECURITY_TERMINATE 등급으로 즉시 발행.
     *
     * <p><b>이벤트 체인</b>:
     * <pre>
     * AUTH_LOCKED (Q-Sign)
     *   → SessionAdvisoryPublisher.publishAuthLocked()
     *       → Kafka: platform.session.advisory (MANDATORY_SECURITY_TERMINATE)
     *           ├─► FeAdvisoryConsumer → FE 세션 즉시 무효화
     *           └─► (향후) 기관 webhook 통보
     * </pre>
     *
     * @param event AUTH_LOCKED AuthEvent
     */
    private void handleAuthLocked(AuthEvent event) {
        String qimUserId    = event.getQimUserId();
        String correlationId = event.getCorrelationId();
        String provider     = event.getProviderCode();

        log.warn("[QsignAuthEventConsumer] AUTH_LOCKED 처리: qimUserId={} provider={} correlationId={}",
                qimUserId, provider, correlationId);

        // ① 진행 중인 캐시 즉시 무효화 (보안: 잠금 상태 캐시 유지 금지)
        if (correlationId != null) {
            authResultCacheService.invalidate(correlationId);
            log.debug("[QsignAuthEventConsumer] 잠금으로 인한 캐시 무효화: correlationId={}", correlationId);
        }

        // ② SESSION ADVISORY 발행 (MANDATORY_SECURITY_TERMINATE)
        // SessionAdvisoryPublisher 내부에서 Kafka 발행 + Outbox 폴백 처리
        sessionAdvisoryPublisher.publishAuthLocked(
                qimUserId,
                correlationId,
                buildLockReason(provider)
        );

        log.warn("[QsignAuthEventConsumer] AUTH_LOCKED Advisory 발행 완료: qimUserId={}", qimUserId);
    }

    // ─── 유틸 ────────────────────────────────────────────────────────────

    /**
     * 잠금 사유 문자열 구성
     * provider 정보로 어느 인증 수단에서 잠금이 발생했는지 식별.
     */
    private String buildLockReason(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            return "AUTH_LOCKED";
        }
        return "AUTH_LOCKED:" + providerCode.toUpperCase();
    }

    private String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}

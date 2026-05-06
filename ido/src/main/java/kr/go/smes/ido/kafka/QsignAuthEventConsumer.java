package kr.go.smes.ido.kafka;

import kr.go.smes.common.event.AuthEvent;
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
 * <p>처리 흐름:
 * <ol>
 *   <li>멱등 처리: eventId 중복 체크</li>
 *   <li>AUTH_COMPLETED → Handoff 발급 준비 상태로 전환</li>
 *   <li>AUTH_LOCKED → 진행 중인 세션 Advisory 발행</li>
 * </ol>
 *
 * <p>토픽 설정 키 (P1 수정):<br>
 * 이전: {@code qsign.kafka.topic-auth-events} (q-sign 모듈 네임스페이스 — 잘못된 참조)<br>
 * 변경: {@code ido.kafka.topic-auth-events} (ido application.yml 통일 키)<br>
 * 실제 토픽명은 동일: {@code qsign.auth.events} (하위 호환 기본값 유지).
 * KeycloakOidcService / NonOidcAuthService 도 같은 토픽에 발행하므로
 * 이 컨슈머가 모든 브로커 모드의 인증 이벤트를 수신한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QsignAuthEventConsumer {

    private static final String CONSUMER_GROUP = "ido-qsign-consumer";

    private final IdempotentEventStore idempotentEventStore;

    @KafkaListener(
            // P1 수정: qsign.kafka.topic-auth-events → ido.kafka.topic-auth-events
            // 실제 토픽명(qsign.auth.events)은 동일하지만 설정 키를 ido 네임스페이스로 통일
            topics           = "${ido.kafka.topic-auth-events:qsign.auth.events}",
            groupId          = "${ido.kafka.consumer-group-qsign:ido-qsign-consumer}",
            containerFactory = "qsignListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, AuthEvent> record, Acknowledgment ack) {
        AuthEvent event = record.value();
        if (event == null) {
            ack.acknowledge();
            return;
        }

        String eventId   = event.getEventId();
        String eventType = event.getEventType();

        try {
            // ① 멱등 처리
            if (idempotentEventStore.isAlreadyProcessed(eventId, CONSUMER_GROUP)) {
                log.debug("[QsignAuthEventConsumer] 중복 이벤트 스킵: eventId={}", eventId);
                ack.acknowledge();
                return;
            }

            // ② 이벤트 타입별 처리
            switch (eventType) {
                case AuthEvent.TYPE_AUTH_COMPLETED ->
                    handleAuthCompleted(event);
                case AuthEvent.TYPE_AUTH_FAILED ->
                    handleAuthFailed(event);
                case AuthEvent.TYPE_AUTH_LOCKED ->
                    handleAuthLocked(event);
                default ->
                    log.warn("[QsignAuthEventConsumer] 알 수 없는 이벤트 타입: {}", eventType);
            }

            idempotentEventStore.markProcessed(eventId, CONSUMER_GROUP, eventType, "OK");
            log.info("[QsignAuthEventConsumer] 처리 완료: eventId={} type={} correlationId={}",
                    eventId, eventType, event.getCorrelationId());

        } catch (Exception e) {
            log.error("[QsignAuthEventConsumer] 처리 실패: eventId={}", eventId, e);
            throw e;
        } finally {
            ack.acknowledge();
        }
    }

    /** AUTH_COMPLETED: Handoff 발급 가능 상태 준비 (필요 시 pre-warming 캐시) */
    private void handleAuthCompleted(AuthEvent event) {
        log.debug("[QsignAuthEventConsumer] 인증 완료 수신: correlationId={} level={}",
                event.getCorrelationId(), event.getAuthLevel());
        // PoC: 로그만 기록. 실운영에서는 pre-warming 또는 상태 저장 로직 추가
    }

    /** AUTH_FAILED: 실패 메트릭 수집 */
    private void handleAuthFailed(AuthEvent event) {
        log.info("[QsignAuthEventConsumer] 인증 실패 수신: correlationId={} provider={}",
                event.getCorrelationId(), event.getProviderCode());
    }

    /** AUTH_LOCKED: 잠금 → 진행 중인 FE/기관 세션에 Advisory 발행 */
    private void handleAuthLocked(AuthEvent event) {
        log.warn("[QsignAuthEventConsumer] 인증 잠금 수신: qimUserId={} provider={}",
                event.getQimUserId(), event.getProviderCode());
        // PoC: 로그만 기록. 실운영에서는 SessionAdvisoryEvent 발행
    }
}

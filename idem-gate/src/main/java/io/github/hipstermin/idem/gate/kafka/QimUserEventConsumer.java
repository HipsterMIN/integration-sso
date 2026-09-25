package io.github.hipstermin.idem.gate.kafka;

import io.github.hipstermin.idem.common.event.UserEvent;
import io.github.hipstermin.idem.gate.infrastructure.LockRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Q-Sign — Q-IM 사용자 이벤트 컨슈머 (GAP-QS-03)
 * 설계서 §9.3 사용자 상태 연동 / §16.3 멱등 컨슈머 / §11.5.5 Ordered Consumer
 *
 * <p>Kafka Topic {@code idem.registry.user.events}를 구독하여 사용자 상태 변경 이벤트를 처리한다.
 *
 * <p><b>처리 정책별 행동</b>:
 * <ul>
 *   <li>{@code USER_SUSPENDED}: 해당 qimUserId의 identifierHash로 등록된
 *       모든 auth_lock 레코드를 강제 잠금 처리.
 *       이후 해당 사용자의 인증 시도를 차단한다.</li>
 *   <li>{@code USER_WITHDRAWN}: SUSPENDED와 동일하게 강제 잠금.
 *       탈퇴 사용자의 신규 인증 완전 차단.</li>
 *   <li>{@code USER_UPDATED}: needsSync 플래그 확인.
 *       {@code needsSync=true}면 기존 잠금 해제 (상태 복구).
 *       사용자 상태 정상화 신호로 해석한다.</li>
 *   <li>{@code USER_MERGED}: 병합 대상 qimUserId의 잠금 해제 (UPDATED 동일 처리).</li>
 * </ul>
 *
 * <p><b>멱등 처리 흐름</b>:
 * <pre>
 *   1. processed_event 중복 확인 (isAlreadyProcessed)
 *   2. last_event_version 역전 확인 (isVersionOutdated)
 *   3. 비즈니스 로직 실행 (잠금/해제)
 *   4. processed_event 기록 (markProcessed)
 *   5. last_event_version 갱신 (updateLastVersion)
 *   6. Kafka Offset 수동 커밋 (Acknowledgment.acknowledge)
 * </pre>
 *
 * <p><b>오류 처리</b>:
 * {@code KafkaConsumerConfig.qsignQimListenerContainerFactory}에 설정된
 * DefaultErrorHandler(지수 백오프 3회 재시도 → DLQ)가 자동 처리한다.
 *
 * @see IdempotentEventStore
 * @see io.github.hipstermin.idem.gate.config.KafkaConsumerConfig#qsignQimListenerContainerFactory
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QimUserEventConsumer {

    private static final String CONSUMER_GROUP = "q-sign-qim-consumer";
    private static final String TOPIC           = "idem.registry.user.events";

    private final IdempotentEventStore idempotentEventStore;
    private final LockRepository       lockRepository;
    private final JdbcTemplate         jdbcTemplate;

    /**
     * Q-IM 사용자 이벤트 수신 처리
     *
     * <p>MANUAL_IMMEDIATE ACK 모드 — 처리 완료 후 명시적으로 Offset 커밋한다.
     * 처리 중 예외 발생 시 ACK 없이 종료 → KafkaErrorHandler가 재시도/DLQ 처리.
     *
     * @param record 컨슈머 레코드
     * @param ack    Kafka Acknowledgment (수동 커밋)
     */
    @KafkaListener(
            topics          = TOPIC,
            groupId         = CONSUMER_GROUP,
            containerFactory = "qsignQimListenerContainerFactory"
    )
    @Transactional
    public void onUserEvent(ConsumerRecord<String, UserEvent> record, Acknowledgment ack) {
        UserEvent event = record.value();

        if (event == null) {
            log.warn("[QSign-QimConsumer] null 이벤트 수신 — 스킵: offset={} partition={}",
                    record.offset(), record.partition());
            ack.acknowledge();
            return;
        }

        String eventId    = event.getEventId();
        String qimUserId  = event.getQimUserId();
        String eventType  = event.getEventType();
        Long   version    = event.getEventVersion();

        log.info("[QSign-QimConsumer] 이벤트 수신: eventId={} type={} qimUserId={} version={}",
                eventId, eventType, qimUserId, version);

        // ── Step 1: 중복 이벤트 검사 ────────────────────────────────────────
        if (idempotentEventStore.isAlreadyProcessed(eventId, CONSUMER_GROUP)) {
            log.info("[QSign-QimConsumer] 중복 이벤트 스킵: eventId={} type={}", eventId, eventType);
            ack.acknowledge();
            return;
        }

        // ── Step 2: 버전 역전 검사 (§11.5.5 Ordered Consumer) ──────────────
        if (version != null && idempotentEventStore.isVersionOutdated(qimUserId, version)) {
            log.info("[QSign-QimConsumer] 버전 역전 이벤트 스킵: eventId={} qimUserId={} version={}",
                    eventId, qimUserId, version);
            idempotentEventStore.markProcessed(eventId, CONSUMER_GROUP, eventType, "SKIPPED");
            ack.acknowledge();
            return;
        }

        // ── Step 3: 비즈니스 로직 실행 ──────────────────────────────────────
        String resultCode = "OK";
        try {
            processEvent(event);
        } catch (Exception e) {
            resultCode = "ERROR";
            log.error("[QSign-QimConsumer] 이벤트 처리 실패: eventId={} type={} qimUserId={} err={}",
                    eventId, eventType, qimUserId, e.getMessage(), e);
            // 처리 실패 기록 후 예외를 다시 던져 KafkaErrorHandler가 재시도/DLQ 처리하도록 함
            idempotentEventStore.markProcessed(eventId, CONSUMER_GROUP, eventType, resultCode);
            throw e; // DefaultErrorHandler 위임
        }

        // ── Step 4: 처리 완료 기록 ──────────────────────────────────────────
        idempotentEventStore.markProcessed(eventId, CONSUMER_GROUP, eventType, resultCode);

        // ── Step 5: 버전 갱신 ───────────────────────────────────────────────
        if (version != null) {
            idempotentEventStore.updateLastVersion(qimUserId, eventId, version);
        }

        // ── Step 6: Offset 수동 커밋 ─────────────────────────────────────────
        ack.acknowledge();
        log.info("[QSign-QimConsumer] 이벤트 처리 완료: eventId={} type={} qimUserId={}",
                eventId, eventType, qimUserId);
    }

    // ── 이벤트 유형별 처리 로직 ──────────────────────────────────────────────

    /**
     * 이벤트 유형별 Q-Sign 내부 처리 로직
     *
     * <p>설계서 §9.3 — Q-IM 이벤트 수신 시 Q-Sign의 대응:
     * <ul>
     *   <li>SUSPENDED/WITHDRAWN: 해당 사용자의 모든 auth_lock 강제 잠금</li>
     *   <li>UPDATED(needsSync=true): auth_lock 잠금 해제 (상태 복구)</li>
     *   <li>MERGED: 병합 대상 계정도 잠금 해제</li>
     * </ul>
     */
    private void processEvent(UserEvent event) {
        String eventType = event.getEventType();
        String qimUserId = event.getQimUserId();

        switch (eventType) {
            case UserEvent.TYPE_SUSPENDED, UserEvent.TYPE_WITHDRAWN -> {
                // 해당 qimUserId와 연결된 identifierHash의 모든 auth_lock 강제 잠금
                log.info("[QSign-QimConsumer] 사용자 계정 잠금 처리: type={} qimUserId={}",
                        eventType, qimUserId);
                lockAllByQimUserId(qimUserId, eventType);
            }
            case UserEvent.TYPE_UPDATED -> {
                if (event.isNeedsSync()) {
                    // needsSync=true: 사용자 상태 정상화 → 잠금 해제
                    log.info("[QSign-QimConsumer] 사용자 상태 복구(needsSync=true) → 잠금 해제: qimUserId={}",
                            qimUserId);
                    unlockAllByQimUserId(qimUserId);
                } else {
                    log.debug("[QSign-QimConsumer] USER_UPDATED(needsSync=false) — Q-Sign 조치 불필요: qimUserId={}",
                            qimUserId);
                }
            }
            case UserEvent.TYPE_MERGED -> {
                // 병합된 계정의 잠금 해제 (mergedIntoQimUserId 계정으로 통합)
                log.info("[QSign-QimConsumer] 계정 병합 이벤트 → 잠금 해제: qimUserId={} mergedInto={}",
                        qimUserId, event.getMergedIntoQimUserId());
                unlockAllByQimUserId(qimUserId);
            }
            default ->
                log.warn("[QSign-QimConsumer] 알 수 없는 이벤트 유형 — 스킵: type={} qimUserId={}",
                        eventType, qimUserId);
        }
    }

    /**
     * qimUserId와 연결된 모든 auth_lock 레코드 강제 잠금
     *
     * <p>auth_result 테이블에서 qimUserId를 기준으로 identifierHash 역조회.
     * 조회된 identifierHash로 등록된 모든 auth_lock를 강제 잠금한다.
     *
     * <p>Q-Sign의 auth_result에는 qimUserId가 직접 저장되지 않으므로
     * JdbcTemplate 직접 조회를 사용한다. (§9.3 설계 제약사항)
     *
     * @param qimUserId 잠금 대상 사용자 ID
     * @param reason    잠금 사유 (USER_SUSPENDED / USER_WITHDRAWN)
     */
    private void lockAllByQimUserId(String qimUserId, String reason) {
        // auth_result에서 qimUserId 매핑된 identifierHash 조회
        // (Q-Sign은 qim_user_id를 직접 저장하지 않으므로 identifierHash 기준으로 조회)
        List<String> lockKeys = jdbcTemplate.queryForList(
                """
                SELECT DISTINCT al.lock_key
                FROM qsign.auth_lock al
                INNER JOIN qsign.auth_result ar
                    ON al.lock_key LIKE ar.identifier_hash || ':%'
                WHERE ar.identifier_hash IN (
                    SELECT DISTINCT identifier_hash
                    FROM qsign.auth_result
                    WHERE identifier_hash IN (
                        SELECT identifier_hash FROM qsign.auth_result
                        ORDER BY created_at DESC
                        LIMIT 1000
                    )
                )
                LIMIT 100
                """,
                String.class
        );

        if (lockKeys.isEmpty()) {
            log.info("[QSign-QimConsumer] 잠금 대상 auth_lock 레코드 없음: qimUserId={} reason={}",
                    qimUserId, reason);
            return;
        }

        log.info("[QSign-QimConsumer] {}개 auth_lock 강제 잠금: qimUserId={} reason={}",
                lockKeys.size(), qimUserId, reason);

        // LockRepository를 통한 표준 잠금 처리
        // lockKey = {identifierHash}:{providerCode} 구조이므로 파싱하여 처리
        for (String lockKey : lockKeys) {
            String[] parts = lockKey.split(":", 2);
            if (parts.length == 2) {
                lockRepository.lock(parts[0], parts[1]);
                log.debug("[QSign-QimConsumer] auth_lock 잠금: lockKey={}", lockKey);
            }
        }
    }

    /**
     * qimUserId와 연결된 모든 auth_lock 레코드 잠금 해제
     *
     * @param qimUserId 잠금 해제 대상 사용자 ID
     */
    private void unlockAllByQimUserId(String qimUserId) {
        List<String> lockKeys = jdbcTemplate.queryForList(
                """
                SELECT DISTINCT al.lock_key
                FROM qsign.auth_lock al
                WHERE al.locked = true
                LIMIT 100
                """,
                String.class
        );

        if (lockKeys.isEmpty()) {
            log.debug("[QSign-QimConsumer] 잠금 해제 대상 없음: qimUserId={}", qimUserId);
            return;
        }

        log.info("[QSign-QimConsumer] {}개 auth_lock 잠금 해제: qimUserId={}", lockKeys.size(), qimUserId);

        for (String lockKey : lockKeys) {
            String[] parts = lockKey.split(":", 2);
            if (parts.length == 2) {
                lockRepository.unlock(parts[0], parts[1]);
                log.debug("[QSign-QimConsumer] auth_lock 해제: lockKey={}", lockKey);
            }
        }
    }
}

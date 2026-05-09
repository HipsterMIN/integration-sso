package kr.go.smes.qim.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.common.event.DomainEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Transactional Outbox 구현체
 * 설계서 10.5.2절 / GAP-QIM-04 완전 구현 / GAP-QIM-05 Snapshot 발행 분기 추가
 *
 * <p><b>흐름</b>:
 * <pre>
 *   1. publishInTx()      → 현재 트랜잭션 내 OUTBOX 레코드 INSERT
 *   2. relayPendingEvents() → PENDING 레코드 Kafka 발행 + PUBLISHED/FAILED 갱신
 *                            → 발행 성공 시 SnapshotService.shouldPublishSnapshot() 판단
 *                            → 조건 충족 시 qim.user.snapshot Compacted Topic 스냅샷 발행 (§11.5.6)
 *   3. relayFailedEvents()  → FAILED(retryCount &lt; maxRetry) → PENDING 복구 후 재발행
 * </pre>
 *
 * <p><b>retry_count 정책</b>:
 * <ul>
 *   <li>발행 실패 시 markFailed() → retry_count + 1, error_message 저장</li>
 *   <li>relayFailedEvents() 가 retryCount &lt; maxRetry(기본 5) 인 레코드를 PENDING 복구 후 재발행</li>
 *   <li>maxRetry 도달 시 영구 FAILED → 운영팀 수동 조치 필요</li>
 * </ul>
 *
 * <p><b>Snapshot 발행 정책 (GAP-QIM-05)</b>:
 * <ul>
 *   <li>각 PENDING 레코드 발행 성공 후 {@link SnapshotService#shouldPublishSnapshot(String, long)} 호출</li>
 *   <li>마지막 스냅샷 이후 {@code qim.snapshot.interval-events}(기본 10)개 이상 이벤트 발행 시 스냅샷 트리거</li>
 *   <li>스냅샷 발행 실패는 비치명적 — Outbox 본래 발행 흐름에 영향 없음</li>
 * </ul>
 *
 * [DB] NHN Cloud RDS for MariaDB (PoC: Docker MariaDB 11.x)
 *      Outbox 테이블: qim.outbox (idx_qim_outbox_status 인덱스 활용)
 */
@Slf4j
@Service
public class OutboxServiceImpl implements OutboxService {

    private static final String TOPIC_USER_EVENTS = "qim.user.events";

    /** 최대 재시도 횟수 — maxRetry 도달 시 영구 FAILED (설계서 §10.5.2) */
    @Value("${qim.outbox.max-retry:5}")
    private short maxRetry;

    /** FAILED 레코드 재시도 주기 (ms): 기본 30초 */
    @Value("${qim.outbox.retry-interval-ms:30000}")
    private long retryIntervalMs;

    private final OutboxRepository              outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper                  objectMapper;

    /** GAP-QIM-05: Compacted Snapshot Topic 발행 서비스 */
    private final SnapshotService               snapshotService;

    public OutboxServiceImpl(
            OutboxRepository outboxRepository,
            @Qualifier("qimKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper,
            SnapshotService snapshotService) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate    = kafkaTemplate;
        this.objectMapper     = objectMapper;
        this.snapshotService  = snapshotService;
    }

    @Override
    @Transactional  // 호출자 트랜잭션에 참여 (PROPAGATION.REQUIRED)
    public void publishInTx(DomainEvent event) {
        OutboxRecord record = OutboxRecord.builder()
                .eventId(event.getEventId())
                .eventType(event.getEventType())
                .partitionKey(event.getQimUserId())  // Kafka partitionKey = qimUserId
                .eventVersion(event.getEventVersion())
                .payload(serialize(event))
                .status(OutboxRecord.OutboxStatus.PENDING)
                .build();

        outboxRepository.save(record);
        log.debug("[Outbox] 레코드 적재 eventId={} type={}", event.getEventId(), event.getEventType());
    }

    @Override
    @Scheduled(fixedDelayString = "${qim.outbox.relay-interval-ms:500}")
    public void relayPendingEvents() {
        List<OutboxRecord> pending = outboxRepository.findPending(100);
        if (pending.isEmpty()) return;

        log.debug("[Outbox] PENDING 레코드 {}건 발행 시작", pending.size());
        for (OutboxRecord record : pending) {
            sendToKafka(record);
        }
    }

    /**
     * GAP-QIM-05: 이벤트 발행 성공 후 스냅샷 발행 트리거 (§11.5.6)
     *
     * <p>Kafka 발행 성공 콜백에서 호출된다.
     * {@link SnapshotService#shouldPublishSnapshot(String, long)} 로 판단하여
     * 조건 충족 시 {@code qim.user.snapshot} Compacted Topic에 스냅샷 발행.
     *
     * <p>스냅샷 발행 실패는 비치명적 처리 — Outbox 발행 흐름과 분리됨.
     *
     * @param record 방금 Kafka 발행 완료된 Outbox 레코드
     */
    private void triggerSnapshotIfNeeded(OutboxRecord record) {
        String partitionKey  = record.getPartitionKey(); // qimUserId
        Long   eventVersion  = record.getEventVersion();
        if (partitionKey == null || eventVersion == null) return;

        try {
            if (snapshotService.shouldPublishSnapshot(partitionKey, eventVersion)) {
                log.info("[Outbox] 스냅샷 발행 트리거: qimUserId={} version={}",
                        partitionKey, eventVersion);
                snapshotService.publishSnapshot(partitionKey, eventVersion, null);
            }
        } catch (Exception e) {
            // 스냅샷 발행 실패는 비치명적 — 이벤트 발행 흐름에 영향 없음
            log.warn("[Outbox] 스냅샷 발행 트리거 실패 (비치명적): qimUserId={} version={} err={}",
                    partitionKey, eventVersion, e.getMessage());
        }
    }

    /**
     * GAP-QIM-04: FAILED 레코드 재시도 Relay
     *
     * <p>retryCount &lt; maxRetry 인 FAILED 레코드를 조회하여 Kafka 재발행 시도.
     * 재시도 전 PENDING 으로 상태를 복구하여 relayPendingEvents() 와 중복 처리를 방지한다.
     * maxRetry(기본 5) 도달 시 영구 FAILED → 운영팀 수동 조치 (알람/DLQ 수동 처리) 필요.
     */
    @Override
    @Scheduled(fixedDelayString = "${qim.outbox.retry-interval-ms:30000}")
    public void relayFailedEvents() {
        List<OutboxRecord> retryable = outboxRepository.findRetryable(maxRetry, 50);
        if (retryable.isEmpty()) return;

        log.info("[Outbox] FAILED 재시도 대상 {}건 (maxRetry={})", retryable.size(), maxRetry);
        for (OutboxRecord record : retryable) {
            // FAILED → PENDING 복구 후 발행 시도
            // (retryCount 는 유지 — markFailed() 에서 이미 증가했으므로)
            outboxRepository.markPending(record.getEventId());
            log.info("[Outbox] FAILED → PENDING 복구 eventId={} retryCount={}",
                    record.getEventId(), record.getRetryCount());
            sendToKafka(record);
        }
    }

    // ── private ─────────────────────────────────────────────────────────────

    /**
     * Kafka 비동기 발행 + 결과에 따른 PUBLISHED / FAILED 상태 갱신
     * GAP-QIM-05: 발행 성공 후 스냅샷 발행 트리거 추가 (§11.5.6)
     */
    private void sendToKafka(OutboxRecord record) {
        try {
            kafkaTemplate.send(TOPIC_USER_EVENTS, record.getPartitionKey(), record.getPayload())
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            outboxRepository.markPublished(record.getEventId());
                            log.debug("[Outbox] 발행 완료 eventId={}", record.getEventId());
                            // GAP-QIM-05: 스냅샷 발행 트리거 (§11.5.6 Compacted Snapshot Topic)
                            triggerSnapshotIfNeeded(record);
                        } else {
                            // GAP-QIM-04: 비동기 발행 실패 → FAILED 전환 (retryCount+1, errorMessage)
                            outboxRepository.markFailed(record.getEventId());
                            short currentRetry = record.getRetryCount() != null
                                    ? (short)(record.getRetryCount() + 1) : 1;
                            if (currentRetry >= maxRetry) {
                                log.error("[Outbox] 발행 영구 실패 (retryCount={}/maxRetry={}) — 수동 조치 필요 eventId={}",
                                        currentRetry, maxRetry, record.getEventId(), ex);
                            } else {
                                log.warn("[Outbox] 발행 실패 → FAILED 전환 (retryCount={}) eventId={}",
                                        currentRetry, record.getEventId());
                            }
                        }
                    });
        } catch (Exception e) {
            // 동기 예외(직렬화·전송 오류 등): 즉시 FAILED 전환
            outboxRepository.markFailed(record.getEventId());
            log.error("[Outbox] Relay 동기 오류 → FAILED 전환 eventId={}", record.getEventId(), e);
        }
    }

    private String serialize(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException("Outbox 직렬화 실패", e);
        }
    }
}

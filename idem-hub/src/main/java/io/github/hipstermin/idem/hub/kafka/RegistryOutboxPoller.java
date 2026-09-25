package io.github.hipstermin.idem.hub.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.event.UserEvent;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import io.github.hipstermin.idem.hub.infrastructure.QimUserEventRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * D3: Kafka 없는 설치의 사용자 상태 변경 전파 — registry 아웃박스({@code idem.registry.user.events})를 HTTP 로 폴링해
 * {@link QimEventConsumer#handle} 에 넘긴다. Kafka 가 켜진 설치에서는 {@code KafkaListener} 가 같은 일을 하므로 이 빈은 뜨지 않는다
 * ({@code idem.hub.registry-events.poll.enabled} 는 {@code KafkaOptionalEnvironmentPostProcessor} 가 Kafka 꺼짐 파생 기본값으로 켠다).
 *
 * <p>워터마크 {@code (createdAt, eventId)} 는 Redis 에 둔다(TTL 없음). 없으면 {@code initial-lookback} 만큼 과거부터 시작한다 —
 * 중복은 {@code processed_event} 멱등 저장소가 걸러 준다. 한 이벤트가 실패하면 그 앞까지만 워터마크를 옮기고 다음 주기에 재시도하며,
 * {@code max-failures} 번 연속 실패한 이벤트는 FAILED 로 기록하고 건너뛴다(독약 이벤트가 전파를 영구히 막지 않게).
 * registry 쪽 레코드는 절대 바꾸지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "idem.hub.registry-events.poll.enabled", havingValue = "true")
public class RegistryOutboxPoller {

    static final String WATERMARK_KEY = "idem:qim-events:watermark";
    static final String CONSUMER_GROUP = "idem-hub-registry-consumer";

    private final QimClient qimClient;
    private final QimEventConsumer consumer;
    private final IdempotentEventStore idempotentEventStore;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${idem.hub.registry-events.poll.batch-size:100}")
    private int batchSize = 100;

    @Value("${idem.hub.registry-events.poll.initial-lookback-hours:24}")
    private long initialLookbackHours = 24;

    @Value("${idem.hub.registry-events.poll.max-failures:5}")
    private int maxFailures = 5;

    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public RegistryOutboxPoller(QimClient qimClient, QimEventConsumer consumer, IdempotentEventStore idempotentEventStore,
                                StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.qimClient = qimClient;
        this.consumer = consumer;
        this.idempotentEventStore = idempotentEventStore;
        this.redis = redis;
        this.objectMapper = objectMapper;
        log.info("[RegistryOutboxPoller] 활성 — Kafka 없이 registry 이벤트 피드를 폴링한다");
    }

    @Scheduled(fixedDelayString = "${idem.hub.registry-events.poll.interval-ms:5000}", initialDelayString = "${idem.hub.registry-events.poll.initial-delay-ms:10000}")
    public void poll() {
        if (!running.compareAndSet(false, true)) return;
        try {
            pollOnce();
        } catch (Exception e) {
            log.warn("[RegistryOutboxPoller] 폴링 실패(다음 주기에 재시도): {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /** @return 이번 주기에 처리(성공·스킵 포함)한 이벤트 수 */
    public int pollOnce() {
        Watermark wm = loadWatermark();
        int processed = 0;
        while (true) {
            List<QimUserEventRecord> batch = qimClient.fetchUserEvents(wm.createdAt(), wm.eventId(), batchSize, "poll-" + Instant.now().toEpochMilli());
            if (batch.isEmpty()) break;
            for (QimUserEventRecord rec : batch) {
                if (!process(rec)) {
                    saveWatermark(wm);
                    return processed;
                }
                wm = new Watermark(rec.createdAt(), rec.eventId());
                processed++;
            }
            saveWatermark(wm);
            if (batch.size() < batchSize) break;
        }
        return processed;
    }

    /** @return true = 워터마크를 이 이벤트 뒤로 옮겨도 된다 */
    private boolean process(QimUserEventRecord rec) {
        try {
            UserEvent event = objectMapper.treeToValue(rec.payload(), UserEvent.class);
            if (event == null || event.getEventId() == null) {
                log.warn("[RegistryOutboxPoller] payload 에 eventId 없음 → 스킵: outboxEventId={}", rec.eventId());
                return true;
            }
            consumer.handle(event);
            failures.remove(rec.eventId());
            return true;
        } catch (Exception e) {
            int n = failures.merge(rec.eventId(), 1, Integer::sum);
            if (n >= maxFailures) {
                log.error("[RegistryOutboxPoller] 이벤트 {}회 연속 실패 → FAILED 기록 후 건너뜀: eventId={} type={} err={}",
                        n, rec.eventId(), rec.eventType(), e.getMessage());
                idempotentEventStore.markProcessed(rec.eventId(), CONSUMER_GROUP, rec.eventType(), "FAILED");
                failures.remove(rec.eventId());
                return true;
            }
            log.warn("[RegistryOutboxPoller] 이벤트 처리 실패({}/{}) — 다음 주기에 재시도: eventId={} err={}",
                    n, maxFailures, rec.eventId(), e.getMessage());
            return false;
        }
    }

    record Watermark(Instant createdAt, String eventId) {}

    Watermark loadWatermark() {
        String raw = redis.opsForValue().get(WATERMARK_KEY);
        if (raw != null) {
            int i = raw.indexOf('|');
            if (i > 0) {
                try {
                    return new Watermark(Instant.parse(raw.substring(0, i)), raw.substring(i + 1));
                } catch (Exception ignore) { /* 아래 초기값으로 */ }
            }
        }
        return new Watermark(Instant.now().minus(Duration.ofHours(initialLookbackHours)), "");
    }

    void saveWatermark(Watermark wm) {
        redis.opsForValue().set(WATERMARK_KEY, wm.createdAt().toString() + "|" + (wm.eventId() != null ? wm.eventId() : ""));
    }
}

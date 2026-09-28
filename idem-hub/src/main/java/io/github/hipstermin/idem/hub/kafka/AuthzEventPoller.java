package io.github.hipstermin.idem.hub.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.event.AuthorizationEvent;
import io.github.hipstermin.idem.hub.infrastructure.AuthzEventRecord;
import io.github.hipstermin.idem.hub.infrastructure.QAuthzClient;
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
 * 1.1: authz 아웃박스 피드({@code idem.authz.assignment.events}) 폴링 → {@link AuthzEventConsumer}.
 * {@link RegistryOutboxPoller} 와 같은 방식: Redis 워터마크 {@code (createdAt, eventId)}, {@code processed_event} 멱등, 독약 이벤트는
 * {@code max-failures} 뒤 FAILED 로 기록하고 건너뛴다. Kafka 유무와 무관하게 돈다 — 피드는 읽기 전용이라 릴레이와 간섭하지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "idem.hub.authz-events.poll.enabled", havingValue = "true", matchIfMissing = true)
public class AuthzEventPoller {

    static final String WATERMARK_KEY = "idem:authz-events:watermark";

    private final QAuthzClient authzClient;
    private final AuthzEventConsumer consumer;
    private final IdempotentEventStore idempotentEventStore;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${idem.hub.authz-events.poll.batch-size:100}")
    private int batchSize = 100;

    @Value("${idem.hub.authz-events.poll.initial-lookback-hours:24}")
    private long initialLookbackHours = 24;

    @Value("${idem.hub.authz-events.poll.max-failures:5}")
    private int maxFailures = 5;

    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AuthzEventPoller(QAuthzClient authzClient, AuthzEventConsumer consumer, IdempotentEventStore idempotentEventStore,
                            StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.authzClient = authzClient;
        this.consumer = consumer;
        this.idempotentEventStore = idempotentEventStore;
        this.redis = redis;
        this.objectMapper = objectMapper;
        log.info("[AuthzEventPoller] 활성 — authz 할당 변경 피드를 폴링해 기관에 통보한다");
    }

    @Scheduled(fixedDelayString = "${idem.hub.authz-events.poll.interval-ms:5000}",
               initialDelayString = "${idem.hub.authz-events.poll.initial-delay-ms:15000}")
    public void poll() {
        if (!running.compareAndSet(false, true)) return;
        try {
            pollOnce();
        } catch (Exception e) {
            log.warn("[AuthzEventPoller] 폴링 실패(다음 주기에 재시도): {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /** @return 이번 주기에 처리(성공·스킵 포함)한 이벤트 수 */
    public int pollOnce() {
        Watermark wm = loadWatermark();
        int processed = 0;
        while (true) {
            List<AuthzEventRecord> batch = authzClient.fetchAssignmentEvents(wm.createdAt(), wm.eventId(), batchSize,
                    "authz-poll-" + Instant.now().toEpochMilli());
            if (batch.isEmpty()) break;
            for (AuthzEventRecord rec : batch) {
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
    private boolean process(AuthzEventRecord rec) {
        try {
            AuthorizationEvent event = objectMapper.treeToValue(rec.payload(), AuthorizationEvent.class);
            if (event == null || event.getEventId() == null) {
                log.warn("[AuthzEventPoller] payload 에 eventId 없음 → 스킵: outboxEventId={}", rec.eventId());
                return true;
            }
            consumer.handle(event);
            failures.remove(rec.eventId());
            return true;
        } catch (Exception e) {
            int n = failures.merge(rec.eventId(), 1, Integer::sum);
            if (n >= maxFailures) {
                log.error("[AuthzEventPoller] 이벤트 {}회 연속 실패 → FAILED 기록 후 건너뜀: eventId={} type={} err={}",
                        n, rec.eventId(), rec.eventType(), e.getMessage());
                idempotentEventStore.markProcessed(rec.eventId(), AuthzEventConsumer.CONSUMER_GROUP, rec.eventType(), "FAILED");
                failures.remove(rec.eventId());
                return true;
            }
            log.warn("[AuthzEventPoller] 이벤트 처리 실패({}/{}) — 다음 주기에 재시도: eventId={} err={}",
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

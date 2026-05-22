package kr.go.smes.batch.health;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.Node;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Kafka 클러스터 연결 헬스 인디케이터 (PR-A4)
 *
 * <p><b>역할</b>:
 * 배치 서비스의 Kafka Producer가 의존하는 브로커 클러스터의 가용성을
 * Spring Boot Actuator {@code /actuator/health}에 노출한다.
 * outbox-relay-batch는 Producer 전용이므로 Kafka 장애 시 릴레이 불가 ⇒
 * Readiness DOWN 처리로 K8s가 트래픽을 차단해야 한다.
 *
 * <p><b>검사 방식</b>:
 * Spring Boot 기본 KafkaHealthIndicator는 spring-kafka {@code KafkaTemplate} bean을
 * 필요로 하지만, 본 구현체는 <b>독립 {@link AdminClient}</b>를 짧은 타임아웃으로 사용한다.
 * {@code describeCluster()}로 컨트롤러 노드를 조회하여 응답이 오면 UP.
 *
 * <p><b>왜 별도 AdminClient인가</b>:
 * <ol>
 *   <li>Producer/Consumer 풀과 분리 — 본 인디케이터의 호출이 데이터 경로 성능에 영향 없음</li>
 *   <li>독자적 타임아웃 설정 — probe 응답 시간을 K8s probe timeout 이내로 보장</li>
 *   <li>request.timeout.ms = 3000 (기본 30000) → probe가 30초 멈춤을 방지</li>
 * </ol>
 *
 * <p><b>출력 예시</b>:
 * <pre>
 * UP:
 *   "kafka": {
 *     "status": "UP",
 *     "details": {
 *       "clusterId": "abc-xyz",
 *       "controllerId": 1,
 *       "nodeCount": 3,
 *       "bootstrap": "kafka:9092"
 *     }
 *   }
 *
 * DOWN:
 *   "kafka": {
 *     "status": "DOWN",
 *     "details": {
 *       "bootstrap": "kafka:9092",
 *       "reason": "TimeoutException: Call(callName=describeCluster, deadlineMs=...)"
 *     }
 *   }
 * </pre>
 *
 * <p><b>캐시 전략</b>:
 * AdminClient 호출 비용을 줄이기 위해 {@code cacheTtlMs} 이내 결과는 재사용.
 * K8s readinessProbe periodSeconds=10s + cacheTtl=5s ⇒ 실질적으로 매 probe마다 신규 호출.
 */
@Slf4j
@Component("kafka")
public class KafkaConnectionHealthIndicator implements HealthIndicator {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.properties.security.protocol:PLAINTEXT}")
    private String securityProtocol;

    /** AdminClient describeCluster() 타임아웃 (ms) */
    @Value("${batch.health.kafka.timeout-ms:3000}")
    private int timeoutMs;

    /** 헬스 결과 캐시 TTL (ms) */
    @Value("${batch.health.kafka.cache-ttl-ms:5000}")
    private long cacheTtlMs;

    private AdminClient adminClient;
    private final AtomicReference<CachedResult> cache = new AtomicReference<>(null);

    @PostConstruct
    void init() {
        Map<String, Object> props = new HashMap<>();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.CLIENT_ID_CONFIG, "outbox-relay-batch-health-admin");
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMs);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMs);
        props.put("security.protocol", securityProtocol);

        try {
            this.adminClient = AdminClient.create(props);
            log.info("[Kafka-Health] AdminClient 초기화: bootstrap={} timeoutMs={}",
                    bootstrapServers, timeoutMs);
        } catch (Exception e) {
            log.error("[Kafka-Health] AdminClient 생성 실패: {}", e.getMessage());
            // Bean 초기화는 실패하지 않고, health() 호출 시 DOWN 반환
            this.adminClient = null;
        }
    }

    @PreDestroy
    void destroy() {
        if (adminClient != null) {
            try {
                adminClient.close(Duration.ofSeconds(2));
            } catch (Exception ignore) { /* shutdown */ }
        }
    }

    @Override
    public Health health() {
        CachedResult cached = cache.get();
        Instant now = Instant.now();

        if (cached != null && cacheTtlMs > 0
                && Duration.between(cached.checkedAt, now).toMillis() < cacheTtlMs) {
            return cached.health;
        }

        return performCheck(now);
    }

    private Health performCheck(Instant now) {
        if (adminClient == null) {
            Health result = Health.down()
                    .withDetail("bootstrap", bootstrapServers)
                    .withDetail("reason", "AdminClient is not initialized")
                    .build();
            cache.set(new CachedResult(result, now));
            return result;
        }

        try {
            DescribeClusterOptions opts = new DescribeClusterOptions()
                    .timeoutMs(timeoutMs);
            DescribeClusterResult cluster = adminClient.describeCluster(opts);

            // .get(timeoutMs) — KafkaFuture 단위 타임아웃 추가 안전망
            String clusterId = cluster.clusterId().get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            Node controller   = cluster.controller().get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            Collection<Node> nodes = cluster.nodes().get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);

            Health.Builder b = Health.up()
                    .withDetail("bootstrap", bootstrapServers)
                    .withDetail("clusterId", clusterId != null ? clusterId : "(unknown)")
                    .withDetail("nodeCount", nodes != null ? nodes.size() : 0);
            if (controller != null) {
                b.withDetail("controllerId", controller.id());
            }
            Health result = b.build();
            cache.set(new CachedResult(result, now));
            return result;

        } catch (Exception e) {
            log.warn("[Kafka-Health] describeCluster 실패: {}", e.getMessage());
            Health result = Health.down()
                    .withDetail("bootstrap", bootstrapServers)
                    .withDetail("reason", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .build();
            cache.set(new CachedResult(result, now));
            return result;
        }
    }

    private record CachedResult(Health health, Instant checkedAt) {}
}

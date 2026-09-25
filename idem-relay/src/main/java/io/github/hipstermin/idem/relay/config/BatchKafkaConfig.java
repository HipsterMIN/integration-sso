package io.github.hipstermin.idem.relay.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * 배치 서비스 Kafka Producer 설정
 *
 * <h2>설계 결정</h2>
 * <ul>
 *   <li>배치 릴레이는 Kafka Consumer가 아닌 Producer만 필요</li>
 *   <li>멱등성 프로듀서 ({@code enable.idempotence=true}) — at-least-once + 중복 방어</li>
 *   <li>acks=all — 모든 ISR 브로커에 기록 확인 후 성공 응답</li>
 *   <li>타입 헤더 제거 ({@code spring.json.add.type.headers=false}) —
 *       Consumer 쪽 타입 불일치 방지, 기존 ido/q-im/q-sign 설정과 동일</li>
 * </ul>
 *
 * <h2>트랜잭션 프로듀서 미사용 이유</h2>
 * Outbox Relay는 이미 DB 상태(PENDING→PUBLISHED)로 멱등성을 보장하므로
 * Kafka 트랜잭션 프로듀서({@code transactional.id})는 불필요 — 오히려 처리량 저하.
 * at-least-once + 멱등 컨슈머 조합으로 충분.
 */
@Configuration
public class BatchKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.properties.security.protocol:PLAINTEXT}")
    private String securityProtocol;

    /**
     * Outbox Relay 전용 KafkaTemplate
     *
     * <p>payload 타입: {@code Map<String, Object>} (idem.hub.outbox) 또는 직렬화된 JSON String.
     * JsonSerializer가 Object를 직렬화하므로 Map/String/도메인 객체 모두 처리 가능.
     */
    @Bean(name = "batchKafkaTemplate")
    public KafkaTemplate<String, Object> batchKafkaTemplate() {
        return new KafkaTemplate<>(batchProducerFactory());
    }

    @Bean(name = "batchProducerFactory")
    public ProducerFactory<String, Object> batchProducerFactory() {
        Map<String, Object> config = new HashMap<>();

        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "outbox-relay-batch-producer");

        // ── at-least-once + 내구성 ──────────────────────────────────────────
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.RETRIES_CONFIG, 5);
        config.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 200);

        // ── 멱등성 프로듀서 ─────────────────────────────────────────────────
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

        // ── 배치 최적화 (릴레이는 소량 건씩 폴링하므로 linger 작게 설정) ────
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        // ── 보안 ────────────────────────────────────────────────────────────
        config.put("security.protocol", securityProtocol);

        // ── 타입 헤더 제거 (기존 ido/q-im/q-sign 설정과 동일) ──────────────
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        return new DefaultKafkaProducerFactory<>(config);
    }
}

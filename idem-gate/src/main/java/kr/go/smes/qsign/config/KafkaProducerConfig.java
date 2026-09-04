package kr.go.smes.qsign.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Q-Sign Kafka Producer 설정
 * 설계서 §9.3 Transactional Outbox 패턴
 *
 * <p>exactly-once 시맨틱 보장:
 * <ul>
 *   <li>enable.idempotence=true</li>
 *   <li>acks=all</li>
 *   <li>transactional.id 기반 트랜잭션 프로듀서</li>
 * </ul>
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.producer.client-id:q-sign-producer}")
    private String clientId;

    /**
     * Transactional Outbox 용 Producer Factory
     * DB 트랜잭션 커밋 후 Outbox Relay 배치가 이 팩토리를 사용해 발행
     */
    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> props = commonProducerProps();
        // Transactional Producer (exactly-once)
        props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "q-sign-tx-");
        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), jsonSerializer());
    }

    /** 일반(non-transactional) KafkaTemplate — 단순 발행용 */
    @Bean("qsignKafkaTemplate")
    public KafkaTemplate<String, Object> kafkaTemplate() {
        KafkaTemplate<String, Object> template =
                new KafkaTemplate<>(producerFactory());
        template.setDefaultTopic("qsign.auth.events");
        template.setObservationEnabled(true);
        return template;
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private Map<String, Object> commonProducerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // exactly-once 필수 조합
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5");
        props.put(ProducerConfig.RETRIES_CONFIG, "3");
        // 배치 최적화
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, "16384");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, "33554432");
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        // 타입 헤더 비활성 (JsonDeserializer 와 독립적 구조)
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return props;
    }

    private JsonSerializer<Object> jsonSerializer() {
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new JsonSerializer<>(om);
    }
}

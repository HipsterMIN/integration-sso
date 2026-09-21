package io.github.hipstermin.idem.registry.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hipstermin.idem.common.messaging.KafkaOptional;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.transaction.KafkaTransactionManager;

/**
 * Q-IM Kafka Producer 설정
 * 설계서 §10.5.2 Transactional Outbox 패턴
 *
 * <p>DB 트랜잭션과 Kafka 발행을 원자적으로 처리하기 위해
 * KafkaTransactionManager 를 함께 구성한다.
 * Outbox Relay 배치가 트랜잭션 프로듀서를 통해 exactly-once 발행.
 */
@Configuration
@ConditionalOnProperty(name = KafkaOptional.PROPERTY, havingValue = "true") // D1-b: 꺼지면 idem-common 의 DisabledKafkaTemplate 이 대신 주입된다 (KafkaTransactionManager 도 없음)
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.producer.client-id:q-im-producer}")
    private String clientId;

    @Bean
    public ProducerFactory<String, Object> qimProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // exactly-once
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5");
        props.put(ProducerConfig.RETRIES_CONFIG, "3");
        props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "q-im-tx-");
        // 배치 최적화
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, "16384");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        DefaultKafkaProducerFactory<String, Object> factory =
                new DefaultKafkaProducerFactory<>(props, new StringSerializer(), jsonSerializer());
        factory.setTransactionIdPrefix("q-im-tx-");
        return factory;
    }

    /**
     * Kafka 트랜잭션 매니저
     * OutboxRelay 에서 @Transactional(transactionManager="kafkaTxManager") 로 참조
     */
    @Bean
    public KafkaTransactionManager<String, Object> kafkaTxManager(
            ProducerFactory<String, Object> qimProducerFactory) {
        return new KafkaTransactionManager<>(qimProducerFactory);
    }

    @Bean("qimKafkaTemplate")
    public KafkaTemplate<String, Object> qimKafkaTemplate(
            ProducerFactory<String, Object> qimProducerFactory) {
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(qimProducerFactory);
        template.setObservationEnabled(true);
        return template;
    }

    private JsonSerializer<Object> jsonSerializer() {
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new JsonSerializer<>(om);
    }
}

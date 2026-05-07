package kr.go.smes.qim.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Q-IM Kafka Consumer 설정
 * 설계서 §10.5.2 / §11.5.5 Ordered Consumer / §16.3 멱등 컨슈머
 *
 * <p>수신 대상:
 *   - (현재) 외부 이벤트 수신 없음; Q-IM은 이벤트 발행자
 *   - 향후 관리자 커맨드 이벤트 소비 시 이 Bean 활용
 *
 * <p>주요 설정:
 *   - ack-mode: MANUAL_IMMEDIATE (처리 완료 후 명시적 ack)
 *   - isolation.level: read_committed (Transactional Producer 대응)
 *   - enable.auto.commit: false (중복 처리 방지)
 *
 * [DB] NHN Cloud RDS for MariaDB (PoC: Docker MariaDB 11.x)
 */
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:q-im-consumer}")
    private String groupId;

    @Bean
    public ConsumerFactory<String, Object> qimConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "50");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        // Transactional Producer 격리 수준 — read_committed
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        // 신뢰 패키지 설정
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "kr.go.smes.*");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, "false");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "java.util.Map");

        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Kafka 리스너 컨테이너 팩토리
     * — MANUAL_IMMEDIATE: 리스너가 명시적으로 ack 호출
     * — concurrency: 3 (설계서 §10.5.2)
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(qimConsumerFactory());
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}

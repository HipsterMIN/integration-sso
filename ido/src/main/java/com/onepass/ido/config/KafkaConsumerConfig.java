package com.onepass.ido.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.onepass.common.event.AuthEvent;
import com.onepass.common.event.UserEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * IdO Kafka Consumer / Producer 설정
 * 설계서 §11.5 Q-IM 이벤트 소비 / §9.3 Q-Sign 이벤트 소비
 *
 * <p>컨슈머 그룹:
 * <ul>
 *   <li>ido-qim-consumer  : qim.user.events 구독 (Q-IM 캐시 갱신)</li>
 *   <li>ido-qsign-consumer: qsign.auth.events 구독 (인증 결과 연계)</li>
 * </ul>
 */
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${ido.kafka.consumer-group-qim:ido-qim-consumer}")
    private String qimConsumerGroup;

    @Value("${ido.kafka.consumer-group-qsign:ido-qsign-consumer}")
    private String qsignConsumerGroup;

    // ── Q-IM 이벤트 컨슈머 팩토리 ────────────────────────────────────────

    @Bean("qimConsumerFactory")
    public ConsumerFactory<String, UserEvent> qimConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(
                consumerProps(qimConsumerGroup),
                new StringDeserializer(),
                errorHandlingDeserializer(UserEvent.class)
        );
    }

    /**
     * §11.5.5 Ordered Consumer 패턴
     * - concurrency=3 (파티션 수의 약수)
     * - MANUAL_IMMEDIATE: 처리 완료 후 수동 커밋 (멱등 처리 보장)
     * - 지수 백오프 재시도 후 DLQ 전송
     */
    @Bean("qimListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, UserEvent>
    qimListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, UserEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(qimConsumerFactory());
        factory.setConcurrency(3);
        factory.getContainerProperties()
               .setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(defaultErrorHandler());
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    // ── Q-Sign 인증 이벤트 컨슈머 팩토리 ─────────────────────────────────

    @Bean("qsignConsumerFactory")
    public ConsumerFactory<String, AuthEvent> qsignConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(
                consumerProps(qsignConsumerGroup),
                new StringDeserializer(),
                errorHandlingDeserializer(AuthEvent.class)
        );
    }

    @Bean("qsignListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, AuthEvent>
    qsignListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, AuthEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(qsignConsumerFactory());
        factory.setConcurrency(3);
        factory.getContainerProperties()
               .setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(defaultErrorHandler());
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    // ── IdO Outbox 용 Producer ────────────────────────────────────────────

    @Bean("idoProducerFactory")
    public ProducerFactory<String, Object> idoProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "ido-producer");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5");
        props.put(ProducerConfig.RETRIES_CONFIG, "3");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, "16384");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        DefaultKafkaProducerFactory<String, Object> factory =
                new DefaultKafkaProducerFactory<>(props,
                        new StringSerializer(), new JsonSerializer<>(om));
        factory.setTransactionIdPrefix("ido-tx-");
        return factory;
    }

    @Bean("idoKafkaTemplate")
    public KafkaTemplate<String, Object> idoKafkaTemplate() {
        KafkaTemplate<String, Object> template =
                new KafkaTemplate<>(idoProducerFactory());
        template.setObservationEnabled(true);
        return template;
    }

    // ── Error Handler ────────────────────────────────────────────────────

    /**
     * 지수 백오프 재시도 (최대 3회) → DLQ 전송
     * 재시도 간격: 1s → 2s → 4s
     */
    private DefaultErrorHandler defaultErrorHandler() {
        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000L);
        return new DefaultErrorHandler(backOff);
    }

    // ── Shared ────────────────────────────────────────────────────────────

    private Map<String, Object> consumerProps(String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ErrorHandlingDeserializer.class);
        return props;
    }

    private <T> ErrorHandlingDeserializer<T> errorHandlingDeserializer(Class<T> targetType) {
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        JsonDeserializer<T> delegate = new JsonDeserializer<>(targetType, om);
        delegate.addTrustedPackages("com.onepass.*");
        delegate.setUseTypeMapperForKey(false);
        return new ErrorHandlingDeserializer<>(delegate);
    }
}

package com.onepass.fe.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.onepass.common.event.SessionAdvisoryEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Onepass-FE Kafka Consumer 설정
 * 설계서 §12.5 세션 Advisory 수신 → FE 세션 처리
 *
 * <p>구독 토픽: platform.session.advisory
 * <p>컨슈머 그룹: onepass-fe-consumer (FE 독립 그룹 — Agency와 별도 오프셋)
 */
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${onepass.kafka.consumer-group:onepass-fe-consumer}")
    private String consumerGroup;

    @Bean("feAdvisoryConsumerFactory")
    public ConsumerFactory<String, SessionAdvisoryEvent> feAdvisoryConsumerFactory() {
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        JsonDeserializer<SessionAdvisoryEvent> delegate =
                new JsonDeserializer<>(SessionAdvisoryEvent.class, om);
        delegate.addTrustedPackages("com.onepass.*");
        delegate.setUseTypeMapperForKey(false);

        return new DefaultKafkaConsumerFactory<>(
                consumerProps(),
                new StringDeserializer(),
                new ErrorHandlingDeserializer<>(delegate)
        );
    }

    /**
     * Advisory 수신 컨테이너 팩토리
     * - concurrency=2: FE 세션 처리 부하 고려
     * - MANUAL_IMMEDIATE: 세션 무효화 완료 후 ACK
     */
    @Bean("feAdvisoryListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, SessionAdvisoryEvent>
    feAdvisoryListenerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, SessionAdvisoryEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(feAdvisoryConsumerFactory());
        factory.setConcurrency(2);
        factory.getContainerProperties()
               .setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(backOffErrorHandler());
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private Map<String, Object> consumerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ErrorHandlingDeserializer.class);
        return props;
    }

    private DefaultErrorHandler backOffErrorHandler() {
        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(8_000L);
        return new DefaultErrorHandler(backOff);
    }
}

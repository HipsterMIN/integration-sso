package com.onepass.agency.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.onepass.common.event.HandoffEvent;
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
 * Agency-Stub Kafka Consumer 설정
 *
 * <p>구독 토픽:
 * <ul>
 *   <li>ido.handoff.events        — Handoff 이벤트 수신 (REVOKED 감지)</li>
 *   <li>platform.session.advisory — 세션 Advisory 수신 → 기관 세션 처리</li>
 * </ul>
 *
 * <p>컨슈머 그룹: agency-stub-consumer (FE 와 완전히 독립된 오프셋)
 */
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${agency-stub.kafka.consumer-group:agency-stub-consumer}")
    private String consumerGroup;

    // ── Handoff 이벤트 컨슈머 ─────────────────────────────────────────────

    @Bean("agencyHandoffConsumerFactory")
    public ConsumerFactory<String, HandoffEvent> agencyHandoffConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(
                consumerProps(consumerGroup + "-handoff"),
                new StringDeserializer(),
                errorHandlingDeserializer(HandoffEvent.class)
        );
    }

    /**
     * Handoff 이벤트 리스너 팩토리
     * - HANDOFF_REVOKED 수신 시 해당 ticketId 로 발급된 기관 세션 즉시 무효화
     */
    @Bean("agencyHandoffListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, HandoffEvent>
    agencyHandoffListenerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, HandoffEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(agencyHandoffConsumerFactory());
        factory.setConcurrency(2);
        factory.getContainerProperties()
               .setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(backOffErrorHandler());
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    // ── Session Advisory 컨슈머 ───────────────────────────────────────────

    @Bean("agencyAdvisoryConsumerFactory")
    public ConsumerFactory<String, SessionAdvisoryEvent> agencyAdvisoryConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(
                consumerProps(consumerGroup + "-advisory"),
                new StringDeserializer(),
                errorHandlingDeserializer(SessionAdvisoryEvent.class)
        );
    }

    /**
     * Session Advisory 리스너 팩토리
     * - §14.9 세션 필수 보안 이벤트 처리
     * - MANDATORY_SECURITY_TERMINATE → 기관 세션 즉시 무효화
     */
    @Bean("agencyAdvisoryListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, SessionAdvisoryEvent>
    agencyAdvisoryListenerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, SessionAdvisoryEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(agencyAdvisoryConsumerFactory());
        factory.setConcurrency(2);
        factory.getContainerProperties()
               .setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(backOffErrorHandler());
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    // ── Internal ──────────────────────────────────────────────────────────

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

    private DefaultErrorHandler backOffErrorHandler() {
        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(8_000L);
        return new DefaultErrorHandler(backOff);
    }
}

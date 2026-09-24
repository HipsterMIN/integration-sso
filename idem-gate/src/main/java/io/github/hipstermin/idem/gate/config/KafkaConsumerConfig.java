package io.github.hipstermin.idem.gate.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hipstermin.idem.common.event.UserEvent;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

/**
 * Q-Sign Kafka Consumer 설정
 * 설계서 §9.3 Q-IM 사용자 이벤트 수신 / §16.3 멱등 컨슈머 / §19.4 DLQ 전략
 *
 * <p>GAP-QS-03: Q-Sign도 qim.user.events 소비 시 DLQ 처리 필요 (설계 §19.4).
 *
 * <p>컨슈머 그룹:
 * <ul>
 *   <li>q-sign-qim-consumer: qim.user.events 구독 (사용자 상태 연동)</li>
 * </ul>
 *
 * <p>오류 처리 전략:
 * <ul>
 *   <li>지수 백오프 재시도 최대 3회 (1s → 2s → 4s)</li>
 *   <li>재시도 초과 시 "{원본토픽}.dlt" DLQ 토픽 전송</li>
 *   <li>DLQ 헤더: x-original-topic / x-failure-reason / correlationId / eventId</li>
 * </ul>
 */
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${qsign.kafka.consumer-group-qim:q-sign-qim-consumer}")
    private String qimConsumerGroup;

    // ── Q-IM 사용자 이벤트 컨슈머 팩토리 ─────────────────────────────────────

    @Bean("qsignQimConsumerFactory")
    public ConsumerFactory<String, UserEvent> qsignQimConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(
                consumerProps(qimConsumerGroup),
                new StringDeserializer(),
                errorHandlingDeserializer(UserEvent.class)
        );
    }

    /**
     * Q-IM 사용자 이벤트 리스너 컨테이너 팩토리
     * - concurrency=3: qim.user.events 파티션 수 대응
     * - MANUAL_IMMEDIATE: 처리 완료 후 수동 커밋 (멱등 처리 보장)
     * - 지수 백오프 재시도 후 DLQ 전송 (설계서 §19.4)
     */
    @Bean("qsignQimListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, UserEvent>
    qsignQimListenerContainerFactory(
            org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate /* D1-b: Kafka 꺼지면 idem-common 의 DisabledKafkaTemplate — 한정자 없이 받는다 */) {

        ConcurrentKafkaListenerContainerFactory<String, UserEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(qsignQimConsumerFactory());
        factory.setConcurrency(3);
        factory.getContainerProperties()
               .setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(defaultErrorHandler(kafkaTemplate));
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    // ── Error Handler ─────────────────────────────────────────────────────────

    /**
     * 지수 백오프 재시도 (최대 3회) → DLQ 전송
     * 재시도 간격: 1s → 2s → 4s
     *
     * <p>설계서 §19.4 DLQ 전략:
     * 최대 재시도 초과 시 "{원본토픽}.dlt" 토픽으로 전송.
     * DLQ 레코드 헤더에 originalTopic / failureReason / correlationId / eventId 보존.
     */
    private DefaultErrorHandler defaultErrorHandler(
            org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate) {

        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000L);

        // DLQ: 원본 토픽명 + ".dlt" 접미사 토픽으로 전송
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".dlt", -1)
        );

        // DLQ 헤더에 장애 컨텍스트 정보 보존 (설계서 §24.4.1)
        recoverer.setHeadersFunction((consumerRecord, ex) -> {
            org.apache.kafka.common.header.internals.RecordHeaders headers =
                    new org.apache.kafka.common.header.internals.RecordHeaders();

            // originalTopic 보존
            headers.add("x-original-topic",
                    consumerRecord.topic().getBytes(StandardCharsets.UTF_8));

            // failureReason 보존
            String reason = ex.getCause() != null
                    ? ex.getCause().getClass().getSimpleName()
                    : ex.getClass().getSimpleName();
            headers.add("x-failure-reason", reason.getBytes(StandardCharsets.UTF_8));

            // 원본 헤더 전달 (correlationId / eventId 등 보존)
            consumerRecord.headers().forEach(h -> {
                if (!"x-original-topic".equals(h.key())
                        && !"x-failure-reason".equals(h.key())) {
                    headers.add(h);
                }
            });
            return headers;
        });

        return new DefaultErrorHandler(recoverer, backOff);
    }

    // ── Shared ────────────────────────────────────────────────────────────────

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
        delegate.addTrustedPackages("io.github.hipstermin.idem.*");
        delegate.setUseTypeMapperForKey(false);
        return new ErrorHandlingDeserializer<>(delegate);
    }
}

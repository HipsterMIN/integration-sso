package kr.go.smes.qim.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Q-IM Kafka Consumer 설정
 * 설계서 §10.5.2 / §11.5.5 Ordered Consumer / §16.3 멱등 컨슈머 / §24.4.1 DLQ 전략
 *
 * <p>수신 대상:
 *   - (현재) 외부 이벤트 수신 없음; Q-IM은 이벤트 발행자
 *   - 향후 관리자 커맨드 이벤트 소비 시 이 Bean 활용
 *
 * <p>주요 설정:
 *   - ack-mode: MANUAL_IMMEDIATE (처리 완료 후 명시적 ack)
 *   - isolation.level: read_committed (Transactional Producer 대응)
 *   - enable.auto.commit: false (중복 처리 방지)
 *   - DLQ: 지수 백오프 3회 재시도 후 "{원본토픽}.dlt" 전송 (GAP-IDO-09 준용)
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
     * — DLQ: 지수 백오프 3회 후 {원본토픽}.dlt 전송 (설계서 §24.4.1)
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            @Qualifier("qimKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(qimConsumerFactory());
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setObservationEnabled(true);
        factory.setCommonErrorHandler(qimErrorHandler(kafkaTemplate));
        return factory;
    }

    /**
     * Q-IM DLQ 오류 핸들러 (GAP-IDO-09 준용, 설계서 §24.4.1)
     *
     * <p>지수 백오프 재시도 (최대 3회: 1s → 2s → 4s) 후 DLQ 전송.
     * DLQ 토픽: "{원본토픽}.dlt"
     *
     * <p>DLQ 헤더:
     * <ul>
     *   <li>x-original-topic — 원본 토픽명</li>
     *   <li>x-failure-reason — 예외 클래스 단순명</li>
     *   <li>x-failed-at     — 실패 epoch ms</li>
     *   <li>원본 헤더 전파 (x-correlation-id, x-event-id 포함)</li>
     * </ul>
     */
    @Bean
    public DefaultErrorHandler qimErrorHandler(
            @Qualifier("qimKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate) {

        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000L);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".dlt", -1)
        );

        recoverer.setHeadersFunction((consumerRecord, ex) -> {
            var headers = new org.apache.kafka.common.header.internals.RecordHeaders();

            // ① 원본 토픽명
            headers.add("x-original-topic",
                    consumerRecord.topic().getBytes(StandardCharsets.UTF_8));

            // ② 실패 원인 (예외 클래스 단순명 — 스택 노출 방지)
            String reason = ex.getCause() != null
                    ? ex.getCause().getClass().getSimpleName()
                    : ex.getClass().getSimpleName();
            headers.add("x-failure-reason", reason.getBytes(StandardCharsets.UTF_8));

            // ③ 실패 시각 (epoch ms)
            headers.add("x-failed-at",
                    String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));

            // ④ 원본 레코드 헤더 전파 (x-correlation-id, x-event-id 등)
            java.util.Set<String> skipKeys = java.util.Set.of(
                    "x-original-topic", "x-failure-reason", "x-failed-at");
            consumerRecord.headers().forEach(h -> {
                if (!skipKeys.contains(h.key())) {
                    headers.add(h);
                }
            });

            return headers;
        });

        return new DefaultErrorHandler(recoverer, backOff);
    }
}

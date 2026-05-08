package kr.go.smes.ido.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import kr.go.smes.common.event.AuthEvent;
import kr.go.smes.common.event.HandoffEvent;
import kr.go.smes.common.event.SessionAdvisoryEvent;
import kr.go.smes.common.event.UserEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * IdO Kafka Consumer / Producer 설정
 * 설계서 §11.5 Q-IM 이벤트 소비 / §9.3 Q-Sign 이벤트 소비 / §16.3 Handoff 이벤트
 *
 * <p><b>컨슈머 그룹 목록</b>:
 * <ul>
 *   <li>{@code ido-qim-consumer}         — qim.user.events 구독 (Q-IM 캐시 갱신)</li>
 *   <li>{@code ido-qsign-consumer}        — qsign.auth.events 구독 (인증 결과 Pre-warming)</li>
 *   <li>{@code ido-handoff-consumer}      — ido.handoff.events 구독 (기관 webhook 트리거)</li>
 *   <li>{@code ido-fe-advisory-consumer}  — platform.session.advisory 구독 (FE 세션 처리)</li>
 *   <li>{@code ido-qim-sp-member-consumer}— qim.sp.member.events 구독 (SP 회원 이벤트)</li>
 * </ul>
 *
 * <p><b>60,000명 부하 대응 Concurrency 설계</b>:
 * <pre>
 * qsign.auth.events   → concurrency=6 (파티션 12개 기준 절반, pre-warming 병렬도)
 * ido.handoff.events  → concurrency=6 (파티션 12개 기준 절반, webhook 큐잉 병렬도)
 * qim.user.events     → concurrency=3 (캐시 갱신, 순서 중요)
 * platform.advisory   → concurrency=3 (FE 세션 처리)
 * qim.sp.member.events→ concurrency=2 (SP 회원 이벤트, 낮은 빈도)
 * </pre>
 */
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${ido.kafka.consumer-group-qim:ido-qim-consumer}")
    private String qimConsumerGroup;

    @Value("${ido.kafka.consumer-group-qsign:ido-qsign-consumer}")
    private String qsignConsumerGroup;

    @Value("${ido.kafka.consumer-group-handoff:ido-handoff-consumer}")
    private String handoffConsumerGroup;

    @Value("${ido.kafka.consumer-group-fe-advisory:ido-fe-advisory-consumer}")
    private String feAdvisoryConsumerGroup;

    @Value("${ido.kafka.consumer-group-qim-sp-member:ido-qim-sp-member-consumer}")
    private String qimSpMemberConsumerGroup;

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
     * - MANUAL_IMMEDIATE: 처리 완료 후 수동 커밋
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

    // ── Q-Sign 인증 이벤트 컨슈머 팩토리 (60k 급증 핵심) ─────────────────

    @Bean("qsignConsumerFactory")
    public ConsumerFactory<String, AuthEvent> qsignConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(
                consumerProps(qsignConsumerGroup),
                new StringDeserializer(),
                errorHandlingDeserializer(AuthEvent.class)
        );
    }

    /**
     * 60,000명 급증 대응 — concurrency=6
     *
     * <p>qsign.auth.events 파티션 12개 기준 concurrency=6 설정.
     * AUTH_COMPLETED 수신 즉시 Redis Pre-warming 수행.
     * 6개 스레드 × 폴링 주기 = 초당 수백 건 Pre-warming 처리 가능.
     */
    @Bean("qsignListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, AuthEvent>
    qsignListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, AuthEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(qsignConsumerFactory());
        factory.setConcurrency(6);  // 60k 대응: 파티션 12개의 절반
        factory.getContainerProperties()
               .setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(defaultErrorHandler());
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    // ── Handoff 이벤트 컨슈머 팩토리 (기관 webhook 트리거) ───────────────

    /**
     * HandoffEvent는 String으로 수신 후 HandoffEventConsumer 내에서 역직렬화.
     * HandoffEvent가 abstract DomainEvent를 상속하므로 타입 매핑 이슈 방지.
     */
    @Bean("handoffConsumerFactory")
    public ConsumerFactory<String, String> handoffConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(
                consumerProps(handoffConsumerGroup),
                new StringDeserializer(),
                new StringDeserializer()
        );
    }

    /**
     * Handoff 이벤트 리스너 팩토리
     *
     * <p><b>60,000명 급증 대응 — concurrency=6</b>:
     * ido.handoff.events 파티션 12개 기준 concurrency=6.
     * HANDOFF_ISSUED 수신 → webhook Outbox 적재 → 병렬 처리.
     *
     * <p>Handoff Ticket TTL=60s이므로 처리 지연이 생기면 안 됨.
     * 높은 concurrency로 즉시 처리 보장.
     */
    @Bean("handoffListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String>
    handoffListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(handoffConsumerFactory());
        factory.setConcurrency(6);  // 60k 대응: 파티션 12개의 절반
        factory.getContainerProperties()
               .setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(defaultErrorHandler());
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    // ── Q-IM SP 회원 이벤트 컨슈머 팩토리 (qim.sp.member.events) ─────────

    @Bean("qimSpMemberConsumerFactory")
    public ConsumerFactory<String, String> qimSpMemberConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(
                consumerProps(qimSpMemberConsumerGroup),
                new StringDeserializer(),
                new StringDeserializer()
        );
    }

    @Bean("qimSpMemberListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String>
    qimSpMemberListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(qimSpMemberConsumerFactory());
        factory.setConcurrency(2);
        factory.getContainerProperties()
               .setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(defaultErrorHandler());
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    // ── FE Advisory 컨슈머 팩토리 (platform.session.advisory) ────────────

    @Bean("feAdvisoryConsumerFactory")
    public ConsumerFactory<String, SessionAdvisoryEvent> feAdvisoryConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(
                consumerProps(feAdvisoryConsumerGroup),
                new StringDeserializer(),
                errorHandlingDeserializer(SessionAdvisoryEvent.class)
        );
    }

    @Bean("feAdvisoryListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, SessionAdvisoryEvent>
    feAdvisoryListenerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, SessionAdvisoryEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(feAdvisoryConsumerFactory());
        factory.setConcurrency(3);
        factory.getContainerProperties()
               .setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(defaultErrorHandler());
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    // ── Producer (IdO Outbox용) ───────────────────────────────────────────

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

    // ── Error Handler (공통) ──────────────────────────────────────────────

    /**
     * 지수 백오프 재시도 (최대 3회, 1s→2s→4s) → DLQ 전송
     *
     * <p>DLQ 토픽: "{원본토픽}.dlt"
     * DLQ 헤더: x-original-topic, x-failure-reason, x-failure-count
     * (설계서 §24.4.1 DLQ 전략)
     */
    private DefaultErrorHandler defaultErrorHandler() {
        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000L);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                idoKafkaTemplate(),
                (record, ex) -> new TopicPartition(record.topic() + ".dlt", -1)
        );

        recoverer.setHeadersFunction((consumerRecord, ex) -> {
            var headers = new org.apache.kafka.common.header.internals.RecordHeaders();
            headers.add("x-original-topic",
                    consumerRecord.topic().getBytes(StandardCharsets.UTF_8));
            String reason = ex.getCause() != null
                    ? ex.getCause().getClass().getSimpleName()
                    : ex.getClass().getSimpleName();
            headers.add("x-failure-reason",
                    reason.getBytes(StandardCharsets.UTF_8));
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

    // ── 공통 컨슈머 프로퍼티 ─────────────────────────────────────────────

    /**
     * 컨슈머 공통 프로퍼티
     *
     * <p>max-poll-records=50: 60k 급증 시 배치 처리로 처리량 향상.
     * isolation-level=read_committed: 트랜잭셔널 Outbox 발행 레코드만 소비.
     * auto-commit 비활성: MANUAL_IMMEDIATE ACK로 정확한 처리 보장.
     */
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
        delegate.addTrustedPackages("kr.go.smes.*");
        delegate.setUseTypeMapperForKey(false);
        return new ErrorHandlingDeserializer<>(delegate);
    }
}

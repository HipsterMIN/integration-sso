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
 *   <li>{@code ido-qim-member-consumer}  — qim.user.events 구독 (QIM-OUTBOX-SPEC-001 회원 등록/전환/탈퇴)</li>
 * </ul>
 *
 * <p><b>60,000명 부하 대응 Concurrency 설계</b>:
 * <pre>
 * qsign.auth.events   → concurrency=6 (파티션 12개 기준 절반, pre-warming 병렬도)
 * ido.handoff.events  → concurrency=6 (파티션 12개 기준 절반, webhook 큐잉 병렬도)
 * qim.user.events     → concurrency=3 (캐시 갱신, 순서 중요) — ido-qim-consumer
 * qim.user.events     → concurrency=2 (등록/전환/탈퇴 처리) — ido-qim-member-consumer
 * platform.advisory   → concurrency=3 (FE 세션 처리)
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

    // QIM-OUTBOX-SPEC-001: qim.user.events 구독 (BIZ/PERSONAL REGISTERED/CONVERTED/WITHDRAWN)
    @Value("${ido.kafka.consumer-group-qim-member:ido-qim-member-consumer}")
    private String qimMemberConsumerGroup;

    // 기존 qim.sp.member.events 컨슈머 그룹 (폐기 예정 토픽 마이그레이션 완료 시 제거)
    @Deprecated(since = "QIM-OUTBOX-SPEC-001", forRemoval = true)
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

    // ── Q-IM 회원 이벤트 컨슈머 팩토리 (qim.user.events / QIM-OUTBOX-SPEC-001) ──
    //    QimSpMemberEventConsumer 가 BIZ/PERSONAL_MEMBER_CONVERTED/REGISTERED/WITHDRAWN 처리
    //    payload 는 QimSpReceiverService 가 Map<String,Object> 구조로 INSERT했으므로
    //    String → 컨슈머 내에서 ObjectMapper 역직렬화 (JsonDeserializer 타입 불일치 방지)

    @Bean("qimMemberConsumerFactory")
    public ConsumerFactory<String, String> qimMemberConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(
                consumerProps(qimMemberConsumerGroup),
                new StringDeserializer(),
                new StringDeserializer()
        );
    }

    /**
     * QIM-OUTBOX-SPEC-001: qim.user.events 회원 등록/전환/탈퇴 이벤트 컨슈머 팩토리
     *
     * <p>concurrency=2: qim.user.events 파티션 6개 기준 적정 병렬도.
     * 회원 이벤트는 실시간성 요구가 낮고 순서 중요도가 높으므로 낮은 concurrency 설정.
     */
    @Bean("qimMemberListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String>
    qimMemberListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(qimMemberConsumerFactory());
        factory.setConcurrency(2);
        factory.getContainerProperties()
               .setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(defaultErrorHandler());
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    // ── Q-IM SP 회원 이벤트 컨슈머 팩토리 (qim.sp.member.events) ─────────
    // @Deprecated QIM-OUTBOX-SPEC-001: qim.sp.member.events → qim.user.events 전환 완료 후 제거

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
     * GAP-IDO-09: DLQ DeadLetterPublishingRecoverer 완전 구현
     *
     * <p>지수 백오프 재시도 (최대 3회: 1s → 2s → 4s) 후 DLQ 전송.
     *
     * <p><b>DLQ 토픽</b>: "{원본토픽}.dlt" (파티션 -1 → Kafka 기본 파티션 결정)
     *
     * <p><b>보존 헤더 6종</b> (설계서 §24.4.1):
     * <ol>
     *   <li>{@code x-original-topic}   — 원본 토픽명</li>
     *   <li>{@code x-failure-reason}   — 예외 클래스 단순명</li>
     *   <li>{@code x-failure-count}    — 재시도 횟수 (1-indexed)</li>
     *   <li>{@code x-correlation-id}   — 흐름 추적 ID (원본 레코드 헤더에서 복사)</li>
     *   <li>{@code x-event-id}         — 이벤트 ID (원본 레코드 헤더에서 복사)</li>
     *   <li>{@code x-failed-at}        — 실패 epoch ms (ISO-8601 문자열)</li>
     * </ol>
     *
     * <p>{@code @Bean} 등록으로 단일 인스턴스를 모든 리스너 컨테이너에서 공유.
     * (설계서 §24.4.1 DLQ 전략)
     */
    @Bean
    public DefaultErrorHandler defaultErrorHandler() {
        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000L);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                idoKafkaTemplate(),
                // 파티션 -1: Kafka 기본 파티셔너에게 위임 (DLT 파티션 수에 상관없이 동작)
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
            headers.add("x-failure-reason",
                    reason.getBytes(StandardCharsets.UTF_8));

            // ③ 재시도 횟수: 원본 레코드에 x-failure-count 가 있으면 +1, 없으면 1
            byte[] prevCountBytes = consumerRecord.headers().lastHeader("x-failure-count") != null
                    ? consumerRecord.headers().lastHeader("x-failure-count").value()
                    : null;
            int failureCount = (prevCountBytes != null)
                    ? Integer.parseInt(new String(prevCountBytes, StandardCharsets.UTF_8)) + 1
                    : 1;
            headers.add("x-failure-count",
                    String.valueOf(failureCount).getBytes(StandardCharsets.UTF_8));

            // ④ 실패 시각 (epoch ms)
            headers.add("x-failed-at",
                    String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));

            // ⑤ 원본 레코드 헤더 전파 (x-correlation-id, x-event-id 포함)
            //    단, 이미 위에서 추가한 헤더는 덮어쓰지 않기 위해 제외
            java.util.Set<String> skipKeys = java.util.Set.of(
                    "x-original-topic", "x-failure-reason",
                    "x-failure-count",  "x-failed-at"
            );
            consumerRecord.headers().forEach(h -> {
                if (!skipKeys.contains(h.key())) {
                    headers.add(h);
                }
            });

            return headers;
        });

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // 재시도 불가 예외: 역직렬화 오류는 즉시 DLQ로 (재시도 의미 없음)
        handler.addNotRetryableExceptions(
                org.springframework.kafka.support.serializer.DeserializationException.class
        );
        return handler;
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

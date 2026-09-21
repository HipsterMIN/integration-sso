package io.github.hipstermin.idem.common.messaging;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.TopicPartitionOffset;
import org.springframework.messaging.Message;

/**
 * Kafka 가 꺼진 배포에서 {@link KafkaTemplate} 자리를 채우는 무동작 구현.
 *
 * <p>프로듀서를 만들지 않으므로 브로커 접속·메타데이터 대기({@code max.block.ms})가 전혀 없다.
 * 모든 {@code send*} 는 {@link KafkaDisabledException} 으로 실패한 Future 를 돌려준다 — 기존 호출부의
 * {@code whenComplete} 폴백(아웃박스 저장 등)이 그대로 동작하고, 예외를 던지지 않으므로 요청 흐름은 끊기지 않는다.
 * 트랜잭션·수신 API 는 지원하지 않는다(즉시 예외).
 */
public class DisabledKafkaTemplate<K, V> extends KafkaTemplate<K, V> {

    public DisabledKafkaTemplate() {
        super(new DefaultKafkaProducerFactory<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-disabled:9092")));
    }

    private CompletableFuture<SendResult<K, V>> disabled() {
        return CompletableFuture.failedFuture(new KafkaDisabledException());
    }

    @Override public CompletableFuture<SendResult<K, V>> sendDefault(V data) { return disabled(); }
    @Override public CompletableFuture<SendResult<K, V>> sendDefault(K key, V data) { return disabled(); }
    @Override public CompletableFuture<SendResult<K, V>> sendDefault(Integer partition, K key, V data) { return disabled(); }
    @Override public CompletableFuture<SendResult<K, V>> sendDefault(Integer partition, Long timestamp, K key, V data) { return disabled(); }
    @Override public CompletableFuture<SendResult<K, V>> send(String topic, V data) { return disabled(); }
    @Override public CompletableFuture<SendResult<K, V>> send(String topic, K key, V data) { return disabled(); }
    @Override public CompletableFuture<SendResult<K, V>> send(String topic, Integer partition, K key, V data) { return disabled(); }
    @Override public CompletableFuture<SendResult<K, V>> send(String topic, Integer partition, Long timestamp, K key, V data) { return disabled(); }
    @Override public CompletableFuture<SendResult<K, V>> send(ProducerRecord<K, V> record) { return disabled(); }
    @Override public CompletableFuture<SendResult<K, V>> send(Message<?> message) { return disabled(); }

    @Override public List<PartitionInfo> partitionsFor(String topic) { return List.of(); }
    @Override public Map<MetricName, ? extends Metric> metrics() { return Map.of(); }
    @Override public <T> T execute(ProducerCallback<K, V, T> callback) { throw new KafkaDisabledException(); }
    @Override public <T> T executeInTransaction(OperationsCallback<K, V, T> callback) { throw new KafkaDisabledException(); }
    @Override public void flush() { /* 보낼 것이 없다 */ }
    @Override public void sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata> offsets, ConsumerGroupMetadata groupMetadata) { throw new KafkaDisabledException(); }
    @Override public ConsumerRecord<K, V> receive(String topic, int partition, long offset, Duration pollTimeout) { throw new KafkaDisabledException(); }
    @Override public ConsumerRecords<K, V> receive(Collection<TopicPartitionOffset> requested, Duration pollTimeout) { throw new KafkaDisabledException(); }
    @Override public boolean isTransactional() { return false; }
    @Override public boolean inTransaction() { return false; }
    @Override public void afterSingletonsInstantiated() { /* 관측 레지스트리 조회 불필요 */ }
}

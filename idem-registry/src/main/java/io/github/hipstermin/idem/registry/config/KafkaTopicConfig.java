package io.github.hipstermin.idem.registry.config;

import io.github.hipstermin.idem.common.messaging.KafkaOptional;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Q-IM Kafka 토픽 설정
 * 설계서 §10.5.2 Transactional Outbox / §11.5.6 Compacted Snapshot
 *
 * <pre>
 * idem.registry.user.events    : 사용자 변경 이벤트 (compact) — partitionKey=qimUserId
 * idem.registry.user.snapshot  : 사용자 전체 상태 스냅샷 (compact) — IdO 초기 로딩용
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = KafkaOptional.PROPERTY, havingValue = "true") // D1-b: Kafka 선택 의존
public class KafkaTopicConfig {

    @Value("${idem.registry.kafka.topic-user-events:idem.registry.user.events}")
    private String userEventsTopic;

    @Value("${idem.registry.kafka.topic-snapshot:idem.registry.user.snapshot}")
    private String snapshotTopic;

    /**
     * §10.5.2 사용자 이벤트 토픽 (Compacted)
     * - cleanup.policy=compact: 최신 상태 보존 (Kafka Log Compaction)
     * - partitions=12: qimUserId 해시 분산
     * - max.compaction.lag.ms=3600000: 1시간 이내 compaction 보장
     */
    @Bean
    public NewTopic qimUserEventsTopic() {
        return TopicBuilder.name(userEventsTopic)
                .partitions(12)
                .replicas(1)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG,
                        TopicConfig.CLEANUP_POLICY_COMPACT)
                .config(TopicConfig.MIN_COMPACTION_LAG_MS_CONFIG, "0")
                .config(TopicConfig.MAX_COMPACTION_LAG_MS_CONFIG, "3600000")
                .config(TopicConfig.SEGMENT_BYTES_CONFIG, "104857600")   // 100MB
                .config(TopicConfig.DELETE_RETENTION_MS_CONFIG, "86400000") // 24h
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "1")
                .config(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, "1048576")
                .build();
    }

    /**
     * §11.5.6 Compacted Snapshot 토픽
     * - full user state 스냅샷 (USER_SNAPSHOT 타입)
     * - 신규 컨슈머 그룹이 최신 상태 빠르게 복원
     * - max.message.bytes 확대: 전체 프로파일 포함
     */
    @Bean
    public NewTopic qimUserSnapshotTopic() {
        return TopicBuilder.name(snapshotTopic)
                .partitions(12)
                .replicas(1)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG,
                        TopicConfig.CLEANUP_POLICY_COMPACT)
                .config(TopicConfig.MIN_COMPACTION_LAG_MS_CONFIG, "0")
                .config(TopicConfig.SEGMENT_BYTES_CONFIG, "104857600")
                .config(TopicConfig.DELETE_RETENTION_MS_CONFIG, "86400000")
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "1")
                .config(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, "10485760") // 10MB (스냅샷)
                .build();
    }

    /** Q-IM DLQ */
    @Bean
    public NewTopic qimUserEventsDlqTopic() {
        return TopicBuilder.name(userEventsTopic + ".dlq")
                .partitions(6)
                .replicas(1)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG,
                        TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }
}

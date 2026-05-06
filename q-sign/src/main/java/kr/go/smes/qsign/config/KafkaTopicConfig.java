package kr.go.smes.qsign.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Q-Sign Kafka 토픽 설정
 * 설계서 §9.3 인증 이벤트 / Transactional Outbox
 *
 * <pre>
 * qsign.auth.events     : 인증 성공/실패/잠금 이벤트 (delete, 1년 보관)
 * qsign.auth.events.dlq : Dead Letter Queue
 * </pre>
 */
@Configuration
public class KafkaTopicConfig {

    @Value("${qsign.kafka.topic-auth-events:qsign.auth.events}")
    private String authEventsTopic;

    /**
     * §9.3 인증 이벤트 토픽
     * - partitionKey = identifierHash → 동일 사용자 이벤트 순서 보장
     * - retention 1년: 감사·분석 목적
     * - lz4 압축: 처리량 최적화
     */
    @Bean
    public NewTopic qsignAuthEventsTopic() {
        return TopicBuilder.name(authEventsTopic)
                .partitions(6)
                .replicas(1)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG,
                        TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(365L * 24 * 60 * 60 * 1000))   // 1년
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                .config(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, "1048576")
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "1")
                .build();
    }

    /** §9.3 인증 이벤트 DLQ */
    @Bean
    public NewTopic qsignAuthEventsDlqTopic() {
        return TopicBuilder.name(authEventsTopic + ".dlq")
                .partitions(3)
                .replicas(1)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG,
                        TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(7L * 24 * 60 * 60 * 1000))     // 7일
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                .build();
    }
}

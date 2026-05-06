package com.onepass.ido.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * IdO Kafka 토픽 설정
 * 설계서 §16.3 Handoff 이벤트 / §14.11 세션 Advisory
 *
 * <pre>
 * ido.handoff.events        : Handoff Issue/Consume/Expire/Revoke
 * platform.session.advisory : 세션 종료 권고 (Advisory / Mandatory)
 * platform.audit.log        : 플랫폼 전역 감사 로그
 * </pre>
 */
@Configuration
public class KafkaTopicConfig {

    @Value("${ido.kafka.topic-handoff-events:ido.handoff.events}")
    private String handoffEventsTopic;

    @Value("${ido.kafka.topic-session-advisory:platform.session.advisory}")
    private String sessionAdvisoryTopic;

    /**
     * §16.3 Handoff 이벤트 토픽
     * - partitionKey = correlationId (동일 correlationId 순서 보장)
     * - retention 1년 (감사)
     */
    @Bean
    public NewTopic idoHandoffEventsTopic() {
        return TopicBuilder.name(handoffEventsTopic)
                .partitions(6)
                .replicas(1)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG,
                        TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(365L * 24 * 60 * 60 * 1000))
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "1")
                .config(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, "1048576")
                .build();
    }

    /** §16.3 Handoff DLQ */
    @Bean
    public NewTopic idoHandoffEventsDlqTopic() {
        return TopicBuilder.name(handoffEventsTopic + ".dlq")
                .partitions(3)
                .replicas(1)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG,
                        TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    /**
     * §14.11 세션 Advisory 토픽
     * - FE, Agency-Stub 이 구독 (각각 독립 컨슈머 그룹)
     * - partitionKey = qimUserId
     * - retention 24시간 (advisory 성격)
     */
    @Bean
    public NewTopic platformSessionAdvisoryTopic() {
        return TopicBuilder.name(sessionAdvisoryTopic)
                .partitions(6)
                .replicas(1)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG,
                        TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(24L * 60 * 60 * 1000))
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "1")
                .config(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, "1048576")
                .build();
    }

    /** §14.11 Advisory DLQ */
    @Bean
    public NewTopic platformSessionAdvisoryDlqTopic() {
        return TopicBuilder.name(sessionAdvisoryTopic + ".dlq")
                .partitions(3)
                .replicas(1)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG,
                        TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    /**
     * §15 플랫폼 전역 감사 로그 토픽
     * - 2년 보관 (감사 요건)
     * - max.message.bytes 확대 (상세 감사 내용 포함)
     */
    @Bean
    public NewTopic platformAuditLogTopic() {
        return TopicBuilder.name("platform.audit.log")
                .partitions(6)
                .replicas(1)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG,
                        TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(2L * 365 * 24 * 60 * 60 * 1000))
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "1")
                .config(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, "2097152")
                .build();
    }
}

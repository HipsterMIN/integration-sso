package io.github.hipstermin.idem.hub.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * IdO Kafka 토픽 설정
 * 설계서 §16.3 Handoff 이벤트 / §14.11 세션 Advisory / §9.3 인증 이벤트
 *
 * <p><b>60,000명 급증 대응 파티션 설계</b>:
 * <pre>
 * 이전 (PoC):  6  파티션, RF=1, ISR=1
 * 현재 (운영): 12  파티션, RF=3, ISR=2
 *
 * 처리량 계산:
 *   qsign.auth.events   12파티션 × concurrency 6 = 초당 ~1,200건 처리 가능
 *   ido.handoff.events  12파티션 × concurrency 6 = 초당 ~1,200건 처리 가능
 *
 *   60,000명이 10분(600s)에 걸쳐 인증 완료한다고 가정:
 *   → 초당 100건 피크 → 현재 설정으로 충분
 *
 *   최악 시나리오 (1분 내 60,000건):
 *   → 초당 1,000건 → 파티션 12 × concurrency 6으로 한계치
 *   → 확장 시 파티션 24 + concurrency 12로 대응
 * </pre>
 *
 * <p><b>운영 환경 주의</b>:
 * 파티션 수 증가는 전체 재시작 없이 가능하나,
 * 파티션 감소는 불가능 — 신중히 설정.
 * RF=3은 Kafka 3-broker 클러스터 환경 필수.
 * PoC 단일 브로커 환경에서는 RF=1로 오버라이드.
 *
 * <p><b>토픽 목록</b>:
 * <pre>
 * qsign.auth.events          : Q-Sign 인증 결과 (Pre-warming 소비)
 * ido.handoff.events         : Handoff 이벤트 (기관 webhook 트리거)
 * ido.handoff.events.dlt     : Handoff DLT (Dead Letter Topic)
 * platform.session.advisory  : 세션 종료 Advisory
 * platform.session.advisory.dlt : Advisory DLT (Dead Letter Topic)
 * platform.audit.log         : 플랫폼 전역 감사 로그
 * ido.webhook.dispatch.requests : webhook 발송 내부 이벤트 (미래 확장용)
 * </pre>
 */
@Configuration
public class KafkaTopicConfig {

    // ── 파티션 수 (환경변수로 오버라이드 가능) ──────────────────────────
    /** 핵심 토픽 파티션 수 — 60k 대응 기준 12 (PoC: 6, 운영: 12~24) */
    @Value("${ido.kafka.partition-count-main:12}")
    private int mainPartitions;

    /** DLQ 파티션 수 — 메인의 절반 */
    @Value("${ido.kafka.partition-count-dlq:6}")
    private int dlqPartitions;

    /** Replication Factor — 운영: 3, PoC: 1 */
    @Value("${ido.kafka.replication-factor:1}")
    private short replicationFactor;

    /** Min ISR — 운영: 2, PoC: 1 */
    @Value("${ido.kafka.min-insync-replicas:1}")
    private String minInsyncReplicas;

    // ── 토픽 이름 설정 ─────────────────────────────────────────────────
    @Value("${ido.kafka.topic-auth-events:qsign.auth.events}")
    private String authEventsTopic;

    @Value("${ido.kafka.topic-handoff-events:ido.handoff.events}")
    private String handoffEventsTopic;

    @Value("${ido.kafka.topic-session-advisory:platform.session.advisory}")
    private String sessionAdvisoryTopic;

    // ═══════════════════════════════════════════════════════════════════════
    // qsign.auth.events — Q-Sign 인증 결과 (Pre-warming 소비)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Q-Sign 인증 이벤트 토픽
     *
     * <p>Q-Sign OutboxRelay가 발행, IdO QsignAuthEventConsumer가 소비.
     * AUTH_COMPLETED → Redis Pre-warming (60k 부하 흡수 핵심).
     *
     * <p>파티션 키: identifierHash (동일 사용자 순서 보장)
     * <p>retention: 1시간 (인증 이벤트는 단기 유효)
     */
    @Bean
    public NewTopic qsignAuthEventsTopic() {
        return TopicBuilder.name(authEventsTopic)
                .partitions(mainPartitions)
                .replicas(replicationFactor)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(60L * 60 * 1000))     // 1시간
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, minInsyncReplicas)
                .config(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, "1048576")
                .build();
    }

    /** Q-Sign 인증 이벤트 DLQ */
    @Bean
    public NewTopic qsignAuthEventsDlqTopic() {
        return TopicBuilder.name(authEventsTopic + ".dlt")
                .partitions(dlqPartitions)
                .replicas(replicationFactor)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(7L * 24 * 60 * 60 * 1000))  // 7일
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ido.handoff.events — Handoff 이벤트 (기관 webhook 트리거)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Handoff 이벤트 토픽
     *
     * <p>HandoffServiceImpl이 발행, HandoffEventConsumer가 소비.
     * HANDOFF_ISSUED → WebhookDispatcherService → 기관 HTTPS webhook.
     *
     * <p>파티션 키: correlationId (동일 correlationId 순서 보장)
     * <p>retention: 1년 (감사 요건)
     */
    @Bean
    public NewTopic idoHandoffEventsTopic() {
        return TopicBuilder.name(handoffEventsTopic)
                .partitions(mainPartitions)
                .replicas(replicationFactor)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(365L * 24 * 60 * 60 * 1000))  // 1년
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, minInsyncReplicas)
                .config(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, "1048576")
                .build();
    }

    /** Handoff DLQ */
    @Bean
    public NewTopic idoHandoffEventsDlqTopic() {
        return TopicBuilder.name(handoffEventsTopic + ".dlt")
                .partitions(dlqPartitions)
                .replicas(replicationFactor)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // platform.session.advisory — 세션 종료 Advisory
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 세션 Advisory 토픽
     *
     * <p>SessionAdvisoryPublisher가 발행.
     * AUTH_LOCKED → MANDATORY_SECURITY_TERMINATE → FE 세션 즉시 무효화.
     *
     * <p>파티션 키: qimUserId (동일 사용자 Advisory 순서 보장)
     * <p>retention: 24시간 (세션 권고 성격)
     */
    @Bean
    public NewTopic platformSessionAdvisoryTopic() {
        return TopicBuilder.name(sessionAdvisoryTopic)
                .partitions(mainPartitions)
                .replicas(replicationFactor)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(24L * 60 * 60 * 1000))   // 24시간
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, minInsyncReplicas)
                .config(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, "1048576")
                .build();
    }

    /** Advisory DLQ */
    @Bean
    public NewTopic platformSessionAdvisoryDlqTopic() {
        return TopicBuilder.name(sessionAdvisoryTopic + ".dlt")
                .partitions(dlqPartitions)
                .replicas(replicationFactor)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // platform.audit.log — 플랫폼 전역 감사 로그
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 감사 로그 토픽
     *
     * <p>AuditLogPublisher가 발행.
     * 법적 보존 요건: 2년.
     * max.message.bytes 확대 (상세 감사 내용 포함).
     *
     * <p>파티션 키: agencyCode (기관별 감사 로그 순서 보장)
     */
    @Bean
    public NewTopic platformAuditLogTopic() {
        return TopicBuilder.name("platform.audit.log")
                .partitions(mainPartitions)
                .replicas(replicationFactor)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(2L * 365 * 24 * 60 * 60 * 1000))  // 2년
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, minInsyncReplicas)
                .config(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, "2097152")
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // qim.user.events — Q-IM 회원 이벤트 (QIM-OUTBOX-SPEC-001 신규 토픽)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Q-IM 회원 이벤트 토픽 (QIM-OUTBOX-SPEC-001 §2 기준)
     *
     * <p>Q-IM Outbox Relay가 발행,
     * QimSpMemberEventConsumer(ido-qim-member-consumer)가 소비.
     *
     * <p>파티션 수 결정 근거 (QIM-OUTBOX-SPEC-001 §2):
     * <ul>
     *   <li>처리량 목표: 150 TPS</li>
     *   <li>설계 처리량: 6파티션 × concurrency 150 = 900건/초 (6× 여유)</li>
     *   <li>확장 전략: 12파티션 → 1,800건/초 (무중단 증설)</li>
     * </ul>
     *
     * <p>파티션 키: qimUserId (동일 사용자 이벤트 순서 보장)
     * <p>retention: 운영 정책 (최소 30일)
     */
    @Bean
    public NewTopic qimUserEventsTopic() {
        return TopicBuilder.name("qim.user.events")
                .partitions(6)
                .replicas(replicationFactor)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(30L * 24 * 60 * 60 * 1000))  // 최소 30일
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, minInsyncReplicas)
                .build();
    }

    /** Q-IM 회원 이벤트 DLQ */
    @Bean
    public NewTopic qimUserEventsDlqTopic() {
        return TopicBuilder.name("qim.user.events.dlt")
                .partitions(3)
                .replicas(replicationFactor)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(7L * 24 * 60 * 60 * 1000))  // 7일
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // qim.agency.events — Q-IM 기관 이벤트 (QIM-OUTBOX-SPEC-001 신규 토픽)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Q-IM 기관 이벤트 토픽 (QIM-OUTBOX-SPEC-001 §2 기준)
     *
     * <p>AGENCY_ADDED / AGENCY_DELETED 이벤트 처리.
     * <p>파티션 수 결정 근거: 저빈도(일 수 건) → 파티션 3개로 충분.
     *
     * <p>파티션 키: agencyCode
     * <p>retention: 365일 (기관 변경 이력 장기 보관)
     */
    @Bean
    public NewTopic qimAgencyEventsTopic() {
        return TopicBuilder.name("qim.agency.events")
                .partitions(3)
                .replicas(replicationFactor)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(365L * 24 * 60 * 60 * 1000))  // 365일
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, minInsyncReplicas)
                .build();
    }

    /** Q-IM 기관 이벤트 DLQ */
    @Bean
    public NewTopic qimAgencyEventsDlqTopic() {
        return TopicBuilder.name("qim.agency.events.dlt")
                .partitions(2)
                .replicas(replicationFactor)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(30L * 24 * 60 * 60 * 1000))  // 30일
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // qim.sp.member.events — Q-IM SP 회원 이벤트 (기존 내부 전파용, 유지)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Q-IM SP 회원 이벤트 토픽 (기존 토픽 — 마이그레이션 완료 후 폐기 예정)
     *
     * <p>기존 QimSpReceiverService → QimSpMemberEventConsumer 경로에서 사용.
     * qim.user.events로 완전 전환 후 이 토픽은 폐기한다.
     *
     * @deprecated qim.user.events로 대체됨 (QIM-OUTBOX-SPEC-001)
     */
    @Deprecated(since = "QIM-OUTBOX-SPEC-001", forRemoval = true)
    @Bean
    public NewTopic qimSpMemberEventsTopic() {
        return TopicBuilder.name("qim.sp.member.events")
                .partitions(6)
                .replicas(replicationFactor)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(30L * 24 * 60 * 60 * 1000))  // 30일
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, minInsyncReplicas)
                .build();
    }

    /** Q-IM SP 회원 이벤트 DLQ */
    @Deprecated(since = "QIM-OUTBOX-SPEC-001", forRemoval = true)
    @Bean
    public NewTopic qimSpMemberEventsDlqTopic() {
        return TopicBuilder.name("qim.sp.member.events.dlt")
                .partitions(3)
                .replicas(replicationFactor)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }
}

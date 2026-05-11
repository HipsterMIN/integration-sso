package kr.go.smes.ido.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * IdO 기능 플래그(Feature Flags) 중앙 관리 빈
 *
 * <p>모든 선택적 기능의 활성화 여부를 단일 클래스에서 관리하여:
 * <ol>
 *   <li>앱 기동 시 전체 기능 상태를 로그로 출력 (운영 확인 용이)</li>
 *   <li>{@code /actuator/features} 엔드포인트로 런타임 상태 조회 가능</li>
 *   <li>각 기능 클래스의 {@code @Value} 중복을 줄이고 일관성 유지</li>
 * </ol>
 *
 * <p><b>전체 플래그 목록</b>: {@code docs/FEATURE_FLAGS.md} 참조
 *
 * <p><b>사용 예시</b>:
 * <pre>{@code
 * @Autowired FeatureFlags flags;
 * if (flags.isAuthRateLimit()) { ... }
 * }</pre>
 *
 * <p><b>환경변수 독립성 원칙</b>:
 * F-01(IP RL)의 {@code IDO_AUTH_RL_ENABLED}와 F-02(기관 RL)의 {@code IDO_RATE_LIMIT_ENABLED}는
 * 반드시 분리된 환경변수를 사용한다 (이전 버전 공유 문제 수정됨).
 */
@Slf4j
@Getter
@Component
public class FeatureFlags {

    // ── F-01: IP 기반 Auth Rate Limiting ────────────────────────────────────
    /** /api/v1/auth/** IP 단위 TPS/분당/일별 제한. 독립 환경변수 IDO_AUTH_RL_ENABLED */
    @Value("${ido.auth.rate-limit.enabled:${IDO_AUTH_RL_ENABLED:true}}")
    private boolean authRateLimit;

    // ── F-02: 기관별 Rate Limiting ──────────────────────────────────────────
    /** 기관(agencyCode) 단위 TPS/일별 제한. 환경변수 IDO_RATE_LIMIT_ENABLED */
    @Value("${ido.rate-limit.enabled:${IDO_RATE_LIMIT_ENABLED:true}}")
    private boolean agencyRateLimit;

    // ── F-03: 감사 로그 Kafka 발행 ──────────────────────────────────────────
    /** platform.audit.log 토픽 비동기 발행. 환경변수 IDO_AUDIT_KAFKA_ENABLED */
    @Value("${ido.audit.kafka-publish-enabled:${IDO_AUDIT_KAFKA_ENABLED:true}}")
    private boolean auditKafka;

    // ── F-04: 감사 로그 DB 저장 ─────────────────────────────────────────────
    /** ido.audit_log 테이블 DB 저장. 환경변수 IDO_AUDIT_DB_ENABLED
     * ⚠️ 운영에서 false 금지 — 컴플라이언스 위반 */
    @Value("${ido.audit.db-save-enabled:${IDO_AUDIT_DB_ENABLED:true}}")
    private boolean auditDb;

    // ── F-05: OTel 분산 추적 AOP ────────────────────────────────────────────
    /** AuthTracingAspect 빈 등록 여부. 환경변수 IDO_AUTH_TRACING_ENABLED */
    @Value("${ido.tracing.auth-aspect-enabled:${IDO_AUTH_TRACING_ENABLED:true}}")
    private boolean authTracing;

    // ── F-08: Redisson 분산 락 ──────────────────────────────────────────────
    /** RedissonClient 빈 등록 여부. 환경변수 IDO_REDISSON_ENABLED
     * false 시 NoOpRedissonConfig의 NoOp 프록시 사용 */
    @Value("${ido.redisson.enabled:${IDO_REDISSON_ENABLED:true}}")
    private boolean redissonLock;

    // ── F-10: 보안 응답 헤더 필터 ────────────────────────────────────────────
    /** SecurityHeadersFilter 빈 등록 여부. 환경변수 IDO_SECURITY_HEADERS_ENABLED */
    @Value("${ido.security-headers.enabled:${IDO_SECURITY_HEADERS_ENABLED:true}}")
    private boolean securityHeaders;

    // ── F-11: 개인정보 파기 스케줄러 ────────────────────────────────────────
    /** 파기 스케줄러 활성 여부. 환경변수 IDO_RETENTION_ENABLED (기본 false — 안전)
     * ⚠️ 운영 적용 전 dry-run=true로 대상 검증 필수 */
    @Value("${ido.retention.enabled:${IDO_RETENTION_ENABLED:false}}")
    private boolean retentionJob;

    /** 파기 dry-run 모드. 환경변수 IDO_RETENTION_DRY_RUN (기본 true — 안전)
     * true: 대상 조회·로그만, false: 실제 영구 삭제 */
    @Value("${ido.retention.dry-run:${IDO_RETENTION_DRY_RUN:true}}")
    private boolean retentionDryRun;

    // ── F-12: Handoff 키 로테이션 스케줄러 ──────────────────────────────────
    /** AES 키 90일 주기 로테이션. 환경변수 IDO_CRYPTO_ROTATION_ENABLED */
    @Value("${ido.crypto.rotation-enabled:${IDO_CRYPTO_ROTATION_ENABLED:true}}")
    private boolean cryptoRotation;

    // ── F-13: IdO Outbox Relay ───────────────────────────────────────────────
    /** ido.outbox PENDING 이벤트 Kafka 재발행. 환경변수 IDO_OUTBOX_RELAY_ENABLED */
    @Value("${ido.outbox.relay-enabled:${IDO_OUTBOX_RELAY_ENABLED:true}}")
    private boolean outboxRelay;

    // ── F-14: Webhook Outbox Relay ───────────────────────────────────────────
    /** webhook_dispatch_outbox PENDING 기관 HTTP 발송. 환경변수 IDO_WEBHOOK_RELAY_ENABLED */
    @Value("${ido.webhook.relay-enabled:${IDO_WEBHOOK_RELAY_ENABLED:true}}")
    private boolean webhookRelay;

    // ── F-18: SP 수신 감사 로그 ──────────────────────────────────────────────
    /** Q-IM SP 수신 API 감사 로그. 환경변수 IDO_QIM_RECEIVER_AUDIT */
    @Value("${ido.qim.receiver-audit-enabled:${IDO_QIM_RECEIVER_AUDIT:true}}")
    private boolean spReceiverAudit;

    // ════════════════════════════════════════════════════════════════════════
    // 기동 시 전체 상태 출력
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 앱 기동 완료 후 현재 기능 플래그 상태를 INFO 레벨로 일괄 출력.
     *
     * <p>운영 배포 시 로그에서 즉시 확인 가능:
     * <pre>
     * [FeatureFlags] ===== 현재 기능 활성화 상태 =====
     * [FeatureFlags]   F-01 authRateLimit       = true   (IDO_AUTH_RL_ENABLED)
     * [FeatureFlags]   F-02 agencyRateLimit     = true   (IDO_RATE_LIMIT_ENABLED)
     * ...
     * </pre>
     */
    @PostConstruct
    public void logFeatureFlags() {
        log.info("[FeatureFlags] ===== 현재 기능 활성화 상태 =====");
        log.info("[FeatureFlags]   F-01 authRateLimit       = {}  (IDO_AUTH_RL_ENABLED)", fmt(authRateLimit));
        log.info("[FeatureFlags]   F-02 agencyRateLimit     = {}  (IDO_RATE_LIMIT_ENABLED)", fmt(agencyRateLimit));
        log.info("[FeatureFlags]   F-03 auditKafka          = {}  (IDO_AUDIT_KAFKA_ENABLED)", fmt(auditKafka));
        log.info("[FeatureFlags]   F-04 auditDb             = {}  (IDO_AUDIT_DB_ENABLED)", fmt(auditDb));
        log.info("[FeatureFlags]   F-05 authTracing         = {}  (IDO_AUTH_TRACING_ENABLED)", fmt(authTracing));
        log.info("[FeatureFlags]   F-08 redissonLock        = {}  (IDO_REDISSON_ENABLED)", fmt(redissonLock));
        log.info("[FeatureFlags]   F-10 securityHeaders     = {}  (IDO_SECURITY_HEADERS_ENABLED)", fmt(securityHeaders));
        log.info("[FeatureFlags]   F-11 retentionJob        = {}  (IDO_RETENTION_ENABLED)", fmt(retentionJob));
        log.info("[FeatureFlags]   F-11b retentionDryRun   = {}  (IDO_RETENTION_DRY_RUN)", fmt(retentionDryRun));
        log.info("[FeatureFlags]   F-12 cryptoRotation      = {}  (IDO_CRYPTO_ROTATION_ENABLED)", fmt(cryptoRotation));
        log.info("[FeatureFlags]   F-13 outboxRelay         = {}  (IDO_OUTBOX_RELAY_ENABLED)", fmt(outboxRelay));
        log.info("[FeatureFlags]   F-14 webhookRelay        = {}  (IDO_WEBHOOK_RELAY_ENABLED)", fmt(webhookRelay));
        log.info("[FeatureFlags]   F-18 spReceiverAudit     = {}  (IDO_QIM_RECEIVER_AUDIT)", fmt(spReceiverAudit));
        log.info("[FeatureFlags] ==========================================");

        // 운영 위험 경고
        if (!auditDb) {
            log.warn("[FeatureFlags] ⚠️  F-04 auditDb=OFF — 감사 로그 DB 저장 비활성. 운영 환경에서는 IDO_AUDIT_DB_ENABLED=true 필수!");
        }
        if (retentionJob && !retentionDryRun) {
            log.warn("[FeatureFlags] ⚠️  F-11 retentionJob=ON + dryRun=OFF — 개인정보 실제 파기 활성. 법무팀 승인 확인 필요!");
        }
        if (!redissonLock) {
            log.warn("[FeatureFlags] ⚠️  F-08 redissonLock=OFF — 분산 락 비활성. K8s 다중 Pod 환경에서는 IDO_REDISSON_ENABLED=true 필수!");
        }
    }

    /** 로그 출력용 포맷 (true→"true ", false→"false") */
    private String fmt(boolean v) {
        return v ? "true " : "false";
    }

    // ════════════════════════════════════════════════════════════════════════
    // Actuator 엔드포인트용 요약 맵
    // ════════════════════════════════════════════════════════════════════════

    /**
     * /actuator/features 응답용 상태 맵 반환.
     *
     * <p>FeaturesEndpoint 빈에서 이 메서드를 호출하여 응답 구성.
     */
    public java.util.Map<String, Object> toStatusMap() {
        return java.util.Map.ofEntries(
            java.util.Map.entry("F-01_authRateLimit",      featureEntry(authRateLimit,    "IDO_AUTH_RL_ENABLED")),
            java.util.Map.entry("F-02_agencyRateLimit",    featureEntry(agencyRateLimit,  "IDO_RATE_LIMIT_ENABLED")),
            java.util.Map.entry("F-03_auditKafka",         featureEntry(auditKafka,       "IDO_AUDIT_KAFKA_ENABLED")),
            java.util.Map.entry("F-04_auditDb",            featureEntry(auditDb,          "IDO_AUDIT_DB_ENABLED")),
            java.util.Map.entry("F-05_authTracing",        featureEntry(authTracing,      "IDO_AUTH_TRACING_ENABLED")),
            java.util.Map.entry("F-08_redissonLock",       featureEntry(redissonLock,     "IDO_REDISSON_ENABLED")),
            java.util.Map.entry("F-10_securityHeaders",    featureEntry(securityHeaders,  "IDO_SECURITY_HEADERS_ENABLED")),
            java.util.Map.entry("F-11_retentionJob",       featureEntry(retentionJob,     "IDO_RETENTION_ENABLED")),
            java.util.Map.entry("F-11b_retentionDryRun",   featureEntry(retentionDryRun,  "IDO_RETENTION_DRY_RUN")),
            java.util.Map.entry("F-12_cryptoRotation",     featureEntry(cryptoRotation,   "IDO_CRYPTO_ROTATION_ENABLED")),
            java.util.Map.entry("F-13_outboxRelay",        featureEntry(outboxRelay,      "IDO_OUTBOX_RELAY_ENABLED")),
            java.util.Map.entry("F-14_webhookRelay",       featureEntry(webhookRelay,     "IDO_WEBHOOK_RELAY_ENABLED")),
            java.util.Map.entry("F-18_spReceiverAudit",    featureEntry(spReceiverAudit,  "IDO_QIM_RECEIVER_AUDIT"))
        );
    }

    private java.util.Map<String, Object> featureEntry(boolean enabled, String envVar) {
        return java.util.Map.of("enabled", enabled, "env", envVar);
    }
}

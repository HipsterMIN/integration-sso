package io.github.hipstermin.idem.hub.config;

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
 * <h2>Phase-Gate 배포 전략</h2>
 * <p>Sprint 14~16 신규 기능(F-20~F-27)은 <b>Phase-Gate 방식</b>으로 단계적으로 활성화합니다.
 * 각 Phase의 기본값과 활성화 조건은 {@code docs/phased-rollout-strategy.md}를 참조하세요.
 *
 * <pre>
 * Phase 1 (기반 안정화): F-20~F-27 모두 기본값 유지 (대부분 false)
 * Phase 2 (프로비저닝):  F-20, F-21, F-22 활성화
 * Phase 3 (Gateway):    F-23, F-24 활성화
 * Phase 4 (보안 강화):  F-26 활성화 (HMAC 필수화)
 * </pre>
 *
 * <h2>전체 플래그 목록</h2>
 * <ul>
 *   <li>F-01 ~ F-18: 기존 기능 (안정 운영 중) → {@code docs/features/} 참조</li>
 *   <li>F-20 ~ F-27: Sprint 14~16 신규 기능 → Phase-Gate 관리</li>
 * </ul>
 *
 * <h2>사용 예시</h2>
 * <pre>{@code
 * @Autowired FeatureFlags flags;
 * if (flags.isProvisioning()) { ... }
 * if (flags.isGatewayInbound()) { ... }
 * }</pre>
 *
 * <h2>환경변수 독립성 원칙</h2>
 * <p>F-01(IP RL)의 {@code IDO_AUTH_RL_ENABLED}와 F-02(기관 RL)의 {@code IDO_RATE_LIMIT_ENABLED}는
 * 반드시 분리된 환경변수를 사용한다 (이전 버전 공유 문제 수정됨).
 */
@Slf4j
@Getter
@Component
public class FeatureFlags {

    // ════════════════════════════════════════════════════════════════════════
    // ── 기존 기능 플래그 (F-01 ~ F-18): 안정 운영 중
    // ════════════════════════════════════════════════════════════════════════

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
    // ── 신규 기능 플래그 (F-20 ~ F-27): Sprint 14~16, Phase-Gate 관리
    // ════════════════════════════════════════════════════════════════════════
    // ⚠️  Phase 1 기본값: false (Phase-Gate 통과 후 true로 전환)
    // 📖  상세: docs/phased-rollout-strategy.md

    // ── F-20: 전 기관 프로비저닝 (Sprint 14) ────────────────────────────────
    /**
     * 전 기관 프로비저닝 활성화 여부.
     *
     * <p>JDK 21 Virtual Thread로 최대 68개 기관에 동시 HTTP POST를 발행합니다.
     * true 전환 전 {@code IDO_PROVISIONING_DRY_RUN=true}로 먼저 로그 관찰 필수.
     *
     * <p><b>Phase 1 기본값: false</b> (Phase 2에서 true로 전환)
     *
     * @see docs/features/F-20-provisioning.md
     */
    @Value("${ido.provisioning.enabled:${IDO_PROVISIONING_ENABLED:false}}")
    private boolean provisioning;

    // ── F-21: Provisioning Outbox Relay (Sprint 14) ─────────────────────────
    /**
     * Provisioning Outbox 릴레이 활성화 여부.
     *
     * <p>PENDING 상태 provisioning_outbox 레코드를 30초마다 재시도합니다.
     * 지수 백오프: 1분 → 5분 → 30분. FOR UPDATE SKIP LOCKED로 중복 방지.
     * F-20이 false면 이 플래그도 false여야 합니다.
     *
     * <p><b>Phase 1 기본값: false</b> (Phase 2-B에서 true로 전환)
     *
     * @see docs/features/F-21-provisioning-relay.md
     */
    @Value("${ido.provisioning.relay-enabled:${IDO_PROVISIONING_RELAY_ENABLED:false}}")
    private boolean provisioningRelay;

    // ── F-22: 프로비저닝 Dry-Run 모드 (Sprint 14) ───────────────────────────
    /**
     * 프로비저닝 dry-run 모드.
     *
     * <p>true: 페이로드 생성 및 로그 출력만, 실제 HTTP POST 미발행.
     * F-20 활성화 후 2주간 이 모드로 관찰한 후 false로 전환 권장.
     * false로 변경 시 실제 기관 서버에 HTTP 요청이 발행됩니다.
     *
     * <p><b>기본값: true (안전)</b> — Phase 2-A 관찰 후 false로 전환
     *
     * @see docs/features/F-20-provisioning.md
     */
    @Value("${ido.provisioning.dry-run:${IDO_PROVISIONING_DRY_RUN:true}}")
    private boolean provisioningDryRun;

    // ── F-23: Agency 인바운드 API (Sprint 15) ────────────────────────────────
    /**
     * 기관 → OnePass 인바운드 이벤트 API 활성화 여부.
     *
     * <p>POST /api/v1/agency/gateway/inbound/event 엔드포인트를 처리합니다.
     * false 시 해당 엔드포인트가 503 Service Unavailable을 반환합니다.
     * Redis 멱등성 방어(F-25)와 함께 동작합니다.
     *
     * <p><b>Phase 1 기본값: false</b> (Phase 3-A에서 true로 전환)
     *
     * @see docs/features/F-23-gateway-inbound.md
     */
    @Value("${ido.gateway.inbound-enabled:${IDO_GATEWAY_INBOUND_ENABLED:false}}")
    private boolean gatewayInbound;

    // ── F-24: Agency 아웃바운드 API (Sprint 15) ──────────────────────────────
    /**
     * OnePass → 기관 아웃바운드 API 활성화 여부.
     *
     * <p>PATCH /api/v1/agency/gateway/outbound/notify 엔드포인트를 처리합니다.
     * false 시 해당 엔드포인트가 503 Service Unavailable을 반환합니다.
     * F-23 인바운드 활성화 후 1주 이상 안정 확인 후 활성화 권장.
     *
     * <p><b>Phase 1 기본값: false</b> (Phase 3-B에서 true로 전환)
     *
     * @see docs/features/F-24-gateway-outbound.md
     */
    @Value("${ido.gateway.outbound-enabled:${IDO_GATEWAY_OUTBOUND_ENABLED:false}}")
    private boolean gatewayOutbound;

    // ── F-25: Gateway 멱등성 방어 (Sprint 15) ───────────────────────────────
    /**
     * Gateway Redis 멱등성 중복 방어 활성화 여부.
     *
     * <p>Redis SET NX로 인바운드/아웃바운드 중복 요청을 방지합니다.
     * Redis 장애 시에도 DB UNIQUE 제약이 2차로 방어합니다.
     * 보안 필수 기능이므로 <b>false로 설정하지 않도록</b> 합니다.
     *
     * <p><b>기본값: true (항상 ON)</b>
     *
     * @see docs/features/F-25-gateway-idempotency.md
     */
    @Value("${ido.gateway.idempotency-enabled:${IDO_GATEWAY_IDEMPOTENCY_ENABLED:true}}")
    private boolean gatewayIdempotency;

    // ── F-26: HMAC 서명 필수화 (Sprint 17 예정) ──────────────────────────────
    /**
     * X-Internal-Sig HMAC-SHA256 서명 필수 검증 여부.
     *
     * <p>true 시 인바운드 요청에 X-Internal-Sig 헤더가 없거나 검증 실패하면 401 반환.
     * 현재(Sprint 16)는 헤더가 있으면 검증, 없어도 통과하는 선택적 모드입니다.
     * <b>모든 연동 기관이 HMAC 헤더를 포함하도록 준비된 후에만 true로 전환하세요.</b>
     *
     * <p><b>Phase 1~3 기본값: false</b> (Phase 4에서 true로 전환 — Sprint 17)
     *
     * @see docs/features/F-26-hmac-sig.md
     */
    @Value("${ido.gateway.hmac-sig-required:${IDO_HMAC_SIG_REQUIRED:false}}")
    private boolean hmacSigRequired;

    // ── F-27: Agency Key 인증 감사 로그 (Sprint 15) ──────────────────────────
    /**
     * Agency API Key 인증 시도 감사 로그 활성화 여부.
     *
     * <p>HandoffAgencyKeyInterceptor에서 인증 성공/실패를 로그로 기록합니다.
     * 보안 감사 목적이므로 운영에서는 항상 true를 유지합니다.
     *
     * <p><b>기본값: true (항상 ON)</b>
     *
     * @see docs/features/F-27-agency-key-audit.md
     */
    @Value("${ido.gateway.agency-key-audit-log:${IDO_AGENCY_KEY_AUDIT_LOG:true}}")
    private boolean agencyKeyAuditLog;

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
     * ...
     * [FeatureFlags] ── Phase-Gate 신규 기능 (Sprint 14~16) ──
     * [FeatureFlags]   F-20 provisioning        = false  (IDO_PROVISIONING_ENABLED)  ← Phase 1
     * </pre>
     */
    @PostConstruct
    public void logFeatureFlags() {
        log.info("[FeatureFlags] ===== 현재 기능 활성화 상태 =====");

        // ── 기존 기능 ──
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

        // ── Sprint 14~16 신규 기능 (Phase-Gate 관리) ──
        log.info("[FeatureFlags] ── Sprint 14~16 신규 기능 (Phase-Gate) ──");
        log.info("[FeatureFlags]   F-20 provisioning        = {}  (IDO_PROVISIONING_ENABLED)  Phase2↑", fmt(provisioning));
        log.info("[FeatureFlags]   F-21 provisioningRelay   = {}  (IDO_PROVISIONING_RELAY_ENABLED) Phase2B↑", fmt(provisioningRelay));
        log.info("[FeatureFlags]   F-22 provisioningDryRun  = {}  (IDO_PROVISIONING_DRY_RUN)  Phase2A=true", fmt(provisioningDryRun));
        log.info("[FeatureFlags]   F-23 gatewayInbound      = {}  (IDO_GATEWAY_INBOUND_ENABLED) Phase3↑", fmt(gatewayInbound));
        log.info("[FeatureFlags]   F-24 gatewayOutbound     = {}  (IDO_GATEWAY_OUTBOUND_ENABLED) Phase3B↑", fmt(gatewayOutbound));
        log.info("[FeatureFlags]   F-25 gatewayIdempotency  = {}  (IDO_GATEWAY_IDEMPOTENCY_ENABLED) 항상ON", fmt(gatewayIdempotency));
        log.info("[FeatureFlags]   F-26 hmacSigRequired     = {}  (IDO_HMAC_SIG_REQUIRED)  Phase4↑(S17)", fmt(hmacSigRequired));
        log.info("[FeatureFlags]   F-27 agencyKeyAuditLog   = {}  (IDO_AGENCY_KEY_AUDIT_LOG) 항상ON", fmt(agencyKeyAuditLog));
        log.info("[FeatureFlags] ==========================================");

        // ── 운영 위험 경고 ──
        if (!auditDb) {
            log.warn("[FeatureFlags] ⚠️  F-04 auditDb=OFF — 감사 로그 DB 저장 비활성. 운영 환경에서는 IDO_AUDIT_DB_ENABLED=true 필수!");
        }
        if (retentionJob && !retentionDryRun) {
            log.warn("[FeatureFlags] ⚠️  F-11 retentionJob=ON + dryRun=OFF — 개인정보 실제 파기 활성. 법무팀 승인 확인 필요!");
        }
        if (!redissonLock) {
            log.warn("[FeatureFlags] ⚠️  F-08 redissonLock=OFF — 분산 락 비활성. K8s 다중 Pod 환경에서는 IDO_REDISSON_ENABLED=true 필수!");
        }

        // ── Sprint 14~16 Phase 경고 ──
        if (provisioning && provisioningDryRun) {
            log.info("[FeatureFlags] ℹ️  F-20+F-22: 프로비저닝 DRY-RUN 모드 — 로그만 출력, 실제 HTTP 미발행. 정상 Phase2-A 상태.");
        }
        if (provisioning && !provisioningDryRun && !provisioningRelay) {
            log.warn("[FeatureFlags] ⚠️  F-20 ON + F-21 OFF: 프로비저닝 실패 시 재시도 릴레이 없음. IDO_PROVISIONING_RELAY_ENABLED=true 권장.");
        }
        if (gatewayInbound && !gatewayIdempotency) {
            log.warn("[FeatureFlags] ⚠️  F-23 ON + F-25 OFF: Gateway 인바운드 멱등성 방어 비활성. 중복 이벤트 위험. IDO_GATEWAY_IDEMPOTENCY_ENABLED=true 필수!");
        }
        if (hmacSigRequired) {
            log.info("[FeatureFlags] ✅  F-26 HMAC 서명 필수화 ON — 모든 인바운드 요청에 X-Internal-Sig 헤더 검증 중.");
        }
        if (gatewayOutbound && !gatewayInbound) {
            log.warn("[FeatureFlags] ⚠️  F-24 ON + F-23 OFF: 아웃바운드 활성화지만 인바운드 비활성. 비대칭 설정 확인 필요.");
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
     *
     * <p>Phase 정보가 포함된 응답 예시:
     * <pre>{@code
     * {
     *   "features": {
     *     "F-20_provisioning": {
     *       "enabled": false,
     *       "env": "IDO_PROVISIONING_ENABLED",
     *       "phase": "Phase2",
     *       "description": "전 기관 Virtual Thread 병렬 프로비저닝"
     *     },
     *     ...
     *   }
     * }
     * }</pre>
     */
    public java.util.Map<String, Object> toStatusMap() {
        return java.util.Map.ofEntries(
            // ── 기존 기능 ──
            java.util.Map.entry("F-01_authRateLimit",
                featureEntry(authRateLimit, "IDO_AUTH_RL_ENABLED", "stable", "IP 기반 Auth 엔드포인트 Rate Limiting")),
            java.util.Map.entry("F-02_agencyRateLimit",
                featureEntry(agencyRateLimit, "IDO_RATE_LIMIT_ENABLED", "stable", "기관별 API Rate Limiting (Bucket4j)")),
            java.util.Map.entry("F-03_auditKafka",
                featureEntry(auditKafka, "IDO_AUDIT_KAFKA_ENABLED", "stable", "감사 로그 Kafka 비동기 발행")),
            java.util.Map.entry("F-04_auditDb",
                featureEntry(auditDb, "IDO_AUDIT_DB_ENABLED", "stable", "감사 로그 DB 저장 (OFF금지)")),
            java.util.Map.entry("F-05_authTracing",
                featureEntry(authTracing, "IDO_AUTH_TRACING_ENABLED", "stable", "OTel 분산 추적 AOP")),
            java.util.Map.entry("F-08_redissonLock",
                featureEntry(redissonLock, "IDO_REDISSON_ENABLED", "stable", "Redisson 분산 락")),
            java.util.Map.entry("F-10_securityHeaders",
                featureEntry(securityHeaders, "IDO_SECURITY_HEADERS_ENABLED", "stable", "보안 응답 헤더 필터")),
            java.util.Map.entry("F-11_retentionJob",
                featureEntry(retentionJob, "IDO_RETENTION_ENABLED", "stable", "개인정보 파기 스케줄러")),
            java.util.Map.entry("F-11b_retentionDryRun",
                featureEntry(retentionDryRun, "IDO_RETENTION_DRY_RUN", "stable", "파기 dry-run 모드")),
            java.util.Map.entry("F-12_cryptoRotation",
                featureEntry(cryptoRotation, "IDO_CRYPTO_ROTATION_ENABLED", "stable", "Handoff AES 키 로테이션")),
            java.util.Map.entry("F-13_outboxRelay",
                featureEntry(outboxRelay, "IDO_OUTBOX_RELAY_ENABLED", "stable", "IdO Outbox Kafka 릴레이")),
            java.util.Map.entry("F-14_webhookRelay",
                featureEntry(webhookRelay, "IDO_WEBHOOK_RELAY_ENABLED", "stable", "Webhook Outbox HTTP 릴레이")),
            java.util.Map.entry("F-18_spReceiverAudit",
                featureEntry(spReceiverAudit, "IDO_QIM_RECEIVER_AUDIT", "stable", "Q-IM SP 수신 감사 로그")),

            // ── Sprint 14~16 신규 기능 (Phase-Gate) ──
            java.util.Map.entry("F-20_provisioning",
                featureEntry(provisioning, "IDO_PROVISIONING_ENABLED", "Phase2", "전 기관 Virtual Thread 병렬 프로비저닝")),
            java.util.Map.entry("F-21_provisioningRelay",
                featureEntry(provisioningRelay, "IDO_PROVISIONING_RELAY_ENABLED", "Phase2B", "Provisioning Outbox 지수백오프 릴레이")),
            java.util.Map.entry("F-22_provisioningDryRun",
                featureEntry(provisioningDryRun, "IDO_PROVISIONING_DRY_RUN", "Phase2A", "프로비저닝 dry-run (로그만, HTTP 미발행)")),
            java.util.Map.entry("F-23_gatewayInbound",
                featureEntry(gatewayInbound, "IDO_GATEWAY_INBOUND_ENABLED", "Phase3A", "기관→OnePass 인바운드 이벤트 API")),
            java.util.Map.entry("F-24_gatewayOutbound",
                featureEntry(gatewayOutbound, "IDO_GATEWAY_OUTBOUND_ENABLED", "Phase3B", "OnePass→기관 아웃바운드 API")),
            java.util.Map.entry("F-25_gatewayIdempotency",
                featureEntry(gatewayIdempotency, "IDO_GATEWAY_IDEMPOTENCY_ENABLED", "always-on", "Gateway Redis 멱등성 중복 방어")),
            java.util.Map.entry("F-26_hmacSigRequired",
                featureEntry(hmacSigRequired, "IDO_HMAC_SIG_REQUIRED", "Phase4(S17)", "X-Internal-Sig HMAC-SHA256 필수 검증")),
            java.util.Map.entry("F-27_agencyKeyAuditLog",
                featureEntry(agencyKeyAuditLog, "IDO_AGENCY_KEY_AUDIT_LOG", "always-on", "Agency API Key 인증 감사 로그"))
        );
    }

    /**
     * 기존 호환성 유지용 — phase 정보 없는 2-필드 엔트리.
     * 내부에서 사용하지 않음.
     */
    @Deprecated
    private java.util.Map<String, Object> featureEntry(boolean enabled, String envVar) {
        return java.util.Map.of("enabled", enabled, "env", envVar);
    }

    /**
     * phase, description 포함 4-필드 엔트리 (현재 표준).
     *
     * @param enabled     현재 활성화 여부
     * @param envVar      제어 환경변수명
     * @param phase       활성화 Phase 정보 (예: "Phase2", "stable", "always-on")
     * @param description 기능 설명 (한국어)
     */
    private java.util.Map<String, Object> featureEntry(boolean enabled, String envVar,
                                                        String phase, String description) {
        return java.util.Map.of(
            "enabled",     enabled,
            "env",         envVar,
            "phase",       phase,
            "description", description
        );
    }
}

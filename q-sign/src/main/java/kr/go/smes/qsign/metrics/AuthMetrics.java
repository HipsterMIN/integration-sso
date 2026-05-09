package kr.go.smes.qsign.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Q-Sign 인증 비즈니스 메트릭
 *
 * <p>Prometheus/Grafana 연동 대상 핵심 지표:
 * <ul>
 *   <li>{@code auth.success.total}         — 인증 성공 건수 (provider, auth_level 태그)</li>
 *   <li>{@code auth.failure.total}         — 인증 실패 건수 (provider, reason 태그)</li>
 *   <li>{@code auth.locked.total}          — 잠금 상태 인증 시도 건수 (provider 태그)</li>
 *   <li>{@code auth.duration.seconds}      — 인증 처리 시간 (provider, auth_level 태그)</li>
 *   <li>{@code slo.initiate.total}         — SLO 시작 건수</li>
 *   <li>{@code slo.keycloak.success.total} — Keycloak 세션 종료 성공 건수</li>
 *   <li>{@code slo.keycloak.failure.total} — Keycloak 세션 종료 실패 건수</li>
 *   <li>{@code slo.webhook.enqueued.total} — SLO Webhook Outbox 큐 삽입 건수</li>
 * </ul>
 *
 * <p>태그 설계 원칙 (Low-cardinality 보장):
 * <ul>
 *   <li>provider: "PASS" | "GPKI" | "KEYCLOAK" | "UNKNOWN"</li>
 *   <li>auth_level: "AA" | "A" | "UNKNOWN"</li>
 *   <li>reason: "LOCKED" | "IDP_ERROR" | "SIGNATURE_MISMATCH" | "INVALID_RESPONSE" | "UNKNOWN"</li>
 *   <li>⚠️  PII(identifierHash, sessionId 등)는 절대 태그에 포함 금지</li>
 * </ul>
 */
@Slf4j
@Component
public class AuthMetrics {

    // ── 메트릭 이름 상수 ────────────────────────────────────────────────────

    public static final String METRIC_AUTH_SUCCESS  = "auth.success.total";
    public static final String METRIC_AUTH_FAILURE  = "auth.failure.total";
    public static final String METRIC_AUTH_LOCKED   = "auth.locked.total";
    public static final String METRIC_AUTH_DURATION = "auth.duration.seconds";

    public static final String METRIC_SLO_INITIATE         = "slo.initiate.total";
    public static final String METRIC_SLO_KEYCLOAK_SUCCESS = "slo.keycloak.success.total";
    public static final String METRIC_SLO_KEYCLOAK_FAILURE = "slo.keycloak.failure.total";
    public static final String METRIC_SLO_WEBHOOK_ENQUEUED = "slo.webhook.enqueued.total";

    // ── 실패 원인 상수 (reason 태그 값) ─────────────────────────────────────

    public static final String REASON_LOCKED             = "LOCKED";
    public static final String REASON_IDP_ERROR          = "IDP_ERROR";
    public static final String REASON_SIGNATURE_MISMATCH = "SIGNATURE_MISMATCH";
    public static final String REASON_INVALID_RESPONSE   = "INVALID_RESPONSE";
    public static final String REASON_UNKNOWN            = "UNKNOWN";

    // ── 내부 상태 ────────────────────────────────────────────────────────────

    private final MeterRegistry registry;

    /**
     * Timer 는 (provider × auth_level) 조합별 캐시.
     * Timer.Builder 가 매번 호출되면 중복 등록 오류가 발생하므로 캐시 필수.
     */
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    public AuthMetrics(MeterRegistry registry) {
        this.registry = registry;

        // SLO 카운터 — 태그 없이 단일 시계열, 애플리케이션 기동 시 사전 등록
        Counter.builder(METRIC_SLO_INITIATE)
                .description("SLO 시작 건수")
                .register(registry);
        Counter.builder(METRIC_SLO_KEYCLOAK_SUCCESS)
                .description("Keycloak 세션 종료 성공 건수")
                .register(registry);
        Counter.builder(METRIC_SLO_KEYCLOAK_FAILURE)
                .description("Keycloak 세션 종료 실패 건수")
                .register(registry);
        Counter.builder(METRIC_SLO_WEBHOOK_ENQUEUED)
                .description("SLO Webhook Outbox 큐 삽입 건수")
                .register(registry);
    }

    // ── 인증 성공 ────────────────────────────────────────────────────────────

    /**
     * 인증 성공 카운터 증가.
     *
     * @param provider  인증 제공자 코드 ("PASS", "GPKI", "KEYCLOAK" 등)
     * @param authLevel 인증 수준 ("AA", "A" 등)
     */
    public void incrementAuthSuccess(String provider, String authLevel) {
        Counter.builder(METRIC_AUTH_SUCCESS)
                .tag("provider",   sanitizeTag(provider))
                .tag("auth_level", sanitizeTag(authLevel))
                .description("인증 성공 건수")
                .register(registry)
                .increment();
        log.debug("[AuthMetrics] auth.success provider={} authLevel={}", provider, authLevel);
    }

    // ── 인증 실패 ────────────────────────────────────────────────────────────

    /**
     * 인증 실패 카운터 증가.
     *
     * @param provider 인증 제공자 코드
     * @param reason   실패 원인 ({@link #REASON_LOCKED} 등)
     */
    public void incrementAuthFailure(String provider, String reason) {
        Counter.builder(METRIC_AUTH_FAILURE)
                .tag("provider", sanitizeTag(provider))
                .tag("reason",   sanitizeTag(reason))
                .description("인증 실패 건수")
                .register(registry)
                .increment();
        log.debug("[AuthMetrics] auth.failure provider={} reason={}", provider, reason);
    }

    // ── 잠금 인증 시도 ────────────────────────────────────────────────────────

    /**
     * 잠금(Lock) 상태에서의 인증 시도 카운터 증가.
     *
     * @param provider 인증 제공자 코드
     */
    public void incrementAuthLocked(String provider) {
        Counter.builder(METRIC_AUTH_LOCKED)
                .tag("provider", sanitizeTag(provider))
                .description("잠금 상태 인증 시도 건수")
                .register(registry)
                .increment();
        log.debug("[AuthMetrics] auth.locked provider={}", provider);
    }

    // ── 인증 처리 시간 ────────────────────────────────────────────────────────

    /**
     * 인증 처리 시간 기록.
     * p50/p95/p99 퍼센타일을 함께 발행한다.
     *
     * @param provider   인증 제공자 코드
     * @param authLevel  인증 수준
     * @param durationMs 처리 시간 (밀리초)
     */
    public void recordAuthDuration(String provider, String authLevel, long durationMs) {
        String cacheKey = sanitizeTag(provider) + ":" + sanitizeTag(authLevel);
        Timer timer = timerCache.computeIfAbsent(cacheKey, k ->
                Timer.builder(METRIC_AUTH_DURATION)
                        .tag("provider",   sanitizeTag(provider))
                        .tag("auth_level", sanitizeTag(authLevel))
                        .description("인증 처리 시간")
                        .publishPercentiles(0.50, 0.95, 0.99)
                        .register(registry)
        );
        timer.record(Duration.ofMillis(Math.max(0, durationMs)));
    }

    // ── SLO 메트릭 ──────────────────────────────────────────────────────────

    /** SLO 시작 카운터 증가 */
    public void incrementSloInitiate() {
        registry.counter(METRIC_SLO_INITIATE).increment();
    }

    /** Keycloak 세션 종료 성공 카운터 증가 */
    public void incrementSloKeycloakSuccess() {
        registry.counter(METRIC_SLO_KEYCLOAK_SUCCESS).increment();
    }

    /** Keycloak 세션 종료 실패 카운터 증가 */
    public void incrementSloKeycloakFailure() {
        registry.counter(METRIC_SLO_KEYCLOAK_FAILURE).increment();
    }

    /** SLO Webhook Outbox 큐 삽입 카운터 증가 */
    public void incrementSloWebhookEnqueued() {
        registry.counter(METRIC_SLO_WEBHOOK_ENQUEUED).increment();
    }

    // ── 내부 유틸 ────────────────────────────────────────────────────────────

    /**
     * 태그 값 정제.
     * <ul>
     *   <li>null / 공백 → "UNKNOWN"</li>
     *   <li>대문자 변환 (tag 일관성)</li>
     *   <li>50자 초과 시 잘라냄 (Prometheus 시계열 폭발 방지)</li>
     * </ul>
     */
    private String sanitizeTag(String value) {
        if (value == null || value.isBlank()) return "UNKNOWN";
        String trimmed = value.trim().toUpperCase();
        return trimmed.length() > 50 ? trimmed.substring(0, 50) : trimmed;
    }
}

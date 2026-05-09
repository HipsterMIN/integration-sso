package kr.go.smes.ido.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * IdO SLO(Single Logout) 비즈니스 메트릭
 *
 * <p>Prometheus/Grafana 연동 대상 지표:
 * <ul>
 *   <li>{@code slo.initiate.total}          — SLO 시작 건수</li>
 *   <li>{@code slo.completed.total}         — SLO 완전 완료 건수 (feSession 만료 성공)</li>
 *   <li>{@code slo.skipped.total}           — feSessionId 없어 스킵된 SLO 건수</li>
 *   <li>{@code slo.duration.seconds}        — SLO 전체 처리 시간</li>
 *   <li>{@code slo.webhook.enqueued.total}  — USER_LOGOUT Webhook Outbox 큐 삽입 건수</li>
 *   <li>{@code personal.data.purged.total}  — 개인정보 파기 처리 건수</li>
 *   <li>{@code personal.data.purge.failed.total} — 개인정보 파기 실패 건수</li>
 * </ul>
 *
 * <p>태그 설계 원칙: 낮은 카디널리티 보장, PII 태그 금지.
 */
@Slf4j
@Component
public class SloMetrics {

    // ── 메트릭 이름 상수 ────────────────────────────────────────────────────

    public static final String METRIC_SLO_INITIATE         = "slo.initiate.total";
    public static final String METRIC_SLO_COMPLETED        = "slo.completed.total";
    public static final String METRIC_SLO_SKIPPED          = "slo.skipped.total";
    public static final String METRIC_SLO_DURATION         = "slo.duration.seconds";
    public static final String METRIC_SLO_WEBHOOK_ENQUEUED = "slo.webhook.enqueued.total";

    public static final String METRIC_PURGE_SUCCESS = "personal.data.purged.total";
    public static final String METRIC_PURGE_FAILED  = "personal.data.purge.failed.total";

    // ── 내부 상태 ────────────────────────────────────────────────────────────

    private final MeterRegistry registry;
    private final Timer sloTimer;

    public SloMetrics(MeterRegistry registry) {
        this.registry = registry;

        // SLO 카운터 사전 등록 (기동 시 시계열 즉시 노출)
        Counter.builder(METRIC_SLO_INITIATE)
                .description("SLO 시작 건수 (feSessionId 유무 무관)")
                .register(registry);
        Counter.builder(METRIC_SLO_COMPLETED)
                .description("SLO feSession 만료 완료 건수")
                .register(registry);
        Counter.builder(METRIC_SLO_SKIPPED)
                .description("feSessionId 없이 SLO 쿠키 제거만 수행한 건수")
                .register(registry);
        Counter.builder(METRIC_SLO_WEBHOOK_ENQUEUED)
                .description("USER_LOGOUT Webhook Outbox 큐 삽입 건수")
                .register(registry);
        Counter.builder(METRIC_PURGE_SUCCESS)
                .description("개인정보 파기 처리 건수")
                .register(registry);
        Counter.builder(METRIC_PURGE_FAILED)
                .description("개인정보 파기 실패 건수")
                .register(registry);

        // SLO 처리 시간 Timer (p50/p95/p99)
        this.sloTimer = Timer.builder(METRIC_SLO_DURATION)
                .description("SLO 전체 처리 시간 (feSession 만료 ~ 오케스트레이션 완료)")
                .publishPercentiles(0.50, 0.95, 0.99)
                .register(registry);
    }

    // ── SLO 카운터 ─────────────────────────────────────────────────────────

    /** SLO 엔드포인트 진입 카운터 */
    public void incrementSloInitiate() {
        registry.counter(METRIC_SLO_INITIATE).increment();
    }

    /** feSession 만료 완료 카운터 */
    public void incrementSloCompleted() {
        registry.counter(METRIC_SLO_COMPLETED).increment();
    }

    /** feSessionId 없어 스킵된 SLO 카운터 */
    public void incrementSloSkipped() {
        registry.counter(METRIC_SLO_SKIPPED).increment();
    }

    /** USER_LOGOUT Webhook Outbox 큐 삽입 카운터 */
    public void incrementWebhookEnqueued() {
        registry.counter(METRIC_SLO_WEBHOOK_ENQUEUED).increment();
    }

    // ── SLO 처리 시간 ──────────────────────────────────────────────────────

    /**
     * SLO 처리 시간 기록
     *
     * @param durationMs 처리 시간 (밀리초)
     */
    public void recordSloDuration(long durationMs) {
        sloTimer.record(Duration.ofMillis(Math.max(0, durationMs)));
    }

    // ── 개인정보 파기 카운터 ────────────────────────────────────────────────

    /**
     * 개인정보 파기 성공 카운터 (회원 1건 단위)
     *
     * @param count 처리 건수
     */
    public void incrementPurgeSuccess(int count) {
        for (int i = 0; i < count; i++) {
            registry.counter(METRIC_PURGE_SUCCESS).increment();
        }
    }

    /**
     * 개인정보 파기 실패 카운터 (회원 1건 단위)
     */
    public void incrementPurgeFailed() {
        registry.counter(METRIC_PURGE_FAILED).increment();
    }
}

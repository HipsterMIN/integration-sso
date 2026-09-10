package io.github.hipstermin.idem.hub.metrics;

import io.github.hipstermin.idem.hub.infrastructure.health.VaultKmsHealthIndicator;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * KMS 가용성 Micrometer Gauge (PR-B1-new — SSO 본질 메트릭)
 *
 * <p><b>설계 결정 — Health Indicator vs Metric 병행</b>:
 * {@link VaultKmsHealthIndicator}가 이미 {@code /actuator/health}로 KMS 상태를 노출하지만
 * 그것은 K8s readinessProbe 전용이다. Prometheus 시계열로 KMS 가용성을 추적하려면
 * Gauge가 별도로 필요하다. 본 클래스는 신규 health 로직을 추가하지 않고
 * {@code VaultKmsHealthIndicator.health()}의 결과를 0/1로 변환만 한다.
 *
 * <p><b>SSO/IM 본질 4가지 중 "개인정보가 안전한가"에 직접 기여하는 메트릭</b>:
 * KMS 다운 = ID 핸드오프 암호화 불가 = SSO 본질 위협. 따라서 본 메트릭은
 * 운영자가 매일 확인해야 하는 3대 지표 중 하나로 docs/SPRINT_B_PLAN.md PR-B1-new에 정의됨.
 *
 * <p><b>노출 메트릭</b>:
 * <pre>
 * onepass_kms_healthy{provider="vault"}  → 1.0 (UP) | 0.0 (DOWN/UNKNOWN)
 * </pre>
 *
 * <p><b>왜 단순한가</b> ({@code OPERATION_INVENTORY.md §8} 자문 체크리스트 통과):
 * 본 메트릭은 신규 health 로직을 0줄 추가한다. {@link VaultKmsHealthIndicator}가
 * 이미 5초 캐시 + Vault Transit API 호출 + 다중 KmsClient 빈 처리를 모두 수행하므로
 * 본 클래스는 그 결과를 단지 Gauge로 노출만 할 뿐이다. 관리 포인트 증가 최소화.
 *
 * <p><b>Prometheus 알람 권장</b> ({@code docs/RUNBOOK_SSO_METRICS.md} 참조):
 * <pre>
 * onepass_kms_healthy == 0   for 1m   → critical (즉시 호출)
 * </pre>
 *
 * <p><b>Gauge 동작</b>: Micrometer는 Gauge 값을 push 방식이 아닌 pull 방식으로 평가한다.
 * Prometheus가 {@code /actuator/prometheus}를 스크래핑할 때마다 {@link #refreshGauge()}가
 * 호출되며, 이 시점에 {@link VaultKmsHealthIndicator#health()}를 평가한다.
 * VaultKmsHealthIndicator 자체 캐시(5s)로 Vault API 부하는 자동 제어된다.
 *
 * @see VaultKmsHealthIndicator
 */
@Slf4j
@Component
public class KmsHealthMetrics {

    private final VaultKmsHealthIndicator kmsHealth;
    private final MeterRegistry meterRegistry;

    /** Gauge가 참조하는 상태 (UP=1, DOWN/UNKNOWN=0) — Micrometer 표준 패턴. */
    private final AtomicInteger healthy = new AtomicInteger(0);

    public KmsHealthMetrics(VaultKmsHealthIndicator kmsHealth, MeterRegistry meterRegistry) {
        this.kmsHealth = kmsHealth;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void registerGauge() {
        meterRegistry.gauge(
                "onepass.kms.healthy",
                healthy,
                this::evaluate);
        log.info("[KmsHealthMetrics] Gauge 'onepass.kms.healthy' 등록 완료");
    }

    /**
     * Gauge 평가 함수 — Prometheus 스크래핑 시점에 호출됨.
     *
     * <p>{@code VaultKmsHealthIndicator.health()}는 내부적으로 5초 캐시를 사용하므로
     * 스크래핑 주기(보통 15~30s)에 무관하게 Vault API 부하는 안전하다.
     */
    private double evaluate(AtomicInteger ref) {
        try {
            Status status = kmsHealth.health().getStatus();
            int value = Status.UP.equals(status) ? 1 : 0;
            ref.set(value);
            return value;
        } catch (Throwable t) {
            // 평가 중 예외 — 0(DOWN)으로 안전 처리 + 로그
            log.warn("[KmsHealthMetrics] Gauge 평가 실패 — DOWN 처리: {}", t.getMessage());
            ref.set(0);
            return 0;
        }
    }
}

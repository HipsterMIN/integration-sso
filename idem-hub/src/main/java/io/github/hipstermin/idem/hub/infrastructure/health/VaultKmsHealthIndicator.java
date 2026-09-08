package io.github.hipstermin.idem.hub.infrastructure.health;

import io.github.hipstermin.idem.hub.crypto.kms.KmsClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * KMS(Vault/NHN/Local) 헬스 인디케이터 (PR-A4)
 *
 * <p><b>역할</b>:
 * Spring Boot Actuator의 {@code /actuator/health} 엔드포인트에
 * 현재 활성화된 {@link KmsClient} 구현체의 가용성을 노출한다.
 * Vault Transit API({@code GET /v1/sys/health}) 또는
 * NHN SKM/Local KMS의 {@code isHealthy()} 결과를 그대로 위임한다.
 *
 * <p><b>출력 예시</b>:
 * <pre>
 * UP 시:
 *   "kms": {
 *     "status": "UP",
 *     "details": {
 *       "provider": "vault",
 *       "lastCheckAt": "2025-05-21T08:30:00Z",
 *       "lastSuccessAt": "2025-05-21T08:30:00Z"
 *     }
 *   }
 *
 * DOWN 시:
 *   "kms": {
 *     "status": "DOWN",
 *     "details": {
 *       "provider": "vault",
 *       "reason": "isHealthy() returned false",
 *       "lastCheckAt": "2025-05-21T08:30:00Z",
 *       "lastSuccessAt": "2025-05-21T08:29:30Z"
 *     }
 *   }
 * </pre>
 *
 * <p><b>K8s Readiness Probe 연동</b>:
 * 본 Indicator는 기본적으로 {@code /actuator/health}(전체)에 노출되며,
 * {@code management.endpoint.health.group.readiness.include=kms,...}
 * 설정 시 Readiness 그룹에 포함된다.
 *
 * <p><b>성능 고려</b>:
 * <ul>
 *   <li>Vault: HTTP 호출 (3s timeout) — 매 probe마다 호출 시 부하 우려</li>
 *   <li>해결: <b>캐시 결과 재사용</b> — 직전 호출이 {@code cacheTtl} 이내면 캐시값 반환</li>
 *   <li>K8s readinessProbe periodSeconds=10s + cacheTtl=5s ⇒ 매 probe에서 실제 호출</li>
 * </ul>
 *
 * <p><b>장애 모드</b>:
 * KMS DOWN 상태에서도 ido 애플리케이션은 기동되어야 한다 (graceful degradation).
 * 따라서 본 Indicator는 {@code /actuator/health/liveness}에는 포함되지 않으며,
 * Readiness 그룹(트래픽 차단)에만 포함되도록 운영 설정해야 한다.
 *
 * @see KmsClient
 * @see io.github.hipstermin.idem.hub.crypto.kms.VaultKmsClient
 * @see io.github.hipstermin.idem.hub.crypto.kms.LocalKmsClient
 */
@Slf4j
@Component("kms")   // Bean 이름 = health 컴포넌트 이름 (/actuator/health 응답의 "kms" 키)
public class VaultKmsHealthIndicator implements HealthIndicator {

    /**
     * 다중 KmsClient 빈을 List로 주입 — Spring이 활성화한 모든 구현체를 받는다.
     *
     * <p><b>설계 근거</b>:
     * 정상 설정에서는 1개의 KmsClient만 활성화되지만,
     * {@link io.github.hipstermin.idem.hub.crypto.kms.AnyIdKmsClient}는 별도 prefix(ido.anyid.kms)를 사용하므로
     * {@link io.github.hipstermin.idem.hub.crypto.kms.LocalKmsClient}와 동시에 활성화될 수 있다.
     * 단일 KmsClient 주입은 NoUniqueBeanDefinitionException을 유발하므로
     * List로 받아 첫 번째(우선순위 — 일반적으로 비-AnyId)를 사용한다.
     *
     * <p>전부 비활성(빈 리스트)이라면 UNKNOWN 상태로 안전 처리한다.
     */
    private final List<KmsClient> kmsClients;

    /** 헬스체크 결과 캐시 TTL (밀리초). 0 이하면 캐시 비활성화. */
    @Value("${ido.kms.health.cache-ttl-ms:5000}")
    private long cacheTtlMs;

    /** 직전 호출 결과 캐시 */
    private final AtomicReference<CachedResult> cache = new AtomicReference<>(null);

    public VaultKmsHealthIndicator(List<KmsClient> kmsClients) {
        this.kmsClients = kmsClients;
    }

    @Override
    public Health health() {
        // KmsClient 빈이 하나도 없는 경우 — 설정 누락 상황
        if (kmsClients == null || kmsClients.isEmpty()) {
            return Health.unknown()
                    .withDetail("reason", "No KmsClient bean is active. " +
                            "Check ido.kms.enabled/provider configuration.")
                    .build();
        }

        CachedResult cached = cache.get();
        Instant now = Instant.now();

        // 캐시 유효: 직전 호출 후 cacheTtlMs 이내
        if (cached != null && cacheTtlMs > 0
                && Duration.between(cached.checkedAt, now).toMillis() < cacheTtlMs) {
            return cached.health;
        }

        // 신규 호출
        return performHealthCheck(now, cached);
    }

    /**
     * 우선 사용할 KmsClient 선택.
     *
     * <p>여러 KMS 구현체가 동시에 등록된 경우 (예: LocalKmsClient + AnyIdKmsClient),
     * AnyIdKmsClient(보조 — AnyID 전용)가 아닌 일반 KMS(Vault/NHN/Local/NoOp)를 우선한다.
     */
    private KmsClient primaryKms() {
        // "anyid"로 끝나는 provider는 후순위
        return kmsClients.stream()
                .filter(c -> {
                    try {
                        String name = c.providerName();
                        return name == null || !name.toLowerCase().contains("anyid");
                    } catch (Throwable t) {
                        return true;
                    }
                })
                .findFirst()
                .orElse(kmsClients.get(0));
    }

    private Health performHealthCheck(Instant now, CachedResult prev) {
        KmsClient kmsClient = primaryKms();
        String provider = safeProviderName(kmsClient);
        boolean healthy;
        Throwable error = null;

        try {
            healthy = kmsClient.isHealthy();
        } catch (Throwable t) {
            healthy = false;
            error = t;
            log.warn("[KMS-Health] isHealthy() 호출 예외: provider={} cause={}",
                    provider, t.getMessage());
        }

        Instant lastSuccessAt = (healthy)
                ? now
                : (prev != null ? prev.lastSuccessAt : null);

        Health.Builder builder = healthy ? Health.up() : Health.down();
        builder.withDetail("provider", provider)
               .withDetail("lastCheckAt", now.toString());

        if (lastSuccessAt != null) {
            builder.withDetail("lastSuccessAt", lastSuccessAt.toString());
        }

        if (!healthy) {
            builder.withDetail("reason",
                    error != null
                        ? error.getClass().getSimpleName() + ": " + error.getMessage()
                        : "isHealthy() returned false");
        }

        Health result = builder.build();
        cache.set(new CachedResult(result, now, lastSuccessAt));
        return result;
    }

    private String safeProviderName(KmsClient kmsClient) {
        try {
            return kmsClient.providerName();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** 캐시 엔트리 (불변) */
    private record CachedResult(Health health, Instant checkedAt, Instant lastSuccessAt) {}
}

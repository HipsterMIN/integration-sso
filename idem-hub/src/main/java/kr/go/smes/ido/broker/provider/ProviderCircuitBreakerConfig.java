package kr.go.smes.ido.broker.provider;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * provider_code 단위 Resilience4j 회로차단기 동적 구성 (P1 — GAP 마감)
 *
 * <p><b>설계</b>:
 * <ol>
 *   <li>Resilience4j {@link CircuitBreakerRegistry}에 provider_code 별 인스턴스 등록</li>
 *   <li>인스턴스 설정은 {@code ido.provider_circuit_config} DB 테이블에서 로드 (V10 신규)</li>
 *   <li>DB 설정 없으면 {@code application.yml} 의 {@code keycloak-client} 기본값 상속</li>
 *   <li>인스턴스는 최초 요청 시 lazy 생성 + {@link ConcurrentHashMap} 메모리 캐시</li>
 * </ol>
 *
 * <p><b>사용 예시</b>:
 * <pre>{@code
 * CircuitBreaker cb = providerCircuitBreakerConfig.getOrCreate("KAKAO_OIDC");
 * return CircuitBreaker.decorateSupplier(cb, () -> callExternalIdp()).get();
 * }</pre>
 *
 * <p>Resilience4j 의존성: {@code io.github.resilience4j:resilience4j-spring-boot3}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderCircuitBreakerConfig {

    /** 기본 회로차단기 이름 (application.yml keycloak-client 설정 상속) */
    public static final String DEFAULT_CB_NAME = "keycloak-client";

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final JdbcTemplate           jdbcTemplate;

    /** provider_code → CircuitBreaker 캐시 */
    private final Map<String, CircuitBreaker> cbCache = new ConcurrentHashMap<>();

    // ────────────────────────────────────────────────────────────────────
    // 공개 API
    // ────────────────────────────────────────────────────────────────────

    /**
     * provider_code 에 해당하는 CircuitBreaker 반환 (없으면 생성)
     *
     * @param providerCode 인증 수단 코드 (예: KAKAO_OIDC, PASS)
     * @return CircuitBreaker 인스턴스
     */
    public CircuitBreaker getOrCreate(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            return circuitBreakerRegistry.circuitBreaker(DEFAULT_CB_NAME);
        }
        return cbCache.computeIfAbsent(providerCode, this::createForProvider);
    }

    /**
     * 특정 provider의 CircuitBreaker 상태 조회
     */
    public CircuitBreaker.State getState(String providerCode) {
        return getOrCreate(providerCode).getState();
    }

    /**
     * 모든 등록된 CircuitBreaker 상태 로그 출력 (모니터링용)
     */
    public void logAllStates() {
        cbCache.forEach((code, cb) ->
                log.info("[ProviderCB] provider={} state={} failureRate={:.1f}%",
                        code, cb.getState(), cb.getMetrics().getFailureRate()));
    }

    // ────────────────────────────────────────────────────────────────────
    // 내부 구현
    // ────────────────────────────────────────────────────────────────────

    private CircuitBreaker createForProvider(String providerCode) {
        // 1. DB에서 provider_circuit_config 조회
        CircuitBreakerConfig dbConfig = loadFromDb(providerCode);

        // 2. Registry에 등록 (CB 이름: "provider-{providerCode}")
        String cbName = "provider-" + providerCode.toLowerCase();
        CircuitBreaker cb;
        if (dbConfig != null) {
            cb = circuitBreakerRegistry.circuitBreaker(cbName, dbConfig);
            log.info("[ProviderCB] CircuitBreaker 생성 (DB 설정): provider={} cbName={}", providerCode, cbName);
        } else {
            // DB 설정 없음 → keycloak-client 기본 설정 기반 생성
            cb = circuitBreakerRegistry.circuitBreaker(cbName,
                    circuitBreakerRegistry.getDefaultConfig());
            log.info("[ProviderCB] CircuitBreaker 생성 (기본 설정): provider={} cbName={}", providerCode, cbName);
        }
        return cb;
    }

    private CircuitBreakerConfig loadFromDb(String providerCode) {
        try {
            return jdbcTemplate.query("""
                    SELECT sliding_window_size, failure_rate_threshold,
                           slow_call_rate_threshold, slow_call_duration_threshold_ms,
                           wait_duration_in_open_ms, permitted_calls_in_half_open,
                           minimum_number_of_calls, enabled
                    FROM ido.provider_circuit_config
                    WHERE provider_code = ? AND enabled = TRUE
                    """,
                    rs -> {
                        if (rs.next()) {
                            return CircuitBreakerConfig.custom()
                                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                                    .slidingWindowSize(rs.getInt("sliding_window_size"))
                                    .failureRateThreshold(rs.getFloat("failure_rate_threshold"))
                                    .slowCallRateThreshold(rs.getFloat("slow_call_rate_threshold"))
                                    .slowCallDurationThreshold(Duration.ofMillis(
                                            rs.getLong("slow_call_duration_threshold_ms")))
                                    .waitDurationInOpenState(Duration.ofMillis(
                                            rs.getLong("wait_duration_in_open_ms")))
                                    .permittedNumberOfCallsInHalfOpenState(
                                            rs.getInt("permitted_calls_in_half_open"))
                                    .minimumNumberOfCalls(rs.getInt("minimum_number_of_calls"))
                                    .build();
                        }
                        return null;  // DB에 없으면 기본값 사용
                    },
                    providerCode
            );
        } catch (Exception e) {
            log.warn("[ProviderCB] provider_circuit_config 조회 실패 — 기본값 사용: provider={} err={}",
                    providerCode, e.getMessage());
            return null;
        }
    }

    /**
     * null config → keycloak-client 기본 설정 상속 후 CircuitBreaker 생성
     * (Resilience4j Registry의 기본 인스턴스에서 설정 복사)
     */
    static {
        // Resilience4j Registry가 존재하지 않는 경우 방어 코드 — Spring 컨텍스트에서 처리
    }
}

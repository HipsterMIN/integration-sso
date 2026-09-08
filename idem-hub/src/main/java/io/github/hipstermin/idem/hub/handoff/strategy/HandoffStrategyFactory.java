package io.github.hipstermin.idem.hub.handoff.strategy;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * HandoffStrategy 팩토리 — integration_type → 전략 매핑
 *
 * <p>Spring이 {@link HandoffStrategy} 구현체를 자동 수집하여 맵으로 관리.
 * 새 연동 유형 추가 시 {@link HandoffStrategy} 구현체만 추가하면 됨 (OCP 준수).
 */
@Slf4j
@Component
public class HandoffStrategyFactory {

    private final Map<String, HandoffStrategy> strategyMap;
    private final HandoffStrategy              defaultStrategy;

    public HandoffStrategyFactory(List<HandoffStrategy> strategies) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(
                        HandoffStrategy::getIntegrationType,
                        Function.identity()
                ));
        this.defaultStrategy = strategyMap.getOrDefault("DIRECT",
                strategies.stream().findFirst().orElseThrow(
                        () -> new IllegalStateException("HandoffStrategy 구현체가 없습니다")));
        log.info("[StrategyFactory] 등록된 HandoffStrategy: {}", strategyMap.keySet());
    }

    /**
     * integration_type 으로 전략 조회
     *
     * @param integrationType DIRECT / APACHE_GATE / BRIDGE / INTERNAL_SSO
     * @return 해당 전략 (미등록이면 DIRECT fallback)
     */
    public HandoffStrategy getStrategy(String integrationType) {
        if (integrationType == null) return defaultStrategy;
        HandoffStrategy strategy = strategyMap.get(integrationType.toUpperCase());
        if (strategy == null) {
            log.warn("[StrategyFactory] 알 수 없는 integration_type '{}' — DIRECT fallback", integrationType);
            return defaultStrategy;
        }
        return strategy;
    }
}

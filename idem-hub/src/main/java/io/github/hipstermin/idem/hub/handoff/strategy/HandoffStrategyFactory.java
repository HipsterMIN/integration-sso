package io.github.hipstermin.idem.hub.handoff.strategy;

import io.github.hipstermin.idem.hub.domain.IntegrationType;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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

    private final Map<IntegrationType, HandoffStrategy> strategyMap;

    /**
     * 모든 {@link IntegrationType} 에 전략이 하나씩 있어야 기동한다 — S1 에서 "미지 값 DIRECT 폴백" 을 없앴다.
     * 유형이 늘면(S6 프로토콜 확장) 전략도 함께 추가해야 하며, 누락은 배포 전에 드러난다.
     */
    public HandoffStrategyFactory(List<HandoffStrategy> strategies) {
        Map<IntegrationType, HandoffStrategy> map = new EnumMap<>(IntegrationType.class);
        for (HandoffStrategy s : strategies) {
            if (map.putIfAbsent(s.getIntegrationType(), s) != null) {
                throw new IllegalStateException("HandoffStrategy 중복 등록: " + s.getIntegrationType());
            }
        }
        List<IntegrationType> missing = Arrays.stream(IntegrationType.values())
                .filter(t -> !map.containsKey(t)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("HandoffStrategy 미등록 연동 유형: " + missing
                    + " — 모든 IntegrationType 에 전략이 있어야 기동한다");
        }
        this.strategyMap = Collections.unmodifiableMap(map);
        log.info("[StrategyFactory] 등록된 HandoffStrategy: {}", strategyMap.keySet());
    }

    /** null 은 {@link IntegrationType#DEFAULT}. 그 외 유형은 생성 시점에 전부 검증됐으므로 항상 존재한다. */
    public HandoffStrategy getStrategy(IntegrationType integrationType) {
        return strategyMap.get(integrationType == null ? IntegrationType.DEFAULT : integrationType);
    }
}

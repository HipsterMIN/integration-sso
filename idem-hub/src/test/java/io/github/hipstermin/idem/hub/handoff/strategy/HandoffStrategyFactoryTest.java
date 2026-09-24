package io.github.hipstermin.idem.hub.handoff.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.domain.HandoffTicket;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S1 범용화 — 연동 유형이 문자열에서 {@link IntegrationType} 으로 바뀌면서 팩토리가 지켜야 할 계약.
 *
 * <ul>
 *   <li>모든 열거형 값에 전략이 있어야 기동한다 (누락 시 fail-fast)</li>
 *   <li>같은 유형의 전략이 둘이면 기동 실패</li>
 *   <li>null 유형은 DEFAULT(DIRECT) 전략</li>
 * </ul>
 */
@DisplayName("HandoffStrategyFactory — IntegrationType 기반 전략 등록·조회")
class HandoffStrategyFactoryTest {

    private static HandoffStrategy stub(IntegrationType type) {
        return new HandoffStrategy() {
            @Override public IntegrationType getIntegrationType() { return type; }
            @Override public void postIssue(HandoffTicket t, HandoffPayload p, String cid) { }
        };
    }

    private static List<HandoffStrategy> allStrategies() {
        return Arrays.stream(IntegrationType.values()).map(HandoffStrategyFactoryTest::stub).toList();
    }

    @Test
    @DisplayName("모든 IntegrationType 에 전략이 등록되면 유형별로 정확히 조회된다")
    void registersEveryType() {
        HandoffStrategyFactory factory = new HandoffStrategyFactory(allStrategies());

        for (IntegrationType type : IntegrationType.values()) {
            assertThat(factory.getStrategy(type).getIntegrationType()).isEqualTo(type);
        }
    }

    @Test
    @DisplayName("null 유형은 DEFAULT(DIRECT) 전략을 돌려준다")
    void nullType_fallsBackToDefault() {
        HandoffStrategyFactory factory = new HandoffStrategyFactory(allStrategies());

        assertThat(factory.getStrategy(null).getIntegrationType()).isEqualTo(IntegrationType.DEFAULT);
    }

    @Test
    @DisplayName("전략이 누락된 유형이 있으면 기동을 거부한다 (조용한 DIRECT 폴백 금지)")
    void missingStrategy_failsFast() {
        List<HandoffStrategy> withoutBridge = allStrategies().stream()
                .filter(s -> s.getIntegrationType() != IntegrationType.BRIDGE)
                .toList();

        assertThatThrownBy(() -> new HandoffStrategyFactory(withoutBridge))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BRIDGE");
    }

    @Test
    @DisplayName("같은 유형의 전략이 둘이면 기동을 거부한다")
    void duplicateStrategy_failsFast() {
        List<HandoffStrategy> dup = new java.util.ArrayList<>(allStrategies());
        dup.add(stub(IntegrationType.DIRECT));

        assertThatThrownBy(() -> new HandoffStrategyFactory(dup))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DIRECT");
    }

    @Test
    @DisplayName("IntegrationType.from — 대소문자·공백 허용, 미지 값·공백은 거부")
    void integrationTypeParsing() {
        assertThat(IntegrationType.from(" apache_gate ")).isEqualTo(IntegrationType.APACHE_GATE);
        assertThat(IntegrationType.fromOrDefault(null)).isEqualTo(IntegrationType.DIRECT);
        assertThat(IntegrationType.fromOrDefault("  ")).isEqualTo(IntegrationType.DIRECT);
        assertThatThrownBy(() -> IntegrationType.from("WEBHOOK"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WEBHOOK")
                .hasMessageContaining("DIRECT, BRIDGE, APACHE_GATE, INTERNAL_SSO, OIDC_RP");
        assertThatThrownBy(() -> IntegrationType.from(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

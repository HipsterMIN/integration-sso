package kr.go.smes.agent.weaving;

import kr.go.smes.agent.config.AgentConfig;
import kr.go.smes.agent.was.WasType;
import kr.go.smes.agent.weaving.jeus.Jeus6WeavingStrategy;
import kr.go.smes.agent.weaving.jeus.Jeus7PlusWeavingStrategy;
import kr.go.smes.agent.weaving.jeus.Jeus8_5PlusWeavingStrategy;
import kr.go.smes.agent.weaving.jeus.JeusLegacyWeavingStrategy;

import java.io.PrintStream;

/**
 * {@link WasType}과 {@link AgentConfig}를 기반으로 {@link WeavingStrategy}를 선택하는 팩토리.
 *
 * <h2>선택 규칙</h2>
 * <pre>
 *  WasType         위빙 전략 구현체                          위빙 엔진
 *  ─────────────────────────────────────────────────────────────────────────────
 *  JEUS_LEGACY     JeusLegacyWeavingStrategy               Javassist (JDK 1.5 호환)
 *  JEUS_6          Jeus6WeavingStrategy                    Javassist (JDK 1.5~1.7)
 *  JEUS_7          Jeus7PlusWeavingStrategy                JDK 8+: byte-buddy,
 *                                                          JDK 7-: Javassist
 *  JEUS_8          Jeus7PlusWeavingStrategy                JDK 8+: byte-buddy,
 *                                                          JDK 7: Javassist
 *  JEUS_8_5        Jeus8_5PlusWeavingStrategy              byte-buddy (javax 전용)
 *  JEUS_9_PLUS     Jeus8_5PlusWeavingStrategy              byte-buddy (javax+jakarta)
 *  TOMCAT          TomcatWeavingStrategy                   byte-buddy (Catalina Valve)
 *  JBOSS           GenericFilterWeavingStrategy            byte-buddy (javax+jakarta Filter)
 *  WEBLOGIC        GenericFilterWeavingStrategy            byte-buddy (javax+jakarta Filter)
 *  UNDERTOW        GenericFilterWeavingStrategy            byte-buddy (javax+jakarta Filter)
 *  JETTY           GenericFilterWeavingStrategy            byte-buddy (javax+jakarta Filter)
 *  UNKNOWN         GenericFilterWeavingStrategy            byte-buddy (Fallback)
 * </pre>
 *
 * <h2>JEUS 우선순위</h2>
 * <p>한국 공공기관 특화 Agent이므로 JEUS 전용 전략이 항상 우선 매핑된다.
 * JEUS 계열을 가장 먼저 switch case로 처리하여 실수로 Fallback 되는 것을 방지한다.
 *
 * <h2>엔진 선택 위임</h2>
 * <p>JEUS_7/8의 경우 실제 위빙 엔진(byte-buddy vs Javassist) 선택은
 * {@link Jeus7PlusWeavingStrategy} 내부에서 런타임 JDK 버전을 확인한 후 결정한다.
 * Factory는 WasType → 전략 클래스 매핑만 담당한다.
 */
public final class WeavingStrategyFactory {

    private WeavingStrategyFactory() {
        // 유틸리티 클래스
    }

    /**
     * WasType에 따라 최적 위빙 전략을 생성하여 반환한다.
     *
     * @param wasType 감지된 WAS 유형 ({@link WasType})
     * @param config  Agent 설정 ({@link AgentConfig})
     * @param log     로그 출력 스트림 (null 허용 시 NPE 발생 — 호출자가 not-null 보장)
     * @return 생성된 {@link WeavingStrategy} 구현체 (null 반환 없음)
     */
    public static WeavingStrategy create(WasType wasType, AgentConfig config, PrintStream log) {
        WeavingStrategy strategy;

        switch (wasType) {

            // ── JEUS 버전별 전용 전략 ─────────────────────────────────────────────

            case JEUS_LEGACY:
                // JEUS 4/5: JDK 1.4~1.5, Javassist 위빙 (byte-buddy 사용 불가)
                strategy = new JeusLegacyWeavingStrategy(config, log);
                break;

            case JEUS_6:
                // JEUS 6: JDK 1.5~1.7, Javassist 통일 정책
                strategy = new Jeus6WeavingStrategy(config, log);
                break;

            case JEUS_7:
            case JEUS_8:
                // JEUS 7/8: JDK 8+이면 byte-buddy, JDK 7이면 Javassist (내부 자동 분기)
                strategy = new Jeus7PlusWeavingStrategy(wasType, config, log);
                break;

            case JEUS_8_5:
            case JEUS_9_PLUS:
                // JEUS 8.5/9/21: byte-buddy, javax+jakarta 이중 지원
                strategy = new Jeus8_5PlusWeavingStrategy(wasType, config, log);
                break;

            // ── 기존 WAS 전략 ─────────────────────────────────────────────────────

            case TOMCAT:
                strategy = new TomcatWeavingStrategy(config, log);
                break;

            case JBOSS:
            case WEBLOGIC:
            case UNDERTOW:
            case JETTY:
            case UNKNOWN:
            default:
                strategy = new GenericFilterWeavingStrategy(config, log);
                break;
        }

        log.println("[WeavingFactory] WAS=" + wasType + " → 전략=" + strategy.name());
        return strategy;
    }
}

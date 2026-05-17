package kr.go.smes.agent.weaving;

import kr.go.smes.agent.config.AgentConfig;
import kr.go.smes.agent.was.WasType;

import java.io.PrintStream;

/**
 * {@link WasType}과 {@link AgentConfig}를 기반으로 {@link WeavingStrategy}를 선택하는 팩토리.
 *
 * <h2>선택 규칙</h2>
 * <pre>
 *   TOMCAT   → TomcatWeavingStrategy   (Catalina Valve 체인)
 *   나머지   → GenericFilterWeavingStrategy  (javax.servlet.Filter)
 * </pre>
 *
 * <p>WebLogic / JBoss / Undertow / Jetty는 모두 Servlet API를 표준으로 지원하므로
 * Generic Filter 전략으로 커버된다. WAS 고유 확장(WebLogic ExecuteThread 등)은
 * 향후 전용 전략 추가로 대응할 수 있다.
 */
public final class WeavingStrategyFactory {

    private WeavingStrategyFactory() {
        // 유틸리티 클래스
    }

    /**
     * @param wasType 감지된 WAS 유형
     * @param config  Agent 설정
     * @param log     로그 출력 스트림
     * @return 선택된 {@link WeavingStrategy} 구현체
     */
    public static WeavingStrategy create(WasType wasType, AgentConfig config, PrintStream log) {
        WeavingStrategy strategy;

        switch (wasType) {
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

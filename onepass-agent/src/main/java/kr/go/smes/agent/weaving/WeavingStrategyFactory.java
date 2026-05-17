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
 * <h2>전체 선택 규칙</h2>
 * <pre>
 *  WasType              위빙 전략 구현체                        위빙 엔진
 *  ──────────────────────────────────────────────────────────────────────────────────
 *  ── JEUS 계열 ──
 *  JEUS_LEGACY          JeusLegacyWeavingStrategy               Javassist (JDK 1.5)
 *  JEUS_6               Jeus6WeavingStrategy                    Javassist (JDK 1.5~1.7)
 *  JEUS_7               Jeus7PlusWeavingStrategy                JDK 8+: BB, JDK 7-: Javassist
 *  JEUS_8               Jeus7PlusWeavingStrategy                byte-buddy (Javassist fallback)
 *  JEUS_8_5             Jeus8_5PlusWeavingStrategy              byte-buddy (javax)
 *  JEUS_9_PLUS          Jeus8_5PlusWeavingStrategy              byte-buddy (javax+jakarta)
 *  ── Tomcat 계열 ──
 *  TOMCAT_LEGACY        TomcatVersionedWeavingStrategy          Javassist (JDK 5~6)
 *  TOMCAT_7             TomcatVersionedWeavingStrategy          Javassist(JDK7)/BB(JDK8+)
 *  TOMCAT_8             TomcatVersionedWeavingStrategy          byte-buddy Valve + javax Filter
 *  TOMCAT_9             TomcatVersionedWeavingStrategy          byte-buddy Valve + javax Filter
 *  TOMCAT_10_PLUS       TomcatVersionedWeavingStrategy          byte-buddy jakarta Filter 전용
 *  TOMCAT               TomcatVersionedWeavingStrategy          byte-buddy Valve + 이중 Filter
 *  ── JBoss/WildFly 계열 ──
 *  JBOSS_LEGACY         LegacyJavassistWeavingStrategy          Javassist (JDK 6~7)
 *  JBOSS                GenericFilterWeavingStrategy            byte-buddy (javax+jakarta Filter)
 *  WILDFLY              GenericFilterWeavingStrategy            byte-buddy (jakarta Filter 위주)
 *  ── WebLogic 계열 ──
 *  WEBLOGIC_LEGACY      LegacyJavassistWeavingStrategy          Javassist (JDK 6~7)
 *  WEBLOGIC             GenericFilterWeavingStrategy            byte-buddy (javax+jakarta Filter)
 *  ── WebSphere 계열 ──
 *  WEBSPHERE_LEGACY     LegacyJavassistWeavingStrategy          Javassist (JDK 6~7)
 *  WEBSPHERE            GenericFilterWeavingStrategy            byte-buddy (javax+jakarta Filter)
 *  ── GlassFish/Payara 계열 ──
 *  GLASSFISH            GenericFilterWeavingStrategy            byte-buddy (javax Filter)
 *  GLASSFISH_JAKARTA    GenericFilterWeavingStrategy            byte-buddy (jakarta Filter)
 *  ── Resin ──
 *  RESIN                ResinWeavingStrategy → GenericFilter    byte-buddy / Javassist 분기
 *  ── Jetty 계열 ──
 *  JETTY_LEGACY         LegacyJavassistWeavingStrategy          Javassist (JDK 7)
 *  JETTY                GenericFilterWeavingStrategy            byte-buddy (javax+jakarta)
 *  JETTY_JAKARTA        GenericFilterWeavingStrategy            byte-buddy (jakarta 위주)
 *  ── Undertow ──
 *  UNDERTOW             GenericFilterWeavingStrategy            byte-buddy (javax+jakarta Filter)
 *  ── Fallback ──
 *  UNKNOWN              GenericFilterWeavingStrategy            byte-buddy (Fallback)
 * </pre>
 *
 * <h2>설계 원칙</h2>
 * <ul>
 *   <li>JEUS 계열 최우선 처리 (한국 공공기관 특화)</li>
 *   <li>Tomcat 버전별 세분화 처리</li>
 *   <li>레거시 WAS(JDK 6~7): LegacyJavassistWeavingStrategy 공통 사용</li>
 *   <li>현대 WAS(JDK 8+): GenericFilterWeavingStrategy 공통 사용</li>
 *   <li>Factory는 WasType → 전략 매핑만 담당, 엔진 선택은 전략 내부에서</li>
 * </ul>
 */
public final class WeavingStrategyFactory {

    private WeavingStrategyFactory() {}

    /**
     * WasType에 따라 최적 위빙 전략을 생성하여 반환한다.
     *
     * @param wasType 감지된 WAS 유형 ({@link WasType})
     * @param config  Agent 설정 ({@link AgentConfig})
     * @param log     로그 출력 스트림
     * @return 생성된 {@link WeavingStrategy} 구현체 (null 반환 없음)
     */
    public static WeavingStrategy create(WasType wasType, AgentConfig config, PrintStream log) {
        WeavingStrategy strategy;

        switch (wasType) {

            // ── JEUS 버전별 전용 전략 ─────────────────────────────────────────────

            case JEUS_LEGACY:
                strategy = new JeusLegacyWeavingStrategy(config, log);
                break;

            case JEUS_6:
                strategy = new Jeus6WeavingStrategy(config, log);
                break;

            case JEUS_7:
            case JEUS_8:
                strategy = new Jeus7PlusWeavingStrategy(wasType, config, log);
                break;

            case JEUS_8_5:
            case JEUS_9_PLUS:
                strategy = new Jeus8_5PlusWeavingStrategy(wasType, config, log);
                break;

            // ── Tomcat 버전별 전용 전략 ───────────────────────────────────────────

            case TOMCAT_LEGACY:
            case TOMCAT_7:
            case TOMCAT_8:
            case TOMCAT_9:
            case TOMCAT_10_PLUS:
            case TOMCAT:
                // TomcatVersionedWeavingStrategy가 내부에서 wasType에 따라 분기
                strategy = new TomcatVersionedWeavingStrategy(wasType, config, log);
                break;

            // ── JBoss / WildFly ───────────────────────────────────────────────────

            case JBOSS_LEGACY:
                // JDK 6~7 레거시 → Javassist 공통 전략
                strategy = new LegacyJavassistWeavingStrategy(wasType, config, log);
                break;

            case JBOSS:
            case WILDFLY:
                // JDK 8+ → GenericFilter (javax + jakarta 이중)
                strategy = new GenericFilterWeavingStrategy(config, log);
                break;

            // ── WebLogic ──────────────────────────────────────────────────────────

            case WEBLOGIC_LEGACY:
                strategy = new LegacyJavassistWeavingStrategy(wasType, config, log);
                break;

            case WEBLOGIC:
                strategy = new GenericFilterWeavingStrategy(config, log);
                break;

            // ── IBM WebSphere ─────────────────────────────────────────────────────

            case WEBSPHERE_LEGACY:
                strategy = new LegacyJavassistWeavingStrategy(wasType, config, log);
                break;

            case WEBSPHERE:
                strategy = new GenericFilterWeavingStrategy(config, log);
                break;

            // ── GlassFish / Payara ────────────────────────────────────────────────

            case GLASSFISH:
            case GLASSFISH_JAKARTA:
                // GlassFish는 Grizzly 기반 → GenericFilter가 안정적
                strategy = new GenericFilterWeavingStrategy(config, log);
                break;

            // ── Caucho Resin ──────────────────────────────────────────────────────

            case RESIN:
                // Resin은 JDK 버전에 따라 분기 — LegacyJavassist가 fallback 포함
                strategy = new LegacyJavassistWeavingStrategy(wasType, config, log);
                break;

            // ── Jetty 계열 ────────────────────────────────────────────────────────

            case JETTY_LEGACY:
                strategy = new LegacyJavassistWeavingStrategy(wasType, config, log);
                break;

            case JETTY:
            case JETTY_JAKARTA:
                strategy = new GenericFilterWeavingStrategy(config, log);
                break;

            // ── Undertow (Standalone) ─────────────────────────────────────────────

            case UNDERTOW:
                strategy = new GenericFilterWeavingStrategy(config, log);
                break;

            // ── Fallback ──────────────────────────────────────────────────────────

            case UNKNOWN:
            default:
                log.println("[WeavingFactory] WAS 미감지 → GenericFilter Fallback");
                log.println("[WeavingFactory] javax/jakarta 양쪽 위빙으로 대부분 WAS 대응");
                log.println("[WeavingFactory] 수동 지정: -Donepass.was.type=<WAS_TYPE>");
                strategy = new GenericFilterWeavingStrategy(config, log);
                break;
        }

        log.println("[WeavingFactory] WAS=" + wasType + " → 전략=" + strategy.name());
        return strategy;
    }
}

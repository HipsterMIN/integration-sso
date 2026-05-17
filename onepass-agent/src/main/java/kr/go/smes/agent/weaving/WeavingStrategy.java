package kr.go.smes.agent.weaving;

import java.lang.instrument.Instrumentation;

/**
 * WAS별 바이트코드 위빙 전략 인터페이스.
 *
 * <p>Strategy 패턴으로 WAS 유형에 따른 위빙 포인트를 추상화한다.
 * {@link WeavingStrategyFactory}가 {@link kr.go.smes.agent.was.WasType}에 따라
 * 적절한 구현체를 선택하고, {@link kr.go.smes.agent.core.OnePassAgentMain}이
 * {@code install(inst)} 를 한 번 호출한다.
 *
 * <h2>구현 목록</h2>
 * <pre>
 *  WasType       WeavingStrategy 구현체
 *  ─────────────────────────────────────────────────────────────────
 *  TOMCAT        TomcatWeavingStrategy   ← Catalina Valve 체인 위빙
 *  JBOSS         GenericFilterWeavingStrategy (JBoss도 Servlet 표준 경유)
 *  WEBLOGIC      GenericFilterWeavingStrategy
 *  UNDERTOW      GenericFilterWeavingStrategy
 *  JETTY         GenericFilterWeavingStrategy
 *  UNKNOWN       GenericFilterWeavingStrategy (Fallback)
 * </pre>
 *
 * <h2>스레드 안전성 요구사항</h2>
 * {@code install()}은 premain 단계에서 단 1회 호출된다.
 * 구현체는 install() 내부에서 Instrumentation에 ClassFileTransformer를 등록한다.
 */
public interface WeavingStrategy {

    /**
     * 위빙 전략을 JVM에 설치한다.
     *
     * <p>byte-buddy {@code AgentBuilder}를 통해 {@link java.lang.instrument.ClassFileTransformer}를
     * {@code inst}에 등록한다. premain 단계에서 단 1회 호출된다.
     *
     * @param inst JVM이 제공하는 {@link Instrumentation} — 절대 null이 아님
     * @throws WeavingInstallException 위빙 설치 실패 시
     */
    void install(Instrumentation inst);

    /**
     * 이 전략의 이름/설명 반환 (로그 출력용).
     *
     * @return 사람이 읽을 수 있는 전략 이름 (ex: "TomcatValveWeaving")
     */
    String name();

    /**
     * 위빙 설치 실패 시 던지는 RuntimeException.
     * premain 단계에서 이 예외가 잡히지 않으면 JVM 기동이 중단될 수 있으므로
     * {@link kr.go.smes.agent.core.OnePassAgentMain}에서 적절히 처리해야 한다.
     */
    final class WeavingInstallException extends RuntimeException {
        public WeavingInstallException(String message, Throwable cause) {
            super(message, cause);
        }
        public WeavingInstallException(String message) {
            super(message);
        }
    }
}

package kr.go.smes.agent.weaving;

/**
 * 위빙 설치 실패 시 던지는 RuntimeException.
 *
 * <p>premain 단계에서 이 예외가 잡히지 않으면 JVM 기동이 중단될 수 있으므로
 * {@link kr.go.smes.agent.core.OnePassAgentMain}에서 적절히 처리해야 한다.
 *
 * <p>레거시 JEUS (4/5/6) 환경에서는 위빙 실패가 WAS 기동을 막으면 안 되므로
 * {@link JeusLegacyWeavingStrategy}가 이 예외를 던지지 않고 경고 로그만 남긴다.
 *
 * @see WeavingStrategy
 */
public class WeavingInstallException extends RuntimeException {

    public WeavingInstallException(String message, Throwable cause) {
        super(message, cause);
    }

    public WeavingInstallException(String message) {
        super(message);
    }
}

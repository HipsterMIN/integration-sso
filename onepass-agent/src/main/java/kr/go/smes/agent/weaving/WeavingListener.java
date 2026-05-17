package kr.go.smes.agent.weaving;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

import java.io.PrintStream;
import java.security.ProtectionDomain;

/**
 * byte-buddy {@link AgentBuilder.Listener} 구현체.
 *
 * <p>위빙 성공/실패/무시 이벤트를 {@link PrintStream}(System.err)에 출력한다.
 * 에러가 발생해도 JVM을 종료하지 않는다 — 단순 로그 출력만 수행.
 *
 * <h2>로그 수준 전략</h2>
 * <ul>
 *   <li>{@code onError}: WARN 수준 출력 — 위빙 실패는 비치명적으로 처리</li>
 *   <li>{@code onTransformation}: INFO 수준 — 위빙 성공 클래스 기록</li>
 *   <li>{@code onIgnored}: 출력 안 함 — 노이즈 방지</li>
 * </ul>
 */
public final class WeavingListener extends AgentBuilder.Listener.Adapter {

    private final PrintStream log;
    private final String      strategyName;

    public WeavingListener(PrintStream log, String strategyName) {
        this.log          = log;
        this.strategyName = strategyName;
    }

    @Override
    public void onTransformation(
            TypeDescription typeDescription,
            ClassLoader classLoader,
            JavaModule module,
            boolean loaded,
            DynamicType dynamicType) {
        log.println("[" + strategyName + "] 위빙 성공: " + typeDescription.getName());
    }

    @Override
    public void onError(
            String typeName,
            ClassLoader classLoader,
            JavaModule module,
            boolean loaded,
            Throwable throwable) {
        log.println("[WARN] [" + strategyName + "] 위빙 실패 (비치명적): "
                + typeName + " — " + throwable.getMessage());
    }

    @Override
    public void onComplete(
            String typeName,
            ClassLoader classLoader,
            JavaModule module,
            boolean loaded) {
        // 완료 이벤트는 노이즈가 많으므로 출력 생략
    }
}

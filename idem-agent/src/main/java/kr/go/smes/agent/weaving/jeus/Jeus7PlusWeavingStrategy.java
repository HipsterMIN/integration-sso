package kr.go.smes.agent.weaving.jeus;

import kr.go.smes.agent.config.AgentConfig;
import kr.go.smes.agent.http.OnePassHttpClient;
import kr.go.smes.agent.was.WasType;
import kr.go.smes.agent.weaving.WeavingInstallException;
import kr.go.smes.agent.weaving.WeavingListener;
import kr.go.smes.agent.weaving.WeavingStrategy;
import kr.go.smes.agent.weaving.engine.JavassistWeavingEngine;
import kr.go.smes.agent.weaving.engine.JavassistWeavingEngine.JavassistClassFileTransformer;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * JEUS 7/8 위빙 전략 — byte-buddy 기반 Generic Filter (JDK 8 이상) 또는
 * Javassist 기반 (JDK 1.6~1.7 폴백).
 *
 * <h2>대상 환경</h2>
 * <ul>
 *   <li>JEUS 7 Fix1~4: JDK 1.6~1.7 / Servlet 3.0 / Java EE 6</li>
 *   <li>JEUS 7 Fix5 이상: JDK 1.6~1.8 / Servlet 3.0</li>
 *   <li>JEUS 8: JDK 1.7~1.8 / Servlet 3.1 / Java EE 7</li>
 *   <li>클래스 패키지: {@code com.tmaxsoft.jeus.*}</li>
 * </ul>
 *
 * <h2>엔진 선택 로직</h2>
 * <p>이 전략은 {@link JeusWeavingEngineSelector}를 통해 런타임 JDK 버전을 확인하고
 * 최적 위빙 엔진을 선택한다:
 * <pre>
 *  런타임 JDK 8+   → byte-buddy 엔진 (javax.servlet.Filter Generic 위빙)
 *  런타임 JDK 7    → Javassist 엔진 (com.tmaxsoft.jeus.web.servlet.* 직접 위빙)
 *  런타임 JDK 6    → Javassist 엔진 (동일)
 * </pre>
 *
 * <h2>byte-buddy 경로 위빙 포인트</h2>
 * <p>JEUS 7/8은 Servlet 3.0/3.1을 지원하므로 {@code javax.servlet.Filter} 표준을 활용한다.
 * {@link kr.go.smes.agent.weaving.GenericFilterWeavingStrategy}와 동일한 방식으로
 * {@code javax.servlet.Filter#doFilter()} 메서드에 Before-Advice를 삽입한다.
 *
 * <h2>Javassist 폴백 경로 위빙 포인트</h2>
 * <p>JDK 7 이하 환경에서는 byte-buddy를 사용할 수 없으므로 Javassist로 폴백한다.
 * JEUS 7/8의 서블릿 핸들러({@code com.tmaxsoft.jeus.web.servlet.JeusServletHandler})를
 * 직접 위빙한다.
 */
public final class Jeus7PlusWeavingStrategy implements WeavingStrategy {

    private static final String STRATEGY_NAME_BB  = "Jeus7PlusWeaving (byte-buddy)";
    private static final String STRATEGY_NAME_JA  = "Jeus7PlusWeaving (Javassist-fallback)";

    /** javax.servlet.Filter FQCN */
    private static final String FILTER_CLASS_JAVAX = "javax.servlet.Filter";
    private static final String DO_FILTER_METHOD   = "doFilter";

    /** Javassist 폴백: JEUS 7/8 서블릿 핸들러 */
    private static final String TARGET_JEUS7_HANDLER =
            "com.tmaxsoft.jeus.web.servlet.JeusServletHandler";

    // byte-buddy Advice가 사용하는 공유 상태
    static volatile OnePassHttpClient sharedHttpClient;
    static volatile AgentConfig       sharedConfig;
    static volatile PrintStream        sharedLog;

    private final WasType    wasType;
    private final AgentConfig config;
    private final PrintStream log;

    public Jeus7PlusWeavingStrategy(WasType wasType, AgentConfig config, PrintStream log) {
        this.wasType = wasType;
        this.config  = config;
        this.log     = log;
    }

    @Override
    public void install(Instrumentation inst) {
        if (!config.isEnabled()) {
            log.println("[Jeus7PlusWeaving] enabled=false — 위빙 건너뜀");
            return;
        }

        JeusWeavingEngineSelector.EngineType engine =
                JeusWeavingEngineSelector.select(wasType, log);

        if (engine == JeusWeavingEngineSelector.EngineType.BYTE_BUDDY) {
            installWithByteBuddy(inst);
        } else {
            installWithJavassist(inst);
        }
    }

    // ── byte-buddy 경로 ────────────────────────────────────────────────────────

    private void installWithByteBuddy(Instrumentation inst) {
        sharedHttpClient = new OnePassHttpClient(config);
        sharedConfig     = config;
        sharedLog        = log;

        log.println("[" + STRATEGY_NAME_BB + "] 설치 시작 (javax.servlet.Filter 위빙)");

        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(new WeavingListener(log, STRATEGY_NAME_BB))
                    .type(ElementMatchers.<TypeDescription>hasSuperType(
                            ElementMatchers.<TypeDescription>named(FILTER_CLASS_JAVAX)))
                    .transform(new AgentBuilder.Transformer() {
                        @Override
                        public DynamicType.Builder<?> transform(
                                DynamicType.Builder<?> builder,
                                TypeDescription typeDescription,
                                ClassLoader classLoader,
                                JavaModule module,
                                ProtectionDomain protectionDomain) {
                            return builder.visit(
                                    Advice.to(Jeus7FilterAdvice.class)
                                            .on(ElementMatchers.named(DO_FILTER_METHOD)));
                        }
                    })
                    .installOn(inst);

            log.println("[" + STRATEGY_NAME_BB + "] 설치 완료");

        } catch (Exception e) {
            throw new WeavingInstallException(
                    "[" + STRATEGY_NAME_BB + "] byte-buddy 설치 실패", e);
        }
    }

    // ── Javassist 폴백 경로 ───────────────────────────────────────────────────

    private void installWithJavassist(Instrumentation inst) {
        System.setProperty(JeusLegacyWeavingStrategy.PROP_ENDPOINT, config.endpoint());
        System.setProperty(JeusLegacyWeavingStrategy.PROP_API_KEY,  config.apiKey());
        System.setProperty(JeusLegacyWeavingStrategy.PROP_ENABLED,  String.valueOf(config.isEnabled()));

        log.println("[" + STRATEGY_NAME_JA + "] 설치 시작 (JDK 8 미만 → Javassist 폴백)");

        JavassistWeavingEngine engine = new JavassistWeavingEngine(log);
        try {
            JavassistClassFileTransformer transformer =
                    new Jeus6WeavingStrategy.Jeus6Transformer(
                            TARGET_JEUS7_HANDLER, "service", log);
            engine.install(inst, transformer);
            log.println("[" + STRATEGY_NAME_JA + "] 설치 완료: "
                    + TARGET_JEUS7_HANDLER + "#service");
        } catch (Throwable t) {
            log.println("[WARN] [" + STRATEGY_NAME_JA + "] Javassist 설치 실패: " + t.getMessage());
        }
    }

    @Override
    public String name() {
        JeusWeavingEngineSelector.EngineType engineType = JeusWeavingEngineSelector.select(wasType, null);
        return engineType == JeusWeavingEngineSelector.EngineType.BYTE_BUDDY
                ? STRATEGY_NAME_BB : STRATEGY_NAME_JA;
    }

    // ── byte-buddy Advice ─────────────────────────────────────────────────────

    /**
     * JEUS 7/8 javax.servlet.Filter doFilter() 진입 직전 실행되는 Advice.
     *
     * <p>Servlet 3.0 (JEUS 7), Servlet 3.1 (JEUS 8) 표준 {@code javax.servlet.Filter}에
     * Before-Advice를 삽입한다. GenericFilterWeavingStrategy와 동일한 Advice 구조이나
     * JEUS 환경에 맞는 로그 접두사를 사용한다.
     */
    @SuppressWarnings("unused")
    public static class Jeus7FilterAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(
                @Advice.Argument(0) Object request,
                @Advice.Argument(1) Object response,
                @Advice.Argument(2) Object chain
        ) {
            AgentConfig cfg = Jeus7PlusWeavingStrategy.sharedConfig;
            if (cfg == null || !cfg.isEnabled()) return;

            PrintStream log = Jeus7PlusWeavingStrategy.sharedLog;

            try {
                String uri = extractUri(request);
                if (uri == null || shouldBypass(uri)) return;

                String token = extractBearerToken(request);
                if (token == null || token.isEmpty()) return;

                OnePassHttpClient client = Jeus7PlusWeavingStrategy.sharedHttpClient;
                if (client == null) return;

                String tokenJson = "{\"token\":\"" + escapeJson(token)
                        + "\",\"apiKey\":\"" + escapeJson(cfg.apiKey()) + "\"}";
                OnePassHttpClient.HttpResponse resp = client.verifyToken(tokenJson);

                if (!resp.isSuccess()) {
                    sendUnauthorized(response, resp.statusCode(), log);
                }

            } catch (Throwable t) {
                if (log != null) {
                    log.println("[WARN][Jeus7FilterAdvice] 토큰 검증 예외: " + t.getMessage());
                }
            }
        }

        private static String extractUri(Object req) {
            try {
                return (String) req.getClass().getMethod("getRequestURI").invoke(req);
            } catch (Exception e) { return null; }
        }

        private static String extractBearerToken(Object req) {
            try {
                String auth = (String) req.getClass()
                        .getMethod("getHeader", String.class).invoke(req, "Authorization");
                if (auth == null || !auth.startsWith("Bearer ")) return null;
                return auth.substring(7).trim();
            } catch (Exception e) { return null; }
        }

        private static void sendUnauthorized(Object resp, int upstreamStatus, PrintStream log) {
            try {
                resp.getClass().getMethod("sendError", int.class).invoke(resp, 401);
                if (log != null) {
                    log.println("[Jeus7FilterAdvice] 인증 실패 → HTTP 401 (upstream=" + upstreamStatus + ")");
                }
            } catch (Exception ignored) {}
        }

        private static boolean shouldBypass(String uri) {
            if (uri.startsWith("/actuator")) return true;
            if (uri.equals("/health"))       return true;
            if (uri.equals("/favicon.ico"))  return true;
            int dot = uri.lastIndexOf('.');
            if (dot >= 0) {
                String ext = uri.substring(dot + 1).toLowerCase();
                return "css".equals(ext)  || "js".equals(ext)
                        || "html".equals(ext) || "htm".equals(ext)
                        || "png".equals(ext)  || "jpg".equals(ext)
                        || "jpeg".equals(ext) || "gif".equals(ext)
                        || "ico".equals(ext)  || "svg".equals(ext)
                        || "woff".equals(ext) || "woff2".equals(ext);
            }
            return false;
        }

        private static String escapeJson(String s) {
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }
    }
}

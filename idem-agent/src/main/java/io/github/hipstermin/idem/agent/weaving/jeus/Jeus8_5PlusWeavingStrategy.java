package io.github.hipstermin.idem.agent.weaving.jeus;

import io.github.hipstermin.idem.agent.config.AgentConfig;
import io.github.hipstermin.idem.agent.http.OnePassHttpClient;
import io.github.hipstermin.idem.agent.was.WasType;
import io.github.hipstermin.idem.agent.weaving.WeavingInstallException;
import io.github.hipstermin.idem.agent.weaving.WeavingListener;
import io.github.hipstermin.idem.agent.weaving.WeavingStrategy;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

/**
 * JEUS 8.5 / JEUS 9 / JEUS 21 위빙 전략 — byte-buddy 기반 (JDK 8+ / Jakarta EE 지원).
 *
 * <h2>대상 환경</h2>
 * <ul>
 *   <li>JEUS 8.5: JDK 1.8 또는 JDK 11 / Servlet 4.0 / Java EE 8
 *       ({@code javax.servlet.*} 마지막 버전)</li>
 *   <li>JEUS 9:   JDK 11+ / Servlet 5.0 / Jakarta EE 9
 *       ({@code jakarta.servlet.*} 전환)</li>
 *   <li>JEUS 21:  JDK 21+ / Servlet 6.0 / Jakarta EE 10</li>
 * </ul>
 *
 * <h2>javax vs jakarta 이중 지원</h2>
 * <p>JEUS 9 이상은 {@code javax.servlet}이 제거되고 {@code jakarta.servlet}으로 전환됐다.
 * JEUS 8.5는 {@code javax.servlet}을 사용한다.
 * 단일 Agent JAR이 두 버전을 모두 지원하기 위해 두 Filter 인터페이스를 모두 위빙한다:
 * <pre>
 *   javax.servlet.Filter#doFilter  → JEUS 8.5 (Servlet 4.0)
 *   jakarta.servlet.Filter#doFilter → JEUS 9/21 (Servlet 5.0+)
 * </pre>
 *
 * <p>하나가 클래스패스에 없으면 위빙이 조용히 무시된다(byte-buddy 기본 동작).
 * 따라서 동일한 AgentBuilder 체인에서 두 Filter를 모두 등록해도 안전하다.
 *
 * <h2>위빙 포인트 선택 근거</h2>
 * <p>JEUS 8.5/9/21은 표준 Jakarta EE / Java EE Servlet 스펙을 완전히 구현한다.
 * Valve 방식(Tomcat 전용)보다 표준 Filter를 사용하는 것이 WAS 중립적이며,
 * 유관기관 WAS 업그레이드 시 Agent 변경 없이 지원 범위를 확장할 수 있다.
 *
 * <h2>JEUS 9+ 주의 사항</h2>
 * <ul>
 *   <li>Jakarta EE 9+: {@code javax.servlet} → {@code jakarta.servlet} 완전 전환</li>
 *   <li>기존 {@code javax.servlet.Filter} 구현체들도 {@code jakarta.servlet.Filter}로
 *       마이그레이션 필요</li>
 *   <li>Agent의 Reflection 코드: {@code getHeader()}, {@code getRequestURI()},
 *       {@code sendError()} 메서드명은 javax/jakarta 공통이므로 변경 불필요</li>
 * </ul>
 */
public final class Jeus8_5PlusWeavingStrategy implements WeavingStrategy {

    private static final String STRATEGY_NAME = "Jeus8_5PlusWeaving (byte-buddy, javax+jakarta)";

    /** javax.servlet.Filter — JEUS 8.5 (Servlet 4.0) */
    private static final String FILTER_JAVAX   = "javax.servlet.Filter";
    /** jakarta.servlet.Filter — JEUS 9+ (Servlet 5.0+, Jakarta EE 9+) */
    private static final String FILTER_JAKARTA = "jakarta.servlet.Filter";
    /** 위빙 대상 메서드 */
    private static final String DO_FILTER      = "doFilter";

    // byte-buddy Advice 공유 상태
    static volatile OnePassHttpClient sharedHttpClient;
    static volatile AgentConfig       sharedConfig;
    static volatile PrintStream        sharedLog;

    private final WasType    wasType;
    private final AgentConfig config;
    private final PrintStream log;

    public Jeus8_5PlusWeavingStrategy(WasType wasType, AgentConfig config, PrintStream log) {
        this.wasType = wasType;
        this.config  = config;
        this.log     = log;
    }

    @Override
    public void install(Instrumentation inst) {
        if (!config.isEnabled()) {
            log.println("[" + STRATEGY_NAME + "] enabled=false — 위빙 건너뜀");
            return;
        }

        sharedHttpClient = new OnePassHttpClient(config);
        sharedConfig     = config;
        sharedLog        = log;

        log.println("[" + STRATEGY_NAME + "] 설치 시작 (JEUS " + wasType.displayName() + ")");
        log.println("[" + STRATEGY_NAME + "] endpoint=" + config.endpoint());

        try {
            AgentBuilder builder = new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(new WeavingListener(log, STRATEGY_NAME));

            // ── javax.servlet.Filter 위빙 (JEUS 8.5) ─────────────────────────
            builder = applyFilterWeaving(builder, FILTER_JAVAX);

            // ── jakarta.servlet.Filter 위빙 (JEUS 9+, Jakarta EE) ─────────────
            builder = applyFilterWeaving(builder, FILTER_JAKARTA);

            builder.installOn(inst);

            log.println("[" + STRATEGY_NAME + "] 설치 완료 (javax + jakarta 이중 지원)");

        } catch (Exception e) {
            throw new WeavingInstallException(
                    "[" + STRATEGY_NAME + "] byte-buddy 설치 실패", e);
        }
    }

    /**
     * AgentBuilder에 Filter 위빙 체인을 추가한다.
     *
     * <p>대상 Filter 클래스가 클래스패스에 없으면 byte-buddy가 조용히 무시하므로
     * JEUS 8.5 환경에서 jakarta.servlet.Filter 위빙이 등록되어도 문제없다.
     *
     * @param builder     기존 AgentBuilder
     * @param filterFqcn  위빙할 Filter 인터페이스 FQCN
     * @return 위빙이 추가된 AgentBuilder
     */
    private AgentBuilder applyFilterWeaving(AgentBuilder builder, final String filterFqcn) {
        log.println("[" + STRATEGY_NAME + "] 위빙 대상 등록: " + filterFqcn + "#" + DO_FILTER);

        return builder
                .type(ElementMatchers.<TypeDescription>hasSuperType(
                        ElementMatchers.<TypeDescription>named(filterFqcn)))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(
                            DynamicType.Builder<?> b,
                            TypeDescription typeDescription,
                            ClassLoader classLoader,
                            JavaModule module,
                            ProtectionDomain protectionDomain) {
                        return b.visit(
                                Advice.to(Jeus8_5FilterAdvice.class)
                                        .on(ElementMatchers.named(DO_FILTER)));
                    }
                });
    }

    @Override
    public String name() {
        return STRATEGY_NAME;
    }

    // ── byte-buddy Advice ─────────────────────────────────────────────────────

    /**
     * JEUS 8.5/9/21 javax/jakarta.servlet.Filter doFilter() 진입 직전 실행되는 Advice.
     *
     * <h2>javax vs jakarta 공존 처리</h2>
     * <p>이 Advice 클래스는 javax와 jakarta 두 Filter 인터페이스 모두에 적용된다.
     * 두 인터페이스의 {@code doFilter()} 시그니처가 동일하므로 Advice 구현도 동일하다.
     * Reflection으로 {@code getRequestURI()}, {@code getHeader()}, {@code sendError()}를
     * 호출하기 때문에 javax/jakarta 패키지 구분 없이 동작한다.
     *
     * <h2>JEUS 9+ 특이 사항</h2>
     * <p>JEUS 9의 {@code jakarta.servlet.Filter} 구현체들은
     * {@code jakarta.servlet.FilterChain}을 파라미터로 받는다.
     * Advice에서는 Object 타입으로 추상화하므로 패키지 변경에 무관하게 동작한다.
     */
    @SuppressWarnings("unused")
    public static class Jeus8_5FilterAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(
                @Advice.Argument(0) Object request,   // javax/jakarta.servlet.ServletRequest
                @Advice.Argument(1) Object response,  // javax/jakarta.servlet.ServletResponse
                @Advice.Argument(2) Object chain      // javax/jakarta.servlet.FilterChain
        ) {
            AgentConfig cfg = Jeus8_5PlusWeavingStrategy.sharedConfig;
            if (cfg == null || !cfg.isEnabled()) return;

            PrintStream log = Jeus8_5PlusWeavingStrategy.sharedLog;

            try {
                // URI 추출 (javax.servlet / jakarta.servlet 공통 메서드명)
                String uri = extractUri(request);
                if (uri == null || shouldBypass(uri)) return;

                // Bearer 토큰 추출
                String token = extractBearerToken(request);
                if (token == null || token.isEmpty()) return;

                // OnePass 검증 API 호출
                OnePassHttpClient client = Jeus8_5PlusWeavingStrategy.sharedHttpClient;
                if (client == null) return;

                String tokenJson = buildTokenJson(token, cfg.apiKey());
                OnePassHttpClient.HttpResponse resp = client.verifyToken(tokenJson);

                if (!resp.isSuccess()) {
                    sendUnauthorized(response, resp.statusCode(), log);
                }

            } catch (Throwable t) {
                if (log != null) {
                    log.println("[WARN][Jeus8_5FilterAdvice] 토큰 검증 예외: " + t.getMessage());
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
                    log.println("[Jeus8_5FilterAdvice] 인증 실패 → HTTP 401 (upstream="
                            + upstreamStatus + ")");
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
                        || "woff".equals(ext) || "woff2".equals(ext)
                        || "ttf".equals(ext)  || "map".equals(ext);
            }
            return false;
        }

        private static String buildTokenJson(String token, String apiKey) {
            return "{\"token\":\"" + escapeJson(token)
                    + "\",\"apiKey\":\"" + escapeJson(apiKey) + "\"}";
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

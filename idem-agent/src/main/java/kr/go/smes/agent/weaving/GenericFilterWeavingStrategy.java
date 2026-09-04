package kr.go.smes.agent.weaving;

import kr.go.smes.agent.config.AgentConfig;
import kr.go.smes.agent.http.OnePassHttpClient;

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
 * Generic Servlet Filter 위빙 전략 (WAS-agnostic Fallback).
 *
 * <h2>적용 대상 WAS</h2>
 * JBoss/WildFly, WebLogic, Undertow, Jetty, UNKNOWN — Servlet API를 표준으로 지원하는 모든 WAS.
 *
 * <h2>위빙 포인트</h2>
 * {@code javax.servlet.Filter#doFilter(ServletRequest, ServletResponse, FilterChain)} 메서드에
 * Before-Advice를 삽입하여 모든 HTTP 요청 진입 시 OnePass 토큰을 검증한다.
 *
 * <h2>왜 Filter인가?</h2>
 * <ul>
 *   <li>Servlet 3.0+ 표준 — WAS 종류에 무관하게 동작</li>
 *   <li>유관기관 코드에 Filter 구현체가 없어도 위빙 시 자동 삽입</li>
 *   <li>Spring Security, Shiro 등 기존 보안 필터와 공존 가능</li>
 * </ul>
 *
 * <h2>바이패스 조건</h2>
 * {@link TomcatWeavingStrategy.TomcatValveAdvice}와 동일한 바이패스 규칙 적용.
 *
 * <h2>byte-buddy 위빙 구조</h2>
 * <pre>
 *  AgentBuilder
 *    .type(isSubTypeOf(javax.servlet.Filter))
 *    .transform(
 *      Advice.to(GenericFilterAdvice)
 *        .on(named("doFilter"))
 *    )
 * </pre>
 *
 * <h2>javax vs jakarta 이중 지원</h2>
 * Servlet 5.0+ (Jakarta EE 9+)부터 패키지가 {@code jakarta.servlet}으로 변경됐다.
 * 두 패키지 모두 위빙 대상으로 등록하여 단일 Agent로 레거시/신규 WAS를 모두 지원한다.
 */
public final class GenericFilterWeavingStrategy implements WeavingStrategy {

    private static final String STRATEGY_NAME = "GenericServletFilterWeaving";

    /** javax.servlet.Filter FQCN (Servlet 4.0 이하, JBoss EAP 7, Tomcat 9 등) */
    private static final String FILTER_CLASS_JAVAX   = "javax.servlet.Filter";
    /** jakarta.servlet.Filter FQCN (Servlet 5.0+, JBoss EAP 8, Tomcat 10+, WildFly 27+) */
    private static final String FILTER_CLASS_JAKARTA = "jakarta.servlet.Filter";

    private static final String DO_FILTER_METHOD = "doFilter";

    /**
     * Advice 클래스에서 참조하는 공유 상태.
     * premain 단계에서 초기화. volatile → 가시성 보장.
     */
    static volatile OnePassHttpClient sharedHttpClient;
    static volatile AgentConfig       sharedConfig;
    static volatile PrintStream        sharedLog;

    private final AgentConfig config;
    private final PrintStream log;

    public GenericFilterWeavingStrategy(AgentConfig config, PrintStream log) {
        this.config = config;
        this.log    = log;
    }

    @Override
    public void install(Instrumentation inst) {
        sharedHttpClient = new OnePassHttpClient(config);
        sharedConfig     = config;
        sharedLog        = log;

        log.println("[" + STRATEGY_NAME + "] byte-buddy AgentBuilder 설치 시작");

        try {
            AgentBuilder builder = new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(new WeavingListener(log, STRATEGY_NAME));

            // ── javax.servlet.Filter 위빙 ──────────────────────────────────────
            builder = installFilterWeaving(builder, FILTER_CLASS_JAVAX, log);

            // ── jakarta.servlet.Filter 위빙 (Servlet 5+) ──────────────────────
            builder = installFilterWeaving(builder, FILTER_CLASS_JAKARTA, log);

            builder.installOn(inst);
            log.println("[" + STRATEGY_NAME + "] 설치 완료 (javax + jakarta 이중 지원)");

        } catch (Exception e) {
            throw new WeavingInstallException(
                    "[" + STRATEGY_NAME + "] byte-buddy 설치 실패", e);
        }
    }

    private static AgentBuilder installFilterWeaving(
            AgentBuilder builder, String filterClassName, PrintStream log) {

        log.println("[" + STRATEGY_NAME + "] 위빙 대상 등록: " + filterClassName + "#" + DO_FILTER_METHOD);

        // hasSuperType(named(FQCN)): javax/jakarta.servlet.Filter 구현 클래스 모두 매칭
        // isSubTypeOf는 Class<?>/TypeDescription만 받으므로 문자열 FQCN엔 hasSuperType 사용
        return builder
                .type(ElementMatchers.<TypeDescription>hasSuperType(
                        ElementMatchers.<TypeDescription>named(filterClassName)))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(
                            DynamicType.Builder<?> b,
                            TypeDescription typeDescription,
                            ClassLoader classLoader,
                            JavaModule module,
                            ProtectionDomain protectionDomain) {
                        return b.visit(
                                Advice.to(GenericFilterAdvice.class)
                                        .on(ElementMatchers.named(DO_FILTER_METHOD)));
                    }
                });
    }

    @Override
    public String name() {
        return STRATEGY_NAME;
    }

    // ── Generic Filter Advice ────────────────────────────────────────────────────

    /**
     * javax/jakarta.servlet.Filter doFilter() 진입 직전 실행되는 Advice.
     *
     * <p>요청 파라미터({@code HttpServletRequest})를 Object 타입으로 수신하고
     * Reflection으로 처리한다 — Agent ClassLoader와 WAS ClassLoader 격리 때문.
     */
    @SuppressWarnings("unused")
    public static class GenericFilterAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(
                @Advice.Argument(0) Object request,   // javax/jakarta.servlet.ServletRequest
                @Advice.Argument(1) Object response,  // javax/jakarta.servlet.ServletResponse
                @Advice.Argument(2) Object chain      // javax/jakarta.servlet.FilterChain
        ) {
            AgentConfig cfg = GenericFilterWeavingStrategy.sharedConfig;
            if (cfg == null || !cfg.isEnabled()) return;

            PrintStream log = GenericFilterWeavingStrategy.sharedLog;

            try {
                // ── HTTP 요청 여부 확인 (HttpServletRequest cast 가능?) ──────────
                String uri = extractUri(request);
                if (uri == null || shouldBypass(uri)) return;

                // ── Bearer 토큰 추출 ─────────────────────────────────────────────
                String token = extractBearerToken(request);
                if (token == null || token.isEmpty()) return;

                // ── OnePass 검증 호출 ─────────────────────────────────────────────
                OnePassHttpClient client = GenericFilterWeavingStrategy.sharedHttpClient;
                if (client == null) return;

                String tokenJson = buildTokenJson(token, cfg.apiKey());
                OnePassHttpClient.HttpResponse resp = client.verifyToken(tokenJson);

                if (!resp.isSuccess()) {
                    sendUnauthorized(response, resp.statusCode(), log);
                }

            } catch (Throwable t) {
                if (log != null) {
                    log.println("[WARN] [GenericFilterAdvice] 토큰 검증 중 예외: " + t.getMessage());
                }
            }
        }

        // ── Reflection 헬퍼 ────────────────────────────────────────────────────

        private static String extractUri(Object request) {
            try {
                // javax/jakarta.servlet.http.HttpServletRequest#getRequestURI()
                return (String) request.getClass()
                        .getMethod("getRequestURI")
                        .invoke(request);
            } catch (Exception e) {
                return null;
            }
        }

        private static String extractBearerToken(Object request) {
            try {
                String auth = (String) request.getClass()
                        .getMethod("getHeader", String.class)
                        .invoke(request, "Authorization");
                if (auth == null || !auth.startsWith("Bearer ")) return null;
                return auth.substring(7).trim();
            } catch (Exception e) {
                return null;
            }
        }

        private static void sendUnauthorized(Object response, int upstreamStatus, PrintStream log) {
            try {
                // javax/jakarta.servlet.http.HttpServletResponse#sendError(int)
                response.getClass()
                        .getMethod("sendError", int.class)
                        .invoke(response, 401);
                if (log != null) {
                    log.println("[GenericFilterAdvice] 인증 실패 → HTTP 401 (upstream=" + upstreamStatus + ")");
                }
            } catch (Exception ignored) {
                // sendError 실패 시 무시 — WAS 자체 오류 처리에 위임
            }
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

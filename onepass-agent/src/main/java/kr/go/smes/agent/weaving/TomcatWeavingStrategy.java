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
 * Apache Tomcat Catalina Valve 체인 위빙 전략.
 *
 * <h2>위빙 포인트</h2>
 * {@code org.apache.catalina.core.StandardHostValve#invoke(Request, Response)} 메서드에
 * Before-Advice를 삽입하여 모든 HTTP 요청 진입 시 OnePass 토큰을 검증한다.
 *
 * <h2>왜 Valve인가?</h2>
 * <ul>
 *   <li>Servlet Filter보다 더 이른 단계에서 동작 — JNDI, 세션 처리 전 인터셉트 가능</li>
 *   <li>Tomcat의 표준 파이프라인 확장 메커니즘 — 유관기관 코드 무변경</li>
 *   <li>Spring Security 필터 체인보다 앞에 위치하여 불필요한 Spring 의존 제거</li>
 * </ul>
 *
 * <h2>바이패스 조건</h2>
 * 다음 경로는 토큰 검증을 건너뛴다:
 * <ul>
 *   <li>{@code /actuator/**} — Spring Boot Actuator 헬스체크</li>
 *   <li>{@code /health} — 관습적 헬스 엔드포인트</li>
 *   <li>{@code /favicon.ico}</li>
 *   <li>정적 리소스 확장자: {@code .css, .js, .html, .png, .jpg, .gif, .ico, .woff, .woff2}</li>
 * </ul>
 *
 * <h2>byte-buddy 위빙 구조</h2>
 * <pre>
 *  AgentBuilder
 *    .type(isSubTypeOf(Valve))            ← 모든 Valve 서브타입 대상
 *    .transform(
 *      Advice.to(TomcatValveAdvice)       ← Before/After Advice 삽입
 *        .on(named("invoke"))
 *    )
 * </pre>
 *
 * <h2>위빙 패키지 (relocated)</h2>
 * {@code net.bytebuddy} → {@code kr.go.smes.agent.shaded.bytebuddy}
 * (shadowJar relocate 후 컴파일된 import와 실제 런타임 클래스가 일치)
 */
public final class TomcatWeavingStrategy implements WeavingStrategy {

    private static final String STRATEGY_NAME = "TomcatValveWeaving";

    /** Tomcat Valve 인터페이스 FQCN */
    private static final String VALVE_CLASS = "org.apache.catalina.Valve";
    /** 위빙 대상 메서드명 */
    private static final String INVOKE_METHOD = "invoke";

    /**
     * Advice에서 사용할 OnePassHttpClient를 스레드 로컬에 저장.
     * premain 단계에서 초기화, Advice 클래스에서 static으로 참조.
     */
    static volatile OnePassHttpClient sharedHttpClient;
    static volatile AgentConfig       sharedConfig;
    static volatile PrintStream        sharedLog;

    private final AgentConfig config;
    private final PrintStream log;

    public TomcatWeavingStrategy(AgentConfig config, PrintStream log) {
        this.config = config;
        this.log    = log;
    }

    @Override
    public void install(Instrumentation inst) {
        // Advice에서 사용할 공유 상태 초기화
        sharedHttpClient = new OnePassHttpClient(config);
        sharedConfig     = config;
        sharedLog        = log;

        log.println("[" + STRATEGY_NAME + "] byte-buddy AgentBuilder 설치 시작");
        log.println("[" + STRATEGY_NAME + "] 위빙 대상: " + VALVE_CLASS + "#" + INVOKE_METHOD);

        try {
            new AgentBuilder.Default()
                    // ── 위빙 대상 타입 선택 ───────────────────────────────────────
                    // org.apache.catalina.Valve 인터페이스의 모든 구현체를 대상으로 함
                    // (StandardContextValve, StandardHostValve, ApplicationFilterChain 등)
                    // hasSuperType(named(FQCN)): 수퍼 타입 FQCN을 문자열로 지정할 때는
                    // isSubTypeOf(TypeDescription) 대신 hasSuperType(named(...)) 사용
                    .type(ElementMatchers.<TypeDescription>hasSuperType(
                            ElementMatchers.<TypeDescription>named(VALVE_CLASS)))
                    // ── 변환 적용 ───────────────────────────────────────────────────
                    .transform(new AgentBuilder.Transformer() {
                        @Override
                        public DynamicType.Builder<?> transform(
                                DynamicType.Builder<?> builder,
                                TypeDescription typeDescription,
                                ClassLoader classLoader,
                                JavaModule module,
                                ProtectionDomain protectionDomain) {
                            return builder.visit(
                                    Advice.to(TomcatValveAdvice.class)
                                            .on(ElementMatchers.named(INVOKE_METHOD)));
                        }
                    })
                    // ── 리스너: 위빙 성공/실패 로그 ──────────────────────────────────
                    .with(new WeavingListener(log, STRATEGY_NAME))
                    // ── 에러 처리: 위빙 실패해도 JVM 계속 기동 ───────────────────────
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .installOn(inst);

            log.println("[" + STRATEGY_NAME + "] 설치 완료");

        } catch (Exception e) {
            throw new WeavingInstallException(
                    "[" + STRATEGY_NAME + "] byte-buddy 설치 실패", e);
        }
    }

    @Override
    public String name() {
        return STRATEGY_NAME;
    }

    // ── Tomcat Valve Advice ───────────────────────────────────────────────────────

    /**
     * Tomcat Valve {@code invoke()} 진입 직전 실행되는 Advice.
     *
     * <p>byte-buddy Advice 클래스는 인스턴스화되지 않는 정적 클래스여야 하며,
     * 모든 로직은 {@code @Advice.OnMethodEnter} 또는 {@code @Advice.OnMethodExit}
     * 정적 메서드에 구현한다.
     *
     * <p>요청 파라미터 타입({@code org.apache.catalina.connector.Request})을
     * 직접 참조하면 Agent ClassLoader에서 Tomcat 클래스를 찾지 못해 NoClassDefFoundError가
     * 발생하므로, {@link Object} 타입으로 수신하고 Reflection으로 처리한다.
     */
    @SuppressWarnings("unused") // byte-buddy가 런타임에 참조
    public static class TomcatValveAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(
                @Advice.Argument(0) Object request,   // org.apache.catalina.connector.Request
                @Advice.Argument(1) Object response   // org.apache.catalina.connector.Response
        ) {
            // ── Agent 비활성화 체크 ─────────────────────────────────────────
            AgentConfig cfg = TomcatWeavingStrategy.sharedConfig;
            if (cfg == null || !cfg.isEnabled()) return;

            PrintStream log = TomcatWeavingStrategy.sharedLog;

            try {
                // ── 요청 URI 추출 (Reflection) ──────────────────────────────
                String uri = extractUri(request);
                if (uri == null || shouldBypass(uri)) {
                    return; // 정적 리소스 / 헬스체크 바이패스
                }

                // ── Authorization 헤더에서 Bearer 토큰 추출 ──────────────────
                String token = extractBearerToken(request);
                if (token == null || token.isEmpty()) {
                    // 토큰 없음 → 위빙 레이어에서 처리하지 않음 (WAS 자체 인증 흐름에 위임)
                    return;
                }

                // ── OnePass 토큰 검증 API 호출 ────────────────────────────────
                OnePassHttpClient client = TomcatWeavingStrategy.sharedHttpClient;
                if (client == null) return;

                String tokenJson = buildTokenJson(token, cfg.apiKey());
                OnePassHttpClient.HttpResponse resp = client.verifyToken(tokenJson);

                if (!resp.isSuccess()) {
                    // 인증 실패 → HTTP 401 응답 설정 후 요청 중단
                    setUnauthorized(response, resp.statusCode(), log);
                }

            } catch (Throwable t) {
                // Advice 내 모든 예외는 JVM 기동을 막으면 안 되므로 suppress
                if (log != null) {
                    log.println("[WARN] [TomcatValveAdvice] 토큰 검증 중 예외: " + t.getMessage());
                }
            }
        }

        // ── Reflection 헬퍼 ────────────────────────────────────────────────────

        private static String extractUri(Object request) {
            try {
                // org.apache.catalina.connector.Request#getRequestURI()
                return (String) request.getClass().getMethod("getRequestURI").invoke(request);
            } catch (Exception e) {
                return null;
            }
        }

        private static String extractBearerToken(Object request) {
            try {
                // org.apache.catalina.connector.Request#getHeader(String)
                String auth = (String) request.getClass()
                        .getMethod("getHeader", String.class)
                        .invoke(request, "Authorization");
                if (auth == null || !auth.startsWith("Bearer ")) return null;
                return auth.substring(7).trim();
            } catch (Exception e) {
                return null;
            }
        }

        private static void setUnauthorized(Object response, int upstreamStatus, PrintStream log) {
            try {
                // org.apache.catalina.connector.Response#setStatus(int)
                response.getClass().getMethod("setStatus", int.class).invoke(response, 401);
                // org.apache.catalina.connector.Response#setSuspended(boolean) — 응답 본문 중단
                response.getClass().getMethod("setSuspended", boolean.class).invoke(response, true);
                if (log != null) {
                    log.println("[TomcatValveAdvice] 인증 실패 → HTTP 401 반환 (upstream=" + upstreamStatus + ")");
                }
            } catch (Exception ignored) {
                // 응답 설정 실패는 무시 — WAS가 처리
            }
        }

        /** 바이패스 대상 URI 판정 */
        private static boolean shouldBypass(String uri) {
            if (uri.startsWith("/actuator")) return true;
            if (uri.equals("/health"))       return true;
            if (uri.equals("/favicon.ico"))  return true;
            // 정적 리소스 확장자
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
            // Jackson 미사용 — 단순 문자열 조립 (토큰에 특수문자 포함 가능성 있으므로 escape)
            return "{\"token\":\"" + escapeJson(token)
                    + "\",\"apiKey\":\"" + escapeJson(apiKey) + "\"}";
        }

        /** 최소한의 JSON 문자열 이스케이프 */
        private static String escapeJson(String s) {
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }
    }
}

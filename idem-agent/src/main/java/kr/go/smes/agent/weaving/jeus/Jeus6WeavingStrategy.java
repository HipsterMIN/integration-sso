package kr.go.smes.agent.weaving.jeus;

import kr.go.smes.agent.config.AgentConfig;
import kr.go.smes.agent.weaving.WeavingStrategy;
import kr.go.smes.agent.weaving.engine.JavassistWeavingEngine;

import java.io.PrintStream;
import java.lang.instrument.Instrumentation;

/**
 * JEUS 6 전용 위빙 전략 — Javassist 기반 (JDK 1.5~1.7 호환).
 *
 * <h2>대상 환경</h2>
 * <ul>
 *   <li>TmaxSoft JEUS 6 Fix1~8: JDK 1.5~1.6</li>
 *   <li>TmaxSoft JEUS 6 Fix9 이상: JDK 1.5~1.7</li>
 *   <li>Servlet 2.5 / Java EE 5</li>
 *   <li>클래스 패키지: {@code com.tmaxsoft.jeus.*} (신 패키지, JEUS 6에서 전환)</li>
 * </ul>
 *
 * <h2>JEUS 4/5 대비 변경점</h2>
 * <ul>
 *   <li>클래스 패키지가 {@code com.tmax.jeus.*} → {@code com.tmaxsoft.jeus.*}로 변경</li>
 *   <li>Servlet 2.5 도입으로 {@code javax.servlet.Filter} 지원 강화</li>
 *   <li>JEUS 6 Filter Chain이 존재하면 Filter에 위빙하는 것이 더 안정적</li>
 *   <li>JDK 1.5~1.7 범위 → Javassist 통일 (JDK 1.6이어도 안전을 위해 Javassist 사용)</li>
 * </ul>
 *
 * <h2>위빙 포인트 전략 (우선순위)</h2>
 * <ol>
 *   <li>{@code com.tmaxsoft.jeus.web.servlet.JeusServletHandler#service}
 *       — JEUS 6 신 패키지 서블릿 핸들러 (주 위빙 포인트)</li>
 *   <li>{@code com.tmaxsoft.jeus.web.JeusWebContainer} 내부 dispatch 메서드
 *       — 웹컨테이너 수준 (대체 포인트)</li>
 *   <li>{@code jeus.servlet.JeusServletEngine#service}
 *       — 공통 내부 엔진 (최종 Fallback)</li>
 * </ol>
 *
 * <h2>javax.servlet.Filter 활용</h2>
 * <p>JEUS 6는 Servlet 2.5를 지원하므로 {@code javax.servlet.Filter} 체인이 존재한다.
 * 단, JDK 1.5~1.6 환경에서 Javassist로 Filter를 위빙하는 것은 byte-buddy보다
 * 복잡도가 높다. 따라서 JEUS 6 고유 서블릿 핸들러를 직접 위빙하는 방식을 우선 사용한다.
 */
public final class Jeus6WeavingStrategy implements WeavingStrategy {

    private static final String STRATEGY_NAME = "Jeus6Weaving (JEUS 6, Javassist)";

    // ── 위빙 대상 클래스 후보 ────────────────────────────────────────────────────
    private static final String TARGET_SERVLET_HANDLER =
            "com.tmaxsoft.jeus.web.servlet.JeusServletHandler";
    private static final String TARGET_WEB_CONTAINER =
            "com.tmaxsoft.jeus.web.JeusWebContainer";
    private static final String TARGET_SERVLET_ENGINE =
            "jeus.servlet.JeusServletEngine"; // JEUS 4/5/6 공통 내부

    private static final String METHOD_SERVICE  = "service";
    private static final String METHOD_DISPATCH = "dispatch";

    private final AgentConfig config;
    private final PrintStream log;

    public Jeus6WeavingStrategy(AgentConfig config, PrintStream log) {
        this.config = config;
        this.log    = log;
    }

    @Override
    public void install(Instrumentation inst) {
        if (!config.isEnabled()) {
            log.println("[" + STRATEGY_NAME + "] enabled=false — 위빙 건너뜀");
            return;
        }

        // System Property를 통해 위빙 코드에 설정 공유
        System.setProperty(JeusLegacyWeavingStrategy.PROP_ENDPOINT, config.endpoint());
        System.setProperty(JeusLegacyWeavingStrategy.PROP_API_KEY,  config.apiKey());
        System.setProperty(JeusLegacyWeavingStrategy.PROP_ENABLED,  String.valueOf(config.isEnabled()));

        log.println("[" + STRATEGY_NAME + "] 설치 시작 — JEUS 6 (JDK 1.5~1.7 호환 Javassist 위빙)");
        log.println("[" + STRATEGY_NAME + "] endpoint=" + config.endpoint());

        JavassistWeavingEngine engine = new JavassistWeavingEngine(log);

        // ── 주 위빙 포인트: JeusServletHandler#service ───────────────────────
        boolean installed = tryInstall(engine, inst, TARGET_SERVLET_HANDLER, METHOD_SERVICE);

        // ── 대체 1: JeusWebContainer#dispatch ────────────────────────────────
        if (!installed) {
            log.println("[" + STRATEGY_NAME + "] JeusServletHandler 미발견 → JeusWebContainer 시도");
            installed = tryInstall(engine, inst, TARGET_WEB_CONTAINER, METHOD_DISPATCH);
        }

        // ── 대체 2: JeusServletEngine#service (JEUS 4/5/6 공통) ─────────────
        if (!installed) {
            log.println("[" + STRATEGY_NAME + "] JeusWebContainer 미발견 → JeusServletEngine 시도");
            installed = tryInstall(engine, inst, TARGET_SERVLET_ENGINE, METHOD_SERVICE);
        }

        if (!installed) {
            log.println("[WARN] [" + STRATEGY_NAME + "] 모든 위빙 포인트 실패 — "
                    + "OnePass SSO 기능 비활성화. "
                    + "JEUS 6 버전 확인: -Donepass.was.type=JEUS_6");
            return;
        }

        log.println("[" + STRATEGY_NAME + "] 설치 완료");
    }

    private boolean tryInstall(
            JavassistWeavingEngine engine,
            Instrumentation inst,
            String targetClass,
            String targetMethod) {
        try {
            JavassistWeavingEngine.JavassistClassFileTransformer transformer =
                    new Jeus6Transformer(targetClass, targetMethod, log);
            engine.install(inst, transformer);
            log.println("[" + STRATEGY_NAME + "] Transformer 등록: "
                    + targetClass + "#" + targetMethod);
            return true;
        } catch (Throwable t) {
            log.println("[WARN] [" + STRATEGY_NAME + "] Transformer 등록 실패 ("
                    + targetClass + "): " + t.getMessage());
            return false;
        }
    }

    @Override
    public String name() {
        return STRATEGY_NAME;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Javassist 변환기 — JEUS 6
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * JEUS 6 서블릿 핸들러에 OnePass 토큰 검증 코드를 삽입하는 Javassist 변환기.
     *
     * <p>JEUS 6는 Servlet 2.5이므로 {@code javax.servlet.http.HttpServletRequest}와
     * {@code javax.servlet.http.HttpServletResponse}가 표준으로 지원된다.
     * JeusLegacyTransformer와 동일한 JDK 1.4+ API 제약을 유지하되
     * Servlet 2.5 표준 API를 최대한 활용한다.
     */
    static final class Jeus6Transformer
            extends JavassistWeavingEngine.JavassistClassFileTransformer {

        private final String targetClass;
        private final String targetMethod;

        Jeus6Transformer(String targetClass, String targetMethod, PrintStream log) {
            super(log);
            this.targetClass  = targetClass;
            this.targetMethod = targetMethod;
        }

        @Override
        public String targetClassName() {
            return targetClass;
        }

        @Override
        public String targetMethodName() {
            return targetMethod;
        }

        /**
         * JEUS 6 service/dispatch() 진입 직전 삽입 코드.
         *
         * <p>JeusLegacyTransformer와 위빙 로직이 동일하나,
         * JEUS 6 클래스 구조에 맞게 HttpServletRequest 추출 방식이 다를 수 있다.
         * Javassist 소스 코드 문자열에서 Reflection 대신 직접 캐스팅을 사용하여
         * Servlet 2.5 API의 타입 안전성을 활용한다.
         */
        @Override
        public String buildInsertBeforeSource(String targetClassName, String targetMethodName) {
            // @formatter:off
            return "{"
                + "  String _enabled = System.getProperty(\""
                +       JeusLegacyWeavingStrategy.PROP_ENABLED + "\", \"true\");"
                + "  if (\"true\".equalsIgnoreCase(_enabled)) {"
                + "    try {"
                // $1이 HttpServletRequest인지 확인 (service(ServletRequest, ServletResponse) 대응)
                + "      if ($1 instanceof javax.servlet.http.HttpServletRequest) {"
                + "        javax.servlet.http.HttpServletRequest _req ="
                + "            (javax.servlet.http.HttpServletRequest) $1;"
                + "        javax.servlet.http.HttpServletResponse _res ="
                + "            (javax.servlet.http.HttpServletResponse) $2;"
                + "        String _uri = _req.getRequestURI();"
                + "        if (_uri == null) _uri = \"/\";"
                // 바이패스 체크 (정적 리소스 / actuator 등)
                + "        boolean _skip = _uri.startsWith(\"/actuator\")"
                + "          || _uri.equals(\"/health\")"
                + "          || _uri.endsWith(\".css\")"
                + "          || _uri.endsWith(\".js\")"
                + "          || _uri.endsWith(\".png\")"
                + "          || _uri.endsWith(\".jpg\")"
                + "          || _uri.endsWith(\".gif\")"
                + "          || _uri.endsWith(\".ico\");"
                + "        if (!_skip) {"
                + "          String _auth = _req.getHeader(\"Authorization\");"
                + "          if (_auth != null && _auth.startsWith(\"Bearer \")) {"
                + "            String _token = _auth.substring(7).trim();"
                + "            String _ep = System.getProperty(\""
                +                   JeusLegacyWeavingStrategy.PROP_ENDPOINT + "\", \"\");"
                + "            String _key = System.getProperty(\""
                +                   JeusLegacyWeavingStrategy.PROP_API_KEY + "\", \"\");"
                + "            if (_ep.length() > 0 && _token.length() > 0) {"
                + "              java.net.URL _url = new java.net.URL(_ep + \"/v1/token/verify\");"
                + "              java.net.HttpURLConnection _c ="
                + "                  (java.net.HttpURLConnection) _url.openConnection();"
                + "              _c.setRequestMethod(\"POST\");"
                + "              _c.setDoOutput(true);"
                + "              _c.setConnectTimeout(5000);"
                + "              _c.setReadTimeout(10000);"
                + "              _c.setRequestProperty(\"Content-Type\","
                + "                  \"application/json;charset=UTF-8\");"
                + "              _c.setRequestProperty(\"X-OnePass-ApiKey\", _key);"
                + "              String _body = \"{\\\"token\\\":\\\"\" + _token"
                + "                  + \"\\\",\\\"apiKey\\\":\\\"\" + _key + \"\\\"}\";"
                + "              byte[] _b = _body.getBytes(\"UTF-8\");"
                + "              _c.setRequestProperty(\"Content-Length\","
                + "                  Integer.toString(_b.length));"
                + "              java.io.OutputStream _os = _c.getOutputStream();"
                + "              _os.write(_b);"
                + "              _os.flush();"
                + "              _os.close();"
                + "              int _sc = _c.getResponseCode();"
                + "              _c.disconnect();"
                + "              if (_sc < 200 || _sc >= 300) {"
                + "                _res.sendError(401);"
                + "                return;"
                + "              }"
                + "            }"
                + "          }"
                + "        }"
                + "      }"
                + "    } catch (Throwable _t) {"
                + "      System.err.println(\"[WARN][OnePass-JEUS6] 토큰 검증 예외: \""
                + "          + _t.getMessage());"
                + "    }"
                + "  }"
                + "}";
            // @formatter:on
        }
    }
}

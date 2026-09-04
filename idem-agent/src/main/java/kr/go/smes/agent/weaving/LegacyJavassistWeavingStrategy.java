package kr.go.smes.agent.weaving;

import kr.go.smes.agent.config.AgentConfig;
import kr.go.smes.agent.was.WasType;
import kr.go.smes.agent.weaving.engine.JavassistWeavingEngine;

import java.io.PrintStream;
import java.lang.instrument.Instrumentation;

/**
 * 레거시 WAS(JDK 6~7) Javassist 공통 위빙 전략.
 *
 * <h2>적용 대상</h2>
 * <ul>
 *   <li>{@link WasType#JBOSS_LEGACY} — JBoss AS 5/6 (JDK 6~7)</li>
 *   <li>{@link WasType#WEBLOGIC_LEGACY} — WebLogic 10.x/11g/12c 초기 (JDK 6~7)</li>
 *   <li>{@link WasType#WEBSPHERE_LEGACY} — IBM WebSphere 7/8 (JDK 6~7)</li>
 *   <li>{@link WasType#JETTY_LEGACY} — Jetty 7/8 (JDK 7)</li>
 * </ul>
 *
 * <h2>위빙 전략</h2>
 * <p>byte-buddy는 JDK 8 런타임을 요구하므로 JDK 6~7 환경에서 사용 불가.
 * Javassist 소스코드 문자열 삽입 방식으로 {@code javax.servlet.Filter#doFilter()}를 위빙한다.
 *
 * <h2>공통 위빙 포인트: javax.servlet.Filter#doFilter()</h2>
 * <p>모든 레거시 WAS가 Servlet 2.4~3.0 표준을 준수하므로
 * {@code javax.servlet.Filter#doFilter()} 위빙이 공통으로 적용 가능하다.
 *
 * <h2>WAS별 특이사항</h2>
 * <dl>
 *   <dt>JBoss AS 5/6</dt>
 *   <dd>내부 Tomcat 컨테이너(JBoss Web) 기반. org.jboss.web.tomcat 패키지 존재.
 *       JBoss의 복잡한 클래스로더 계층으로 인해 Bootstrap ClassLoader에서 Filter가 보이지 않을 수 있음.</dd>
 *
 *   <dt>WebLogic 10.x/11g/12c 초기</dt>
 *   <dd>WebLogic Filtering ClassLoader가 특정 패키지를 필터링.
 *       {@code weblogic.servlet.internal.FilterChainImpl}이 실제 doFilter() 구현.
 *       Agent 클래스와 WAS 클래스 간 가시성 이슈 발생 가능.
 *       IBM JVM(J9) 사용 시 JVM TI 동작 차이 주의.</dd>
 *
 *   <dt>WebSphere 7/8</dt>
 *   <dd>IBM WebSphere는 기본적으로 IBM J9 JVM 사용.
 *       com.ibm.ws.webcontainer.filter.WebAppFilterManager가 Filter 체인 관리.
 *       OSGi 기반 런타임(WAS Liberty 포함)에서는 각 번들별 별도 ClassLoader.</dd>
 *
 *   <dt>Jetty 7/8</dt>
 *   <dd>org.mortbay.jetty(구형) 또는 org.eclipse.jetty(신형) 패키지.
 *       javax.servlet.Filter 표준 준수.</dd>
 * </dl>
 *
 * <h2>Fail-Open 정책</h2>
 * <p>Javassist 위빙 실패 시 {@link GenericFilterWeavingStrategy} Fallback으로 전환.
 * WAS 기동이 멈추지 않도록 예외를 로그로만 기록하고 계속 진행한다.
 */
public final class LegacyJavassistWeavingStrategy implements WeavingStrategy {

    private static final String FILTER_JAVAX = "javax.servlet.Filter";
    private static final String DO_FILTER    = "doFilter";

    private final WasType     wasType;
    private final AgentConfig config;
    private final PrintStream log;

    public LegacyJavassistWeavingStrategy(WasType wasType, AgentConfig config, PrintStream log) {
        this.wasType = wasType;
        this.config  = config;
        this.log     = log;
    }

    @Override
    public void install(Instrumentation inst) {
        log.println("[LegacyJavassist] " + wasType + " Javassist 위빙 시작");
        log.println("[LegacyJavassist] 위빙 포인트: " + FILTER_JAVAX + "#" + DO_FILTER);

        // System Property Bridge: JDK 1.4 호환 방식으로 설정 공유
        System.setProperty("onepass.agent.endpoint", config.endpoint());
        System.setProperty("onepass.agent.api-key",  config.apiKey());
        System.setProperty("onepass.agent.enabled",  String.valueOf(config.isEnabled()));

        // WAS별 추가 힌트 프로퍼티
        System.setProperty("onepass.was.type.detected", wasType.name());

        // Javassist 소스코드 삽입 (JDK 1.4 호환 문법)
        // javax.servlet.Filter#doFilter(ServletRequest, ServletResponse, FilterChain)
        String beforeCode = buildFilterBeforeCode();

        JavassistWeavingEngine engine = new JavassistWeavingEngine(log);

        try {
            final String capturedBeforeCode = beforeCode;
            engine.install(inst,
                    new JavassistWeavingEngine.JavassistClassFileTransformer(log) {
                        @Override
                        public String targetClassName() { return FILTER_JAVAX; }

                        @Override
                        public String targetMethodName() { return DO_FILTER; }

                        @Override
                        public String buildInsertBeforeSource(
                                String targetClassName, String targetMethodName) {
                            return capturedBeforeCode;
                        }
                    });

            log.println("[LegacyJavassist] " + wasType + " 위빙 설치 완료");

        } catch (Exception e) {
            log.println("[WARN] [LegacyJavassist] " + wasType + " Javassist 위빙 실패: " + e.getMessage());
            log.println("[LegacyJavassist] GenericFilter Fallback으로 전환 (byte-buddy)");
            // byte-buddy 사용 가능한 환경이면 GenericFilterWeavingStrategy 시도
            try {
                new GenericFilterWeavingStrategy(config, log).install(inst);
            } catch (Exception fallbackEx) {
                log.println("[WARN] [LegacyJavassist] GenericFilter Fallback도 실패: " + fallbackEx.getMessage());
                log.println("[LegacyJavassist] Fail-Open: Agent 비활성화, WAS 기동 계속");
            }
        }
    }

    /**
     * javax.servlet.Filter#doFilter() 진입 직전 삽입할 Javassist 소스코드.
     *
     * <p>JDK 1.4 호환 문법 규칙:
     * <ul>
     *   <li>제너릭 사용 불가 (List&lt;String&gt; → List)</li>
     *   <li>삼항 연산자 가능 (단, 타입 추론 주의)</li>
     *   <li>auto-boxing/unboxing 불가 → 명시적 형변환</li>
     *   <li>varargs 불가</li>
     *   <li>향상된 for 불가</li>
     *   <li>try-with-resources 불가</li>
     *   <li>람다 불가</li>
     * </ul>
     *
     * <p>$1 = first param (ServletRequest), $2 = second param (ServletResponse)
     */
    private String buildFilterBeforeCode() {
        return
            "String _enabled = System.getProperty(\"onepass.agent.enabled\", \"false\");" +
            "if (\"true\".equals(_enabled)) {" +
            "  if ($1 instanceof javax.servlet.http.HttpServletRequest) {" +
            "    javax.servlet.http.HttpServletRequest _req =" +
            "        (javax.servlet.http.HttpServletRequest) $1;" +
            "    String _uri = _req.getRequestURI();" +
            "    boolean _bypass = false;" +
            "    if (_uri != null) {" +
            "      if (_uri.startsWith(\"/actuator\")) { _bypass = true; }" +
            "      if (\"/health\".equals(_uri)) { _bypass = true; }" +
            "      if (\"/favicon.ico\".equals(_uri)) { _bypass = true; }" +
            "      int _dot = _uri.lastIndexOf('.');" +
            "      if (_dot >= 0) {" +
            "        String _ext = _uri.substring(_dot + 1).toLowerCase();" +
            "        if (\"css\".equals(_ext) || \"js\".equals(_ext) || \"png\".equals(_ext) ||" +
            "            \"jpg\".equals(_ext) || \"gif\".equals(_ext) || \"ico\".equals(_ext) ||" +
            "            \"html\".equals(_ext) || \"htm\".equals(_ext)) { _bypass = true; }" +
            "      }" +
            "    }" +
            "    if (!_bypass) {" +
            "      String _auth = _req.getHeader(\"Authorization\");" +
            "      if (_auth != null && _auth.startsWith(\"Bearer \")) {" +
            "        String _token = _auth.substring(7).trim();" +
            "        String _ep  = System.getProperty(\"onepass.agent.endpoint\", \"\");" +
            "        String _key = System.getProperty(\"onepass.agent.api-key\", \"\");" +
            "        if (_ep.length() > 0 && _token.length() > 0) {" +
            "          try {" +
            "            java.net.URL _url = new java.net.URL(_ep + \"/api/agent/verify\");" +
            "            java.net.HttpURLConnection _conn =" +
            "                (java.net.HttpURLConnection) _url.openConnection();" +
            "            _conn.setRequestMethod(\"POST\");" +
            "            _conn.setRequestProperty(\"Content-Type\", \"application/json; charset=UTF-8\");" +
            "            _conn.setRequestProperty(\"X-API-Key\", _key);" +
            "            _conn.setDoOutput(true);" +
            "            _conn.setConnectTimeout(3000);" +
            "            _conn.setReadTimeout(5000);" +
            "            _conn.setUseCaches(false);" +
            "            String _body = \"{\\\"token\\\":\\\"\" + _token.replace(\"\\\\\\\\\", \"\\\\\\\\\\\\\\\\\").replace(\"\\\"\", \"\\\\\\\"\") + \"\\\"}\";" +
            "            byte[] _bytes = _body.getBytes(\"UTF-8\");" +
            "            _conn.setRequestProperty(\"Content-Length\", Integer.toString(_bytes.length));" +
            "            java.io.OutputStream _os = _conn.getOutputStream();" +
            "            _os.write(_bytes);" +
            "            _os.flush();" +
            "            _os.close();" +
            "            int _status = _conn.getResponseCode();" +
            "            _conn.disconnect();" +
            "            if (_status == 401) {" +
            "              if ($2 instanceof javax.servlet.http.HttpServletResponse) {" +
            "                ((javax.servlet.http.HttpServletResponse) $2).sendError(401);" +
            "                return;" +
            "              }" +
            "            }" +
            "          } catch (Exception _ex) {" +
            "            System.err.println(\"[OnePass-Agent] \" + System.getProperty(\"onepass.was.type.detected\", \"LEGACY\") + \" 위빙 오류: \" + _ex.getMessage());" +
            "          }" +
            "        }" +
            "      }" +
            "    }" +
            "  }" +
            "}";
    }

    @Override
    public String name() {
        return "LegacyJavassistWeaving (" + wasType + ")";
    }
}

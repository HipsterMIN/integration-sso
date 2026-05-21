package kr.go.smes.agent.weaving.jeus;

import kr.go.smes.agent.config.AgentConfig;
import kr.go.smes.agent.http.OnePassHttpClient;
import kr.go.smes.agent.weaving.WeavingInstallException;
import kr.go.smes.agent.weaving.WeavingStrategy;
import kr.go.smes.agent.weaving.engine.JavassistWeavingEngine;

import java.io.PrintStream;
import java.lang.instrument.Instrumentation;

/**
 * JEUS 4/5 전용 위빙 전략 — Javassist 기반 (JDK 1.5 호환).
 *
 * <h2>대상 환경</h2>
 * <ul>
 *   <li>TmaxSoft JEUS 4: JDK 1.4~1.5, J2EE 1.3, Servlet 2.3</li>
 *   <li>TmaxSoft JEUS 5: JDK 1.4~1.5, J2EE 1.4, Servlet 2.4</li>
 *   <li>클래스 패키지: {@code com.tmax.jeus.*} (구 패키지)</li>
 * </ul>
 *
 * <h2>왜 Javassist인가?</h2>
 * <p>byte-buddy 1.17.x는 JDK 8 런타임을 필요로 한다.
 * JEUS 4/5가 실행되는 JDK 1.4~1.5 환경에서는 byte-buddy 클래스 로드 시
 * {@link UnsupportedClassVersionError}가 발생하여 Agent 자체가 시작되지 않는다.
 * Javassist 3.x는 JDK 1.3+ 호환이므로 이 환경에서 유일한 대안이다.
 *
 * <h2>JDK 1.5 Agent 가능성 증명</h2>
 * <ul>
 *   <li>{@code java.lang.instrument.Premain-Class}: JDK 1.5에 도입(JSR-163) ✅</li>
 *   <li>{@code Instrumentation.addTransformer()}: JDK 1.5 도입 ✅</li>
 *   <li>동적 어태치({@code agentmain}): JDK 1.6 도입 → JDK 1.5 불가 ❌</li>
 *   <li>결론: {@code -javaagent:} 플래그로 JVM 기동 시 {@code premain}에서만 동작</li>
 * </ul>
 *
 * <h2>위빙 포인트 전략</h2>
 * <p>JEUS 4/5는 Servlet 2.3/2.4 표준으로, 현대 Servlet API와 달리
 * {@code javax.servlet.Filter}의 역할이 제한적이다.
 * JEUS 4/5의 HTTP 요청 처리 핵심 클래스인 {@code com.tmax.jeus.web.servlet.HttpServletWrapper}의
 * {@code service()} 메서드에 Before-Advice를 삽입한다.
 *
 * <p>대체 위빙 포인트 (우선순위 순):
 * <ol>
 *   <li>{@code com.tmax.jeus.web.servlet.HttpServletWrapper#service} (주 위빙 포인트)</li>
 *   <li>{@code jeus.servlet.JeusServletEngine#service} (공통 내부 엔진)</li>
 *   <li>{@code com.tmax.jeus.server.JeusMain} 기반 Filter Chain (3순위 Fallback)</li>
 * </ol>
 *
 * <h2>Token Verification 코드 주입 방식</h2>
 * <p>위빙 코드는 Javassist 소스 코드 문자열로 대상 메서드에 삽입된다.
 * JEUS 4/5 ClassLoader 환경에서 실행되므로 OnePass Agent 클래스를 직접 참조하지 않고
 * {@link System#getProperty(String)}, {@link java.net.HttpURLConnection} 등
 * 순수 JDK API만 사용한다. Agent 설정은 System Property를 통해 공유한다.
 *
 * <h2>설치 실패 정책</h2>
 * <p>레거시 환경에서 위빙 실패가 WAS 기동 자체를 막으면 안 된다.
 * 설치 실패 시 {@link WeavingInstallException}을 던지지 않고 경고 로그만 남긴다.
 */
public final class JeusLegacyWeavingStrategy implements WeavingStrategy {

    private static final String STRATEGY_NAME = "JeusLegacyWeaving (JEUS 4/5, Javassist)";

    // ── 위빙 대상 클래스 후보 (우선순위 순) ────────────────────────────────────
    /** JEUS 4/5 HTTP 서블릿 래퍼 — 주 위빙 포인트 */
    private static final String TARGET_HTTP_WRAPPER =
            "com.tmax.jeus.web.servlet.HttpServletWrapper";
    /** JEUS 공통 서블릿 엔진 — 대체 위빙 포인트 */
    private static final String TARGET_SERVLET_ENGINE =
            "jeus.servlet.JeusServletEngine";

    /** 위빙할 메서드 이름 */
    private static final String TARGET_METHOD = "service";

    /**
     * Agent 설정을 System Property로 공유하기 위한 키.
     *
     * <p>JEUS 4/5 ClassLoader 환경에서는 Agent 클래스를 직접 참조할 수 없으므로
     * System Property를 통해 endpoint, apiKey를 전달한다.
     * Javassist 소스 코드 문자열 내에서 {@code System.getProperty()} 로 읽는다.
     */
    static final String PROP_ENDPOINT = "onepass.agent.endpoint";
    static final String PROP_API_KEY  = "onepass.agent.api-key";
    static final String PROP_ENABLED  = "onepass.agent.enabled";

    private final AgentConfig config;
    private final PrintStream log;

    public JeusLegacyWeavingStrategy(AgentConfig config, PrintStream log) {
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
        System.setProperty(PROP_ENDPOINT, config.endpoint());
        System.setProperty(PROP_API_KEY,  config.apiKey());
        System.setProperty(PROP_ENABLED,  String.valueOf(config.isEnabled()));

        log.println("[" + STRATEGY_NAME + "] 설치 시작 — JEUS 4/5 (JDK 1.5 호환 Javassist 위빙)");
        log.println("[" + STRATEGY_NAME + "] endpoint=" + config.endpoint());

        JavassistWeavingEngine engine = new JavassistWeavingEngine(log);

        // ── 주 위빙 포인트: HttpServletWrapper ──────────────────────────────────
        boolean primaryInstalled = tryInstall(engine, inst, TARGET_HTTP_WRAPPER, TARGET_METHOD);

        // ── 대체 위빙 포인트: JeusServletEngine ─────────────────────────────────
        if (!primaryInstalled) {
            log.println("[" + STRATEGY_NAME + "] 주 위빙 포인트 미발견 → 대체 포인트 시도: "
                    + TARGET_SERVLET_ENGINE);
            boolean fallbackInstalled = tryInstall(engine, inst, TARGET_SERVLET_ENGINE, TARGET_METHOD);

            if (!fallbackInstalled) {
                // 위빙 실패가 WAS 기동을 막으면 안 됨 → 예외 없이 경고만
                log.println("[WARN] [" + STRATEGY_NAME + "] 모든 위빙 포인트 실패 — "
                        + "OnePass SSO 기능이 비활성화됩니다. "
                        + "JEUS 4/5 버전 확인 필요: -Donepass.was.type=JEUS_LEGACY");
                return;
            }
        }

        log.println("[" + STRATEGY_NAME + "] 설치 완료 (JEUS 4/5 JDK 1.5 호환 Javassist 위빙)");
    }

    /**
     * 지정 클래스/메서드에 Javassist 위빙 Transformer를 등록한다.
     *
     * @param engine          Javassist 위빙 엔진
     * @param inst            Instrumentation
     * @param targetClassName 위빙 대상 클래스 FQCN
     * @param targetMethod    위빙 대상 메서드 이름
     * @return 등록 성공 여부
     */
    private boolean tryInstall(
            JavassistWeavingEngine engine,
            Instrumentation inst,
            String targetClassName,
            String targetMethod) {
        try {
            JavassistWeavingEngine.JavassistClassFileTransformer transformer =
                    new JeusLegacyTransformer(targetClassName, targetMethod, log);
            engine.install(inst, transformer);
            log.println("[" + STRATEGY_NAME + "] Transformer 등록: "
                    + targetClassName + "#" + targetMethod);
            return true;
        } catch (Throwable t) {
            log.println("[WARN] [" + STRATEGY_NAME + "] Transformer 등록 실패 ("
                    + targetClassName + "): " + t.getMessage());
            return false;
        }
    }

    @Override
    public String name() {
        return STRATEGY_NAME;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Javassist 변환기 구현
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * JEUS 4/5 서블릿 메서드에 OnePass 토큰 검증 코드를 삽입하는 Javassist 변환기.
     *
     * <h2>삽입 코드 설계</h2>
     * <p>삽입되는 Javassist 소스 코드는 JEUS 4/5 ClassLoader 컨텍스트에서 실행된다.
     * OnePass Agent 클래스를 직접 참조할 수 없으므로 다음 제약을 따른다:
     * <ul>
     *   <li>순수 JDK 1.4+ API만 사용 ({@code java.net.HttpURLConnection},
     *       {@code java.io.*}, {@code System.getProperty()})</li>
     *   <li>제네릭 사용 불가 (JDK 1.4 바이트코드 타겟이므로 raw type 사용)</li>
     *   <li>try-catch 필수 — 토큰 검증 실패가 WAS 요청을 중단시키면 안 됨</li>
     * </ul>
     *
     * <h2>위빙 코드 동작 흐름</h2>
     * <pre>
     *   service(request, response) 진입
     *     → System.getProperty("onepass.agent.enabled") 확인
     *     → request.getHeader("Authorization") 추출
     *     → "Bearer " 접두사 확인
     *     → HttpURLConnection으로 OnePass 검증 API 호출 (POST)
     *     → 401 응답 시 response.sendError(401)
     *     → 모든 예외 catch → 경고 로그만 (WAS 요청 흐름 유지)
     * </pre>
     *
     * <h2>HMAC 서명 (JDK 1.4+)</h2>
     * <p>{@code javax.crypto.Mac}은 JDK 1.4부터 존재하므로 HMAC-SHA256 서명이 가능하다.
     * 그러나 Javassist 소스 코드 문자열의 복잡도를 줄이기 위해
     * 레거시 환경에서는 API Key만으로 검증한다 (HMAC은 선택적).
     */
    static final class JeusLegacyTransformer
            extends JavassistWeavingEngine.JavassistClassFileTransformer {

        private final String targetClass;
        private final String targetMethod;

        JeusLegacyTransformer(String targetClass, String targetMethod, PrintStream log) {
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
         * JEUS 4/5 service() 메서드 진입 직전에 삽입할 Javassist 소스 코드.
         *
         * <p>코드는 대상 클래스의 ClassLoader에서 실행되므로 JDK 1.4+ API만 사용한다.
         * {@code $1}은 첫 번째 인수(javax.servlet.ServletRequest),
         * {@code $2}는 두 번째 인수(javax.servlet.ServletResponse).
         *
         * <p>Javassist는 이 소스 문자열을 컴파일하여 바이트코드로 변환한다.
         * 단일 문자열에서 개행/들여쓰기는 컴파일에 영향 없으며 가독성을 위해 유지한다.
         */
        @Override
        public String buildInsertBeforeSource(String targetClassName, String targetMethodName) {
            // @formatter:off
            return "{"
                // 1. Agent 활성화 여부 확인 (System Property)
                + "  String _enabled = System.getProperty(\"" + PROP_ENABLED + "\", \"true\");"
                + "  if (!\"true\".equalsIgnoreCase(_enabled)) { /* disabled */ }"
                + "  else {"
                // 2. HTTP 요청 여부 확인 (HttpServletRequest cast 시도)
                + "    try {"
                + "      javax.servlet.http.HttpServletRequest _req = null;"
                + "      javax.servlet.http.HttpServletResponse _res = null;"
                + "      if ($1 instanceof javax.servlet.http.HttpServletRequest) {"
                + "        _req = (javax.servlet.http.HttpServletRequest) $1;"
                + "        _res = (javax.servlet.http.HttpServletResponse) $2;"
                + "      }"
                + "      if (_req != null) {"
                // 3. URI 기반 바이패스 체크
                + "        String _uri = _req.getRequestURI();"
                + "        boolean _bypass = (_uri != null && ("
                + "          _uri.startsWith(\"/actuator\")"
                + "          || _uri.equals(\"/health\")"
                + "          || _uri.equals(\"/favicon.ico\")"
                + "        ));"
                + "        if (!_bypass) {"
                // 4. Authorization 헤더에서 Bearer 토큰 추출
                + "          String _auth = _req.getHeader(\"Authorization\");"
                + "          if (_auth != null && _auth.startsWith(\"Bearer \")) {"
                + "            String _token = _auth.substring(7).trim();"
                + "            String _endpoint = System.getProperty(\"" + PROP_ENDPOINT + "\", \"\");"
                + "            String _apiKey  = System.getProperty(\"" + PROP_API_KEY + "\", \"\");"
                + "            if (_endpoint.length() > 0 && _token.length() > 0) {"
                // 5. HttpURLConnection으로 OnePass 검증 API 호출 (JDK 1.1+ API)
                + "              java.net.URL _url = new java.net.URL(_endpoint + \"/v1/token/verify\");"
                + "              java.net.HttpURLConnection _conn ="
                + "                  (java.net.HttpURLConnection) _url.openConnection();"
                + "              _conn.setRequestMethod(\"POST\");"
                + "              _conn.setDoOutput(true);"
                + "              _conn.setConnectTimeout(5000);"
                + "              _conn.setReadTimeout(10000);"
                + "              _conn.setRequestProperty(\"Content-Type\", \"application/json;charset=UTF-8\");"
                + "              _conn.setRequestProperty(\"X-OnePass-ApiKey\", _apiKey);"
                // JSON 본문 작성 (제네릭 없이 raw IO 사용)
                + "              String _body = \"{\\\"token\\\":\\\"\" + _token + \"\\\",\\\"apiKey\\\":\\\"\" + _apiKey + \"\\\"}\";"
                + "              byte[] _bodyBytes = _body.getBytes(\"UTF-8\");"
                + "              _conn.setRequestProperty(\"Content-Length\", Integer.toString(_bodyBytes.length));"
                + "              java.io.OutputStream _out = _conn.getOutputStream();"
                + "              _out.write(_bodyBytes);"
                + "              _out.flush();"
                + "              _out.close();"
                + "              int _status = _conn.getResponseCode();"
                + "              _conn.disconnect();"
                // 6. 인증 실패 시 HTTP 401 응답
                + "              if (_status < 200 || _status >= 300) {"
                + "                _res.sendError(401);"
                + "                return;"  // service() 메서드 조기 반환
                + "              }"
                + "            }"  // endpoint/token 유효성 종료
                + "          }"  // Bearer 토큰 추출 종료
                + "        }"  // bypass 체크 종료
                + "      }"  // HTTP 요청 확인 종료
                + "    } catch (Throwable _t) {"
                // 모든 예외 catch — 토큰 검증 실패가 WAS 요청을 중단시키면 안 됨
                + "      System.err.println(\"[WARN][OnePass-JEUS45] 토큰 검증 중 예외: \" + _t.getMessage());"
                + "    }"
                + "  }"  // enabled 체크 종료
                + "}";
            // @formatter:on
        }
    }
}

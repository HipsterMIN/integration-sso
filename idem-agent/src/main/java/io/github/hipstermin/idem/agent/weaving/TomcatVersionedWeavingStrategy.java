package io.github.hipstermin.idem.agent.weaving;

import io.github.hipstermin.idem.agent.config.AgentConfig;
import io.github.hipstermin.idem.agent.http.OnePassHttpClient;
import io.github.hipstermin.idem.agent.was.WasDetector;
import io.github.hipstermin.idem.agent.was.WasType;
import io.github.hipstermin.idem.agent.weaving.engine.JavassistWeavingEngine;
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
 * Apache Tomcat 버전별 분기 위빙 전략.
 *
 * <h2>버전별 위빙 포인트 및 엔진</h2>
 * <pre>
 *  Tomcat 버전    Servlet API   JDK     위빙 엔진       위빙 포인트
 *  ────────────────────────────────────────────────────────────────────────────────
 *  5.x/6.x        2.4~2.5      5~6     Javassist       ApplicationFilterChain.internalDoFilter()
 *  7.x            3.0          7       Javassist/BB    StandardContextValve.invoke()
 *  8.x/8.5        3.1          8       byte-buddy      StandardHostValve.invoke() (Catalina Valve)
 *  9.x            4.0          8+      byte-buddy      StandardHostValve.invoke() + javax.servlet.Filter
 *  10.x/11.x      5.0+/6.0+   11+     byte-buddy      jakarta.servlet.Filter (javax 없음)
 * </pre>
 *
 * <h2>Tomcat 6 특이사항</h2>
 * <ul>
 *   <li>APR(Apache Portable Runtime) 커넥터: AJP 요청이 Valve 체인 우회 가능</li>
 *   <li>ApplicationFilterChain.internalDoFilter()가 더 안정적인 위빙 포인트</li>
 *   <li>JDK 5~6에서 byte-buddy 사용 불가 → Javassist 강제</li>
 * </ul>
 *
 * <h2>Tomcat 7 특이사항</h2>
 * <ul>
 *   <li>JDK 7이면 Javassist 폴백, JDK 8+이면 byte-buddy 사용</li>
 *   <li>Servlet 3.0 AsyncContext 지원 — Async 요청 위빙 주의</li>
 *   <li>NIO 커넥터 기반 (org.apache.tomcat.util.net.NioEndpoint)</li>
 * </ul>
 *
 * <h2>Tomcat 8.x/8.5 특이사항</h2>
 * <ul>
 *   <li>JDK 8 필수 → byte-buddy 정상 동작</li>
 *   <li>8.5부터 HTTP/2(ALPN) 지원 시작 - 위빙 포인트 동일</li>
 *   <li>NIO2 기본 커넥터 (org.apache.tomcat.util.net.Nio2Endpoint)</li>
 *   <li>Spring Boot 1.x/2.x Embedded Tomcat 주요 버전</li>
 * </ul>
 *
 * <h2>Tomcat 9 특이사항</h2>
 * <ul>
 *   <li>Servlet 4.0 (javax 네임스페이스 마지막 버전)</li>
 *   <li>JMX Lifecycle 기반 동적 재설정 지원</li>
 *   <li>Spring Boot 2.x Embedded Tomcat</li>
 *   <li>Valve 위빙 + javax.servlet.Filter 이중 위빙</li>
 * </ul>
 *
 * <h2>Tomcat 10+ 특이사항 (jakarta 전환)</h2>
 * <ul>
 *   <li>javax.servlet.* 완전 제거 → jakarta.servlet.* 전용</li>
 *   <li>Tomcat 10.1: Servlet 6.0 (Jakarta EE 10)</li>
 *   <li>Tomcat 11: Servlet 6.1 (JDK 21+, Jakarta EE 11)</li>
 *   <li>Spring Boot 3.x Embedded Tomcat</li>
 *   <li>jakarta.servlet.Filter만 위빙 (javax 위빙 시도 시 NoClassDefFoundError)</li>
 * </ul>
 *
 * <h2>Spring Boot Embedded Tomcat 주의사항</h2>
 * <ul>
 *   <li>Embedded Tomcat은 WAR 배포 없이 JAR로 실행 → CATALINA_HOME 없음</li>
 *   <li>클래스패스에 Tomcat 클래스가 포함되어 있어 WasDetector가 정상 감지</li>
 *   <li>Spring Security FilterChain보다 Valve가 먼저 실행됨 → SSO 위빙에 유리</li>
 * </ul>
 */
public final class TomcatVersionedWeavingStrategy implements WeavingStrategy {

    // Catalina Valve 관련
    private static final String VALVE_CLASS          = "org.apache.catalina.Valve";
    private static final String INVOKE_METHOD        = "invoke";

    // javax.servlet.Filter (Tomcat 6~9)
    private static final String FILTER_JAVAX         = "javax.servlet.Filter";
    // jakarta.servlet.Filter (Tomcat 10+)
    private static final String FILTER_JAKARTA       = "jakarta.servlet.Filter";
    private static final String DO_FILTER_METHOD     = "doFilter";

    // Javassist 위빙 대상 (Tomcat 6 레거시)
    private static final String TARGET_TOMCAT6_CHAIN = "org.apache.catalina.core.ApplicationFilterChain";
    private static final String TARGET_TOMCAT6_METHOD = "internalDoFilter";

    // Javassist 위빙 대상 (Tomcat 7)
    private static final String TARGET_TOMCAT7_VALVE  = "org.apache.catalina.core.StandardContextValve";

    static volatile OnePassHttpClient sharedHttpClient;
    static volatile AgentConfig       sharedConfig;
    static volatile PrintStream        sharedLog;

    private final WasType     tomcatVersion;
    private final AgentConfig config;
    private final PrintStream log;

    public TomcatVersionedWeavingStrategy(WasType tomcatVersion, AgentConfig config, PrintStream log) {
        this.tomcatVersion = tomcatVersion;
        this.config        = config;
        this.log           = log;
    }

    @Override
    public void install(Instrumentation inst) {
        sharedHttpClient = new OnePassHttpClient(config);
        sharedConfig     = config;
        sharedLog        = log;

        log.println("[TomcatWeaving] 버전=" + tomcatVersion + " 위빙 시작");

        int jdkMajor = WasDetector.getRuntimeJdkMajor();
        log.println("[TomcatWeaving] 런타임 JDK=" + jdkMajor);

        switch (tomcatVersion) {
            case TOMCAT_LEGACY:
                installLegacyJavassist(inst, jdkMajor);
                break;
            case TOMCAT_7:
                installTomcat7(inst, jdkMajor);
                break;
            case TOMCAT_8:
            case TOMCAT_9:
                installTomcat8And9(inst);
                break;
            case TOMCAT_10_PLUS:
                installTomcat10Plus(inst);
                break;
            default:
                // 버전 미감지 TOMCAT → Catalina Valve + 이중 Filter 위빙
                installTomcatDefault(inst);
                break;
        }
    }

    /**
     * Tomcat 5.x/6.x — Javassist로 ApplicationFilterChain.internalDoFilter() 위빙.
     *
     * <p>JDK 5~6 환경에서 byte-buddy 사용 불가. Javassist 소스코드 문자열 방식으로
     * internalDoFilter() 시작 직전에 토큰 검증 코드를 삽입한다.
     *
     * <p>삽입 코드 예:
     * <pre>
     *   if ($1 instanceof javax.servlet.http.HttpServletRequest) {
     *       String _token = ((javax.servlet.http.HttpServletRequest)$1).getHeader("Authorization");
     *       // ... (System.getProperty 로 설정 조회)
     *   }
     * </pre>
     */
    private void installLegacyJavassist(Instrumentation inst, int jdkMajor) {
        log.println("[TomcatWeaving] Tomcat 5/6 Javassist 위빙 시작 (JDK " + jdkMajor + ")");

        // System Property Bridge: config 값을 공유
        System.setProperty("onepass.agent.endpoint", config.endpoint());
        System.setProperty("onepass.agent.api-key",  config.apiKey());
        System.setProperty("onepass.agent.enabled",  String.valueOf(config.isEnabled()));

        // Javassist 소스코드 삽입 — JDK 1.4 호환 문법 (삼항 연산자/제너릭/varargs 불가)
        String beforeCode =
            "if ($1 instanceof javax.servlet.http.HttpServletRequest) {" +
            "  javax.servlet.http.HttpServletRequest _req = (javax.servlet.http.HttpServletRequest)$1;" +
            "  String _enabled = System.getProperty(\"onepass.agent.enabled\", \"false\");" +
            "  if (\"true\".equals(_enabled)) {" +
            "    String _uri = _req.getRequestURI();" +
            "    if (_uri != null && !_uri.startsWith(\"/actuator\") && !\"/health\".equals(_uri)) {" +
            "      String _auth = _req.getHeader(\"Authorization\");" +
            "      if (_auth != null && _auth.startsWith(\"Bearer \")) {" +
            "        String _token = _auth.substring(7).trim();" +
            "        String _endpoint = System.getProperty(\"onepass.agent.endpoint\", \"\");" +
            "        String _apiKey   = System.getProperty(\"onepass.agent.api-key\", \"\");" +
            "        if (_endpoint.length() > 0 && _token.length() > 0) {" +
            "          try {" +
            "            java.net.URL _url = new java.net.URL(_endpoint + \"/api/agent/verify\");" +
            "            java.net.HttpURLConnection _conn = (java.net.HttpURLConnection)_url.openConnection();" +
            "            _conn.setRequestMethod(\"POST\");" +
            "            _conn.setRequestProperty(\"Content-Type\", \"application/json\");" +
            "            _conn.setRequestProperty(\"X-API-Key\", _apiKey);" +
            "            _conn.setDoOutput(true);" +
            "            _conn.setConnectTimeout(3000);" +
            "            _conn.setReadTimeout(5000);" +
            "            String _body = \"{\\\"token\\\":\\\"\" + _token + \"\\\"}\";" +
            "            byte[] _bytes = _body.getBytes(\"UTF-8\");" +
            "            java.io.OutputStream _os = _conn.getOutputStream();" +
            "            _os.write(_bytes);" +
            "            _os.close();" +
            "            int _status = _conn.getResponseCode();" +
            "            _conn.disconnect();" +
            "            if (_status == 401) {" +
            "              javax.servlet.http.HttpServletResponse _resp = " +
            "                (javax.servlet.http.HttpServletResponse)$2;" +
            "              _resp.sendError(401, \"OnePass SSO: Unauthorized\");" +
            "              return;" +
            "            }" +
            "          } catch (Exception _e) {" +
            "            System.err.println(\"[OnePass-Agent] Tomcat6 위빙 오류: \" + _e.getMessage());" +
            "          }" +
            "        }" +
            "      }" +
            "    }" +
            "  }" +
            "}";

        JavassistWeavingEngine engine = new JavassistWeavingEngine(log);
        try {
            final String capturedCode6 = beforeCode;
            engine.install(inst,
                    new JavassistWeavingEngine.JavassistClassFileTransformer(log) {
                        @Override
                        public String targetClassName()  { return TARGET_TOMCAT6_CHAIN; }
                        @Override
                        public String targetMethodName() { return TARGET_TOMCAT6_METHOD; }
                        @Override
                        public String buildInsertBeforeSource(
                                String targetClassName, String targetMethodName) {
                            return capturedCode6;
                        }
                    });
        } catch (Exception e) {
            log.println("[WARN] [TomcatWeaving] Tomcat 6 Javassist 위빙 실패 → GenericFilter Fallback: " + e.getMessage());
            // Fail-Open: Javassist 위빙 실패 시 GenericFilterWeavingStrategy로 폴백
            installGenericFilterFallback(inst);
        }
    }

    /**
     * Tomcat 7 — JDK 7이면 Javassist, JDK 8+이면 byte-buddy.
     *
     * <p>Tomcat 7은 Servlet 3.0을 지원하므로 javax.servlet.Filter 위빙이 가능.
     * JDK 7 환경에서 byte-buddy는 동작하지만 안정성이 낮으므로 Javassist 우선.
     */
    private void installTomcat7(Instrumentation inst, int jdkMajor) {
        if (jdkMajor >= 8) {
            log.println("[TomcatWeaving] Tomcat 7 + JDK 8+ → byte-buddy Catalina Valve 위빙");
            installCatalinaValveWeaving(inst, false);
        } else {
            log.println("[TomcatWeaving] Tomcat 7 + JDK 7 → Javassist StandardContextValve 위빙");
            installTomcat7Javassist(inst);
        }
    }

    /**
     * Tomcat 7 + JDK 7 Javassist 위빙 — StandardContextValve.invoke() 대상.
     */
    private void installTomcat7Javassist(Instrumentation inst) {
        System.setProperty("onepass.agent.endpoint", config.endpoint());
        System.setProperty("onepass.agent.api-key",  config.apiKey());
        System.setProperty("onepass.agent.enabled",  String.valueOf(config.isEnabled()));

        // StandardContextValve.invoke(org.apache.catalina.connector.Request, Response)
        // Tomcat 7의 Valve invoke 파라미터는 Tomcat 내부 Request/Response 타입
        String beforeCode =
            "String _enabled = System.getProperty(\"onepass.agent.enabled\", \"false\");" +
            "if (\"true\".equals(_enabled)) {" +
            "  try {" +
            "    String _uri = $1.getRequestURI();" +
            "    if (_uri != null && !_uri.startsWith(\"/actuator\") && !\"/health\".equals(_uri)) {" +
            "      String _auth = $1.getHeader(\"Authorization\");" +
            "      if (_auth != null && _auth.startsWith(\"Bearer \")) {" +
            "        String _token = _auth.substring(7).trim();" +
            "        String _ep = System.getProperty(\"onepass.agent.endpoint\", \"\");" +
            "        String _key = System.getProperty(\"onepass.agent.api-key\", \"\");" +
            "        if (_ep.length() > 0) {" +
            "          java.net.URL _url = new java.net.URL(_ep + \"/api/agent/verify\");" +
            "          java.net.HttpURLConnection _c = (java.net.HttpURLConnection)_url.openConnection();" +
            "          _c.setRequestMethod(\"POST\");" +
            "          _c.setRequestProperty(\"Content-Type\", \"application/json\");" +
            "          _c.setRequestProperty(\"X-API-Key\", _key);" +
            "          _c.setDoOutput(true);" +
            "          _c.setConnectTimeout(3000);" +
            "          _c.setReadTimeout(5000);" +
            "          byte[] _b = (\"{\\\"token\\\":\\\"\" + _token + \"\\\"}\").getBytes(\"UTF-8\");" +
            "          java.io.OutputStream _os = _c.getOutputStream();" +
            "          _os.write(_b); _os.close();" +
            "          int _s = _c.getResponseCode();" +
            "          _c.disconnect();" +
            "          if (_s == 401) { $2.setStatus(401); return; }" +
            "        }" +
            "      }" +
            "    }" +
            "  } catch (Exception _ex) {" +
            "    System.err.println(\"[OnePass-Agent] Tomcat7 위빙 오류: \" + _ex.getMessage());" +
            "  }" +
            "}";

        JavassistWeavingEngine engine = new JavassistWeavingEngine(log);
        try {
            final String capturedCode7 = beforeCode;
            engine.install(inst,
                    new JavassistWeavingEngine.JavassistClassFileTransformer(log) {
                        @Override
                        public String targetClassName()  { return TARGET_TOMCAT7_VALVE; }
                        @Override
                        public String targetMethodName() { return INVOKE_METHOD; }
                        @Override
                        public String buildInsertBeforeSource(
                                String targetClassName, String targetMethodName) {
                            return capturedCode7;
                        }
                    });
        } catch (Exception e) {
            log.println("[WARN] [TomcatWeaving] Tomcat 7 Javassist 위빙 실패 → GenericFilter Fallback: " + e.getMessage());
            installGenericFilterFallback(inst);
        }
    }

    /**
     * Tomcat 8.x/9.x — byte-buddy Catalina Valve + javax.servlet.Filter 이중 위빙.
     *
     * <p>Catalina Valve를 1차 위빙 포인트로, javax.servlet.Filter를 2차로 등록한다.
     * Valve가 Filter보다 앞에 실행되므로 Valve에서 SSO 처리 후 Filter는 Skip됨.
     * Valve 위빙 실패 시 Filter 위빙이 보완.
     */
    private void installTomcat8And9(Instrumentation inst) {
        log.println("[TomcatWeaving] Tomcat 8/9 byte-buddy 위빙 (Valve + javax.servlet.Filter)");

        try {
            // 1차: Catalina Valve 위빙
            installCatalinaValveWeaving(inst, false);
        } catch (Exception e) {
            log.println("[WARN] [TomcatWeaving] Valve 위빙 실패: " + e.getMessage());
        }

        try {
            // 2차: javax.servlet.Filter 보완 위빙
            installJavaxFilterWeaving(inst);
        } catch (Exception e) {
            log.println("[WARN] [TomcatWeaving] Filter 위빙 실패: " + e.getMessage());
        }
    }

    /**
     * Tomcat 10+ — byte-buddy jakarta.servlet.Filter 전용 위빙.
     *
     * <p>javax.servlet은 완전히 제거됐으므로 javax 위빙 시도 없음.
     * jakarta.servlet.Filter.doFilter()에 Advice를 삽입한다.
     */
    private void installTomcat10Plus(Instrumentation inst) {
        log.println("[TomcatWeaving] Tomcat 10+ byte-buddy 위빙 (jakarta.servlet.Filter 전용)");

        try {
            new AgentBuilder.Default()
                    .type(ElementMatchers.<TypeDescription>hasSuperType(
                            ElementMatchers.<TypeDescription>named(FILTER_JAKARTA)))
                    .transform(new AgentBuilder.Transformer() {
                        @Override
                        public DynamicType.Builder<?> transform(
                                DynamicType.Builder<?> builder,
                                TypeDescription typeDescription,
                                ClassLoader classLoader,
                                JavaModule module,
                                ProtectionDomain protectionDomain) {
                            return builder.visit(
                                    Advice.to(JakartaFilterAdvice.class)
                                            .on(ElementMatchers.named(DO_FILTER_METHOD)));
                        }
                    })
                    .with(new WeavingListener(log, "TomcatJakartaFilter"))
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .installOn(inst);

            log.println("[TomcatWeaving] Tomcat 10+ jakarta.servlet.Filter 위빙 완료");
        } catch (Exception e) {
            throw new WeavingInstallException("[TomcatWeaving] Tomcat 10+ jakarta Filter 설치 실패", e);
        }
    }

    /**
     * 버전 미감지 TOMCAT — Catalina Valve + javax/jakarta 이중 Filter 위빙.
     */
    private void installTomcatDefault(Instrumentation inst) {
        log.println("[TomcatWeaving] Tomcat(버전 미감지) — 기본 Valve + 이중 Filter 위빙");
        try { installCatalinaValveWeaving(inst, true); } catch (Exception e) {
            log.println("[WARN] [TomcatWeaving] Valve 위빙 실패: " + e.getMessage());
        }
        try { installJavaxFilterWeaving(inst); } catch (Exception e) {
            log.println("[WARN] [TomcatWeaving] javax Filter 위빙 실패: " + e.getMessage());
        }
        try { installJakartaFilterWeaving(inst); } catch (Exception e) {
            log.println("[WARN] [TomcatWeaving] jakarta Filter 위빙 실패: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 공통 위빙 헬퍼
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Catalina Valve byte-buddy 위빙.
     *
     * @param withJakarta jakarta.servlet.Filter 도 함께 등록할지 여부
     */
    private void installCatalinaValveWeaving(Instrumentation inst, boolean withJakarta) {
        AgentBuilder builder = new AgentBuilder.Default()
                .type(ElementMatchers.<TypeDescription>hasSuperType(
                        ElementMatchers.<TypeDescription>named(VALVE_CLASS)))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(
                            DynamicType.Builder<?> b,
                            TypeDescription typeDescription,
                            ClassLoader classLoader,
                            JavaModule module,
                            ProtectionDomain protectionDomain) {
                        return b.visit(Advice.to(TomcatValveAdvice.class)
                                .on(ElementMatchers.named(INVOKE_METHOD)));
                    }
                })
                .with(new WeavingListener(log, "TomcatCatalinaValve"))
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);

        if (withJakarta) {
            builder = builder
                    .type(ElementMatchers.<TypeDescription>hasSuperType(
                            ElementMatchers.<TypeDescription>named(FILTER_JAKARTA)))
                    .transform(new AgentBuilder.Transformer() {
                        @Override
                        public DynamicType.Builder<?> transform(
                                DynamicType.Builder<?> b,
                                TypeDescription typeDescription,
                                ClassLoader classLoader,
                                JavaModule module,
                                ProtectionDomain protectionDomain) {
                            return b.visit(Advice.to(JakartaFilterAdvice.class)
                                    .on(ElementMatchers.named(DO_FILTER_METHOD)));
                        }
                    });
        }

        builder.installOn(inst);
        log.println("[TomcatWeaving] Catalina Valve 위빙 완료" + (withJakarta ? " (+ jakarta Filter)" : ""));
    }

    private void installJavaxFilterWeaving(Instrumentation inst) {
        new AgentBuilder.Default()
                .type(ElementMatchers.<TypeDescription>hasSuperType(
                        ElementMatchers.<TypeDescription>named(FILTER_JAVAX)))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(
                            DynamicType.Builder<?> b,
                            TypeDescription typeDescription,
                            ClassLoader classLoader,
                            JavaModule module,
                            ProtectionDomain protectionDomain) {
                        return b.visit(Advice.to(TomcatValveAdvice.class)
                                .on(ElementMatchers.named(DO_FILTER_METHOD)));
                    }
                })
                .with(new WeavingListener(log, "TomcatJavaxFilter"))
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .installOn(inst);
        log.println("[TomcatWeaving] javax.servlet.Filter 위빙 완료");
    }

    private void installJakartaFilterWeaving(Instrumentation inst) {
        new AgentBuilder.Default()
                .type(ElementMatchers.<TypeDescription>hasSuperType(
                        ElementMatchers.<TypeDescription>named(FILTER_JAKARTA)))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(
                            DynamicType.Builder<?> b,
                            TypeDescription typeDescription,
                            ClassLoader classLoader,
                            JavaModule module,
                            ProtectionDomain protectionDomain) {
                        return b.visit(Advice.to(JakartaFilterAdvice.class)
                                .on(ElementMatchers.named(DO_FILTER_METHOD)));
                    }
                })
                .with(new WeavingListener(log, "TomcatJakartaFilter"))
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .installOn(inst);
        log.println("[TomcatWeaving] jakarta.servlet.Filter 위빙 완료");
    }

    /** Javassist/byte-buddy 위빙 모두 실패 시 Generic Filter Fallback */
    private void installGenericFilterFallback(Instrumentation inst) {
        log.println("[TomcatWeaving] GenericFilter Fallback 위빙 시작");
        new GenericFilterWeavingStrategy(config, log).install(inst);
    }

    @Override
    public String name() {
        return "TomcatVersionedWeaving (" + tomcatVersion + ")";
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Advice 클래스
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Tomcat Catalina Valve / javax.servlet.Filter Advice.
     * (Tomcat 8, 9 — javax 기반)
     */
    @SuppressWarnings("unused")
    public static class TomcatValveAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(
                @Advice.Argument(0) Object request,
                @Advice.Argument(1) Object response
        ) {
            AgentConfig cfg = TomcatVersionedWeavingStrategy.sharedConfig;
            if (cfg == null || !cfg.isEnabled()) return;

            PrintStream log = TomcatVersionedWeavingStrategy.sharedLog;

            try {
                String uri = extractUri(request);
                if (uri == null || shouldBypass(uri)) return;

                String token = extractBearerToken(request);
                if (token == null || token.isEmpty()) return;

                OnePassHttpClient client = TomcatVersionedWeavingStrategy.sharedHttpClient;
                if (client == null) return;

                String tokenJson = buildTokenJson(token, cfg.apiKey());
                OnePassHttpClient.HttpResponse resp = client.verifyToken(tokenJson);

                if (!resp.isSuccess()) {
                    setUnauthorized(response, resp.statusCode(), log);
                }
            } catch (Throwable t) {
                if (log != null) log.println("[WARN] [TomcatValveAdvice] 예외: " + t.getMessage());
            }
        }

        private static String extractUri(Object req) {
            try { return (String) req.getClass().getMethod("getRequestURI").invoke(req); }
            catch (Exception e) { return null; }
        }

        private static String extractBearerToken(Object req) {
            try {
                String auth = (String) req.getClass().getMethod("getHeader", String.class).invoke(req, "Authorization");
                if (auth == null || !auth.startsWith("Bearer ")) return null;
                return auth.substring(7).trim();
            } catch (Exception e) { return null; }
        }

        private static void setUnauthorized(Object resp, int status, PrintStream log) {
            try {
                // Valve 파라미터(Tomcat 내부 Response)는 setStatus() 사용
                try { resp.getClass().getMethod("setStatus", int.class).invoke(resp, 401); }
                catch (NoSuchMethodException e) {
                    // Filter 파라미터(HttpServletResponse)는 sendError() 사용
                    resp.getClass().getMethod("sendError", int.class).invoke(resp, 401);
                }
                if (log != null) log.println("[TomcatValveAdvice] 인증 실패 → 401 (upstream=" + status + ")");
            } catch (Exception ignored) {}
        }

        private static boolean shouldBypass(String uri) {
            if (uri.startsWith("/actuator") || uri.equals("/health") || uri.equals("/favicon.ico")) return true;
            int dot = uri.lastIndexOf('.');
            if (dot >= 0) {
                String ext = uri.substring(dot + 1).toLowerCase();
                return "css".equals(ext) || "js".equals(ext) || "html".equals(ext) || "htm".equals(ext)
                        || "png".equals(ext) || "jpg".equals(ext) || "jpeg".equals(ext) || "gif".equals(ext)
                        || "ico".equals(ext) || "svg".equals(ext) || "woff".equals(ext) || "woff2".equals(ext)
                        || "ttf".equals(ext) || "map".equals(ext) || "json".equals(ext);
            }
            return false;
        }

        private static String buildTokenJson(String token, String apiKey) {
            return "{\"token\":\"" + escapeJson(token) + "\",\"apiKey\":\"" + escapeJson(apiKey) + "\"}";
        }

        private static String escapeJson(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        }
    }

    /**
     * jakarta.servlet.Filter Advice (Tomcat 10+, WildFly 27+, Jakarta EE WAS).
     */
    @SuppressWarnings("unused")
    public static class JakartaFilterAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(
                @Advice.Argument(0) Object request,
                @Advice.Argument(1) Object response,
                @Advice.Argument(2) Object chain
        ) {
            AgentConfig cfg = TomcatVersionedWeavingStrategy.sharedConfig;
            if (cfg == null || !cfg.isEnabled()) return;

            PrintStream log = TomcatVersionedWeavingStrategy.sharedLog;

            try {
                String uri = extractUri(request);
                if (uri == null || TomcatValveAdvice.shouldBypass(uri)) return;

                String token = TomcatValveAdvice.extractBearerToken(request);
                if (token == null || token.isEmpty()) return;

                OnePassHttpClient client = TomcatVersionedWeavingStrategy.sharedHttpClient;
                if (client == null) return;

                String tokenJson = TomcatValveAdvice.buildTokenJson(token, cfg.apiKey());
                OnePassHttpClient.HttpResponse resp = client.verifyToken(tokenJson);

                if (!resp.isSuccess()) {
                    sendError(response, 401, log);
                }
            } catch (Throwable t) {
                if (log != null) log.println("[WARN] [JakartaFilterAdvice] 예외: " + t.getMessage());
            }
        }

        private static String extractUri(Object req) {
            try { return (String) req.getClass().getMethod("getRequestURI").invoke(req); }
            catch (Exception e) { return null; }
        }

        private static void sendError(Object resp, int status, PrintStream log) {
            try {
                resp.getClass().getMethod("sendError", int.class).invoke(resp, status);
                if (log != null) log.println("[JakartaFilterAdvice] 인증 실패 → 401");
            } catch (Exception ignored) {}
        }
    }
}

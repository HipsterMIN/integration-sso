package kr.go.smes.agent.was;

import java.io.PrintStream;

/**
 * WAS 런타임 유형 자동 감지기.
 *
 * <h2>감지 전략 (우선순위 순)</h2>
 * <ol>
 *   <li><b>시스템 프로퍼티</b>: {@code onepass.was.type} 명시적 오버라이드</li>
 *   <li><b>클래스패스 탐색</b>: WAS 고유 클래스의 {@link Class#forName(String)} 성공 여부</li>
 *   <li><b>시스템 프로퍼티 패턴</b>: {@code weblogic.Name}, {@code jboss.home.dir} 등</li>
 *   <li><b>환경 변수</b>: {@code CATALINA_HOME}, {@code JBOSS_HOME} 등</li>
 *   <li><b>Fallback</b>: 위 모든 수단 실패 시 {@link WasType#UNKNOWN}</li>
 * </ol>
 *
 * <h2>JDK 8 호환</h2>
 * <ul>
 *   <li>Reflection 미사용 (Class.forName 단순 탐색만)</li>
 *   <li>try-with-resources 미사용 (ClassLoader 불필요)</li>
 *   <li>순수 {@code java.lang} / {@code java.io} API만 사용</li>
 * </ul>
 *
 * <h2>스레드 안전성</h2>
 * 멱등(idempotent) 조회이며 결과를 캐싱하지 않는다.
 * premain 단계에서 단 1회 호출되므로 성능 영향 없음.
 */
public final class WasDetector {

    // ── 시스템 프로퍼티 오버라이드 키 ───────────────────────────────────────────
    private static final String OVERRIDE_PROP = "onepass.was.type";

    // ── WAS 고유 클래스 (클래스패스 탐색용) ───────────────────────────────────
    /** Tomcat: org.apache.catalina.startup.Catalina (Tomcat 7+ 공통) */
    private static final String CLS_TOMCAT_CATALINA = "org.apache.catalina.startup.Catalina";
    /** Tomcat: org.apache.catalina.Valve (파이프라인 밸브 인터페이스) */
    private static final String CLS_TOMCAT_VALVE    = "org.apache.catalina.Valve";

    /** JBoss/WildFly: org.jboss.as.server.Bootstrap */
    private static final String CLS_JBOSS_BOOTSTRAP = "org.jboss.as.server.Bootstrap";
    /** WildFly: org.wildfly.extension.undertow.UndertowService */
    private static final String CLS_WILDFLY_UNDERTOW = "org.wildfly.extension.undertow.UndertowService";

    /** WebLogic: weblogic.t3.srvr.T3Srvr */
    private static final String CLS_WEBLOGIC_SERVER = "weblogic.t3.srvr.T3Srvr";
    /** WebLogic: weblogic.Server (진입점 메인 클래스) */
    private static final String CLS_WEBLOGIC_MAIN   = "weblogic.Server";

    /** Undertow (Standalone): io.undertow.Undertow */
    private static final String CLS_UNDERTOW        = "io.undertow.Undertow";
    /** Undertow Servlet: io.undertow.servlet.api.DeploymentManager */
    private static final String CLS_UNDERTOW_SERVLET = "io.undertow.servlet.api.DeploymentManager";

    /** Jetty: org.eclipse.jetty.server.Server */
    private static final String CLS_JETTY_SERVER    = "org.eclipse.jetty.server.Server";
    /** Jetty 9 legacy: org.mortbay.jetty.Server */
    private static final String CLS_JETTY_LEGACY    = "org.mortbay.jetty.Server";

    // ── 시스템 프로퍼티 단서 ────────────────────────────────────────────────────
    private static final String PROP_WEBLOGIC_NAME  = "weblogic.Name";
    private static final String PROP_JBOSS_HOME     = "jboss.home.dir";
    private static final String PROP_WILDFLY_HOME   = "jboss.server.base.dir"; // WildFly도 동일
    private static final String PROP_CATALINA_HOME  = "catalina.home";

    // ── 환경 변수 단서 ──────────────────────────────────────────────────────────
    private static final String ENV_CATALINA_HOME   = "CATALINA_HOME";
    private static final String ENV_JBOSS_HOME      = "JBOSS_HOME";
    private static final String ENV_WEBLOGIC_HOME   = "WL_HOME";

    private WasDetector() {
        // 유틸리티 클래스 — 인스턴스화 금지
    }

    /**
     * WAS 런타임 유형을 감지해 반환한다.
     *
     * <p>감지 실패 시 {@link WasType#UNKNOWN}을 반환하며 예외를 던지지 않는다.
     * Agent의 premain 단계에서 JVM이 종료되면 안 되므로 방어적으로 설계됐다.
     *
     * @param log 감지 과정 출력에 사용할 PrintStream (보통 System.err)
     * @return 감지된 {@link WasType} — 절대 {@code null}이 아님
     */
    public static WasType detect(PrintStream log) {
        // ── 1단계: 명시적 오버라이드 ─────────────────────────────────────────
        String override = System.getProperty(OVERRIDE_PROP);
        if (override != null && !override.isEmpty()) {
            WasType overridden = parseOverride(override, log);
            if (overridden != null) {
                logInfo(log, "[WasDetector] 오버라이드 적용: -D" + OVERRIDE_PROP + "=" + override
                        + " → " + overridden);
                return overridden;
            }
            logWarn(log, "[WasDetector] 알 수 없는 WAS 유형 오버라이드: " + override
                    + " — 자동 감지로 전환");
        }

        // ── 2단계: 클래스패스 탐색 (신뢰도 최고) ────────────────────────────
        //  NOTE: 탐색 순서가 WAS 우선순위를 결정한다.
        //        WebLogic → JBoss → Tomcat → Undertow → Jetty

        if (isClassPresent(CLS_WEBLOGIC_SERVER) || isClassPresent(CLS_WEBLOGIC_MAIN)) {
            logInfo(log, "[WasDetector] WebLogic 클래스 감지: " + CLS_WEBLOGIC_SERVER);
            return WasType.WEBLOGIC;
        }

        if (isClassPresent(CLS_JBOSS_BOOTSTRAP) || isClassPresent(CLS_WILDFLY_UNDERTOW)) {
            logInfo(log, "[WasDetector] JBoss/WildFly 클래스 감지");
            return WasType.JBOSS;
        }

        if (isClassPresent(CLS_TOMCAT_CATALINA) || isClassPresent(CLS_TOMCAT_VALVE)) {
            logInfo(log, "[WasDetector] Tomcat 클래스 감지: " + CLS_TOMCAT_CATALINA);
            return WasType.TOMCAT;
        }

        // Undertow standalone: JBoss보다 뒤에 체크 (WildFly도 Undertow 포함)
        if (isClassPresent(CLS_UNDERTOW) || isClassPresent(CLS_UNDERTOW_SERVLET)) {
            logInfo(log, "[WasDetector] Undertow 클래스 감지");
            return WasType.UNDERTOW;
        }

        if (isClassPresent(CLS_JETTY_SERVER) || isClassPresent(CLS_JETTY_LEGACY)) {
            logInfo(log, "[WasDetector] Jetty 클래스 감지");
            return WasType.JETTY;
        }

        // ── 3단계: 시스템 프로퍼티 패턴 ─────────────────────────────────────
        if (System.getProperty(PROP_WEBLOGIC_NAME) != null) {
            logInfo(log, "[WasDetector] 시스템 프로퍼티 감지: -D" + PROP_WEBLOGIC_NAME);
            return WasType.WEBLOGIC;
        }
        if (System.getProperty(PROP_JBOSS_HOME) != null
                || System.getProperty(PROP_WILDFLY_HOME) != null) {
            logInfo(log, "[WasDetector] 시스템 프로퍼티 감지: JBoss/WildFly home");
            return WasType.JBOSS;
        }
        if (System.getProperty(PROP_CATALINA_HOME) != null) {
            logInfo(log, "[WasDetector] 시스템 프로퍼티 감지: -D" + PROP_CATALINA_HOME);
            return WasType.TOMCAT;
        }

        // ── 4단계: 환경 변수 ──────────────────────────────────────────────────
        if (getEnv(ENV_WEBLOGIC_HOME) != null) {
            logInfo(log, "[WasDetector] 환경 변수 감지: " + ENV_WEBLOGIC_HOME);
            return WasType.WEBLOGIC;
        }
        if (getEnv(ENV_JBOSS_HOME) != null) {
            logInfo(log, "[WasDetector] 환경 변수 감지: " + ENV_JBOSS_HOME);
            return WasType.JBOSS;
        }
        if (getEnv(ENV_CATALINA_HOME) != null) {
            logInfo(log, "[WasDetector] 환경 변수 감지: " + ENV_CATALINA_HOME);
            return WasType.TOMCAT;
        }

        // ── 5단계: Fallback ────────────────────────────────────────────────────
        logWarn(log, "[WasDetector] WAS 자동 감지 실패 — UNKNOWN (Generic Servlet Filter Fallback)");
        return WasType.UNKNOWN;
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────────────────

    /**
     * 지정 클래스가 현재 클래스로더(또는 부트스트랩 클래스로더)에서 로드 가능한지 확인.
     *
     * <p>Agent는 부트스트랩 클래스로더 위에서 동작하므로, WAS 클래스를 직접 참조하지 않고
     * {@link Class#forName(String)} 성공 여부만으로 존재를 확인한다.
     * 이 방식은 ClassLoader 충돌 없이 안전하게 탐색한다.
     */
    private static boolean isClassPresent(String className) {
        try {
            // Thread ContextClassLoader → System ClassLoader 순으로 탐색
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl != null) {
                cl.loadClass(className);
                return true;
            }
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    /** WAS 유형 오버라이드 문자열을 파싱 (대소문자 무시) */
    private static WasType parseOverride(String value, PrintStream log) {
        try {
            return WasType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 환경 변수 읽기 (SecurityManager 예외 방어) */
    private static String getEnv(String name) {
        try {
            return System.getenv(name);
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private static void logInfo(PrintStream log, String message) {
        if (log != null) log.println(message);
    }

    private static void logWarn(PrintStream log, String message) {
        if (log != null) log.println("[WARN] " + message);
    }
}

package kr.go.smes.agent.was;

import java.io.File;
import java.io.PrintStream;
import java.net.URL;

/**
 * WAS 런타임 유형 자동 감지기 — 강화 버전.
 *
 * <h2>감지 전략 (우선순위 순)</h2>
 * <ol>
 *   <li><b>시스템 프로퍼티 오버라이드</b>: {@code onepass.was.type} 명시적 지정</li>
 *   <li><b>클래스패스 탐색 (고정밀)</b>: WAS 버전별 고유 클래스 {@link Class#forName(String)} 성공 여부
 *       — JEUS(버전 역순), Tomcat(버전 역순), JBoss/WildFly, WebLogic, WebSphere, GlassFish, Resin, Jetty, Undertow</li>
 *   <li><b>시스템 프로퍼티 패턴</b>: {@code jeus.home}, {@code catalina.home}, {@code weblogic.Name} 등</li>
 *   <li><b>환경 변수</b>: {@code JEUS_HOME}, {@code CATALINA_HOME}, {@code JBOSS_HOME} 등</li>
 *   <li><b>JVM 인수 스캔</b>: {@code sun.java.command}, {@code java.class.path} 패턴 분석</li>
 *   <li><b>파일시스템 힌트</b>: WAS 홈 디렉토리 내 고유 파일/디렉토리 존재 여부</li>
 *   <li><b>Fallback</b>: 위 모든 수단 실패 시 {@link WasType#UNKNOWN}</li>
 * </ol>
 *
 * <h2>감지 불가 WAS 대응 (Fail-Open)</h2>
 * <p>UNKNOWN 반환 시 GenericFilterWeavingStrategy(javax/jakarta 이중 위빙)가 Fallback으로 동작.
 * 대부분의 Servlet API 기반 WAS는 이 방식으로 SSO 처리 가능.
 *
 * <h2>스레드 안전성</h2>
 * 멱등(idempotent) 조회이며 결과를 캐싱하지 않는다.
 * premain 단계에서 단 1회 호출되므로 성능 영향 없음.
 */
public final class WasDetector {

    // ── 시스템 프로퍼티 오버라이드 키 ───────────────────────────────────────────
    private static final String OVERRIDE_PROP = "onepass.was.type";

    // ─────────────────────────────────────────────────────────────────────────────
    // JEUS 버전 판별 클래스 상수 (신→구 순)
    // ─────────────────────────────────────────────────────────────────────────────
    private static final String CLS_JEUS9_SERVLET_ENGINE  = "com.tmaxsoft.jeus.web.servlet.engine.JeusServletEngine9";
    private static final String CLS_JAKARTA_SERVLET       = "jakarta.servlet.ServletRequest";
    private static final String CLS_JEUS8_5_CONTAINER     = "com.tmaxsoft.jeus.web.servlet.JeusServlet4Container";
    private static final String CLS_JEUS8_5_HTTP2         = "com.tmaxsoft.jeus.web.http2.JeusHttp2Handler";
    private static final String CLS_JEUS8_CONNECTOR       = "com.tmaxsoft.jeus.web.connector.JeusConnector8";
    private static final String CLS_JEUS8_WEBSOCKET       = "com.tmaxsoft.jeus.web.websocket.JeusWebSocketHandler";
    private static final String CLS_JEUS7_DEPLOYER        = "com.tmaxsoft.jeus.web.deployer.JeusWebDeployer7";
    private static final String CLS_JEUS7_ASYNC           = "com.tmaxsoft.jeus.web.async.JeusAsyncContext";
    private static final String CLS_JEUS6_WEBCONTAINER    = "com.tmaxsoft.jeus.web.JeusWebContainer";
    private static final String CLS_JEUS6_SERVLET_HANDLER = "com.tmaxsoft.jeus.web.servlet.JeusServletHandler";
    private static final String CLS_JEUS45_HTTP_WRAPPER   = "com.tmax.jeus.web.servlet.HttpServletWrapper";
    private static final String CLS_JEUS45_ENGINE         = "com.tmax.jeus.util.engine.ServiceEngine";
    private static final String CLS_JEUS45_MAIN           = "com.tmax.jeus.server.JeusMain";
    private static final String CLS_JEUS_SERVLET_ENGINE   = "jeus.servlet.JeusServletEngine";
    private static final String CLS_JEUS_WEB_UTILS        = "jeus.util.JeusWebUtils";

    // ─────────────────────────────────────────────────────────────────────────────
    // Tomcat 버전 판별 클래스 상수 (신→구 순)
    // ─────────────────────────────────────────────────────────────────────────────
    // Tomcat 10+ (jakarta.servlet)
    private static final String CLS_TOMCAT10_CONTEXT      = "org.apache.catalina.core.StandardContext";
    private static final String CLS_TOMCAT10_NIO2         = "org.apache.tomcat.util.net.Nio2Endpoint";
    private static final String CLS_TOMCAT10_HTTP2        = "org.apache.coyote.http2.Http2Protocol";
    // Tomcat 9 특화 (javax.servlet, Servlet 4.0)
    private static final String CLS_TOMCAT9_EMBEDDED      = "org.apache.catalina.startup.Tomcat";
    private static final String CLS_TOMCAT9_SERVLET40     = "javax.servlet.http.HttpServletMapping";
    // Tomcat 8.5 특화 (HTTP/2, ALPN 지원 시작)
    private static final String CLS_TOMCAT85_ALPN         = "org.apache.tomcat.util.net.SSLUtil";
    private static final String CLS_TOMCAT85_UPGRADETOKEN = "org.apache.coyote.UpgradeToken";
    // Tomcat 8.0/8.x 특화 (Servlet 3.1, NIO2 기본)
    private static final String CLS_TOMCAT8_NIO2HANDLER   = "org.apache.tomcat.util.net.Nio2Channel";
    // Tomcat 7.x 특화 (Servlet 3.0 AsyncContext)
    private static final String CLS_TOMCAT7_ASYNC         = "org.apache.catalina.core.AsyncContextImpl";
    private static final String CLS_TOMCAT7_WSOCKET       = "org.apache.catalina.websocket.WebSocketServlet";
    // Tomcat 5/6 (구형)
    private static final String CLS_TOMCAT6_LEGACY        = "org.apache.catalina.util.RequestUtil";
    private static final String CLS_TOMCAT6_DEPLOY        = "org.apache.catalina.startup.HostConfig";
    // Tomcat 공통
    private static final String CLS_TOMCAT_CATALINA       = "org.apache.catalina.startup.Catalina";
    private static final String CLS_TOMCAT_VALVE          = "org.apache.catalina.Valve";
    private static final String CLS_TOMCAT_CONNECTOR      = "org.apache.catalina.connector.Connector";

    // ─────────────────────────────────────────────────────────────────────────────
    // JBoss / WildFly 클래스 상수
    // ─────────────────────────────────────────────────────────────────────────────
    private static final String CLS_WILDFLY_UNDERTOW      = "org.wildfly.extension.undertow.UndertowService";
    private static final String CLS_WILDFLY_BOOT          = "org.wildfly.security.WildFlySecurityManager";
    private static final String CLS_JBOSS_EAP7_BOOT      = "org.jboss.as.server.Bootstrap";
    private static final String CLS_JBOSS_EAP7_UNDERTOW  = "org.jboss.as.undertow.UndertowService";
    private static final String CLS_JBOSS_LEGACY_DEPLOY   = "org.jboss.web.tomcat.service.TomcatDeployer";
    private static final String CLS_JBOSS_LEGACY_MICRO    = "org.jboss.kernel.Kernel";

    // ─────────────────────────────────────────────────────────────────────────────
    // WebLogic 클래스 상수
    // ─────────────────────────────────────────────────────────────────────────────
    private static final String CLS_WEBLOGIC_T3SVR        = "weblogic.t3.srvr.T3Srvr";
    private static final String CLS_WEBLOGIC_SERVER       = "weblogic.Server";
    private static final String CLS_WEBLOGIC_FILTERCHAIN  = "weblogic.servlet.internal.FilterChainImpl";
    private static final String CLS_WEBLOGIC14_STARTUP    = "weblogic.server.ServerLifecycleListener";
    private static final String CLS_WEBLOGIC_LEGACY_BOOT  = "weblogic.management.runtime.ServerRuntimeMBean";

    // ─────────────────────────────────────────────────────────────────────────────
    // WebSphere 클래스 상수
    // ─────────────────────────────────────────────────────────────────────────────
    private static final String CLS_WAS_WEBCONTAINER      = "com.ibm.ws.webcontainer.WebContainer";
    private static final String CLS_WAS_FILTER_MGR        = "com.ibm.ws.webcontainer.filter.WebAppFilterManager";
    private static final String CLS_WAS_LIBERTY           = "com.ibm.ws.kernel.boot.Launcher";
    private static final String CLS_WAS_LEGACY_SERVER     = "com.ibm.websphere.management.AdminService";

    // ─────────────────────────────────────────────────────────────────────────────
    // GlassFish / Payara 클래스 상수
    // ─────────────────────────────────────────────────────────────────────────────
    private static final String CLS_GF_WEBCONTAINER       = "com.sun.enterprise.web.WebContainer";
    private static final String CLS_GF_GRIZZLY            = "org.glassfish.grizzly.http.server.HttpServer";
    private static final String CLS_GF_JAKARTA_LOG        = "org.glassfish.main.jul.handler.GlassFishLogHandler";
    private static final String CLS_PAYARA_EXECUTOR       = "fish.payara.micro.PayaraMicro";

    // ─────────────────────────────────────────────────────────────────────────────
    // Resin 클래스 상수
    // ─────────────────────────────────────────────────────────────────────────────
    private static final String CLS_RESIN_HTTP            = "com.caucho.server.http.HttpRequest";
    private static final String CLS_RESIN_SERVER          = "com.caucho.server.resin.Resin";
    private static final String CLS_RESIN_DISPATCH        = "com.caucho.server.dispatch.ServletInvocation";

    // ─────────────────────────────────────────────────────────────────────────────
    // Undertow 클래스 상수
    // ─────────────────────────────────────────────────────────────────────────────
    private static final String CLS_UNDERTOW              = "io.undertow.Undertow";
    private static final String CLS_UNDERTOW_SERVLET      = "io.undertow.servlet.api.DeploymentManager";

    // ─────────────────────────────────────────────────────────────────────────────
    // Jetty 클래스 상수
    // ─────────────────────────────────────────────────────────────────────────────
    private static final String CLS_JETTY12_EE10          = "org.eclipse.jetty.ee10.servlet.ServletHandler";
    private static final String CLS_JETTY11_EE9           = "org.eclipse.jetty.ee9.servlet.ServletHandler";
    private static final String CLS_JETTY_SERVER          = "org.eclipse.jetty.server.Server";
    private static final String CLS_JETTY_HANDLER         = "org.eclipse.jetty.servlet.ServletHandler";
    private static final String CLS_JETTY_LEGACY          = "org.mortbay.jetty.Server";

    // ─────────────────────────────────────────────────────────────────────────────
    // 시스템 프로퍼티 키
    // ─────────────────────────────────────────────────────────────────────────────
    private static final String PROP_JEUS_HOME        = "jeus.home";
    private static final String PROP_JEUS_SERVER_NAME = "jeus.server.name";
    private static final String PROP_JEUS_ENGINE_NAME = "jeus.engine.name";
    private static final String PROP_JEUS_VERSION     = "jeus.version";
    private static final String PROP_CATALINA_HOME    = "catalina.home";
    private static final String PROP_CATALINA_BASE    = "catalina.base";
    private static final String PROP_WEBLOGIC_NAME    = "weblogic.Name";
    private static final String PROP_WEBLOGIC_HOME    = "weblogic.home";
    private static final String PROP_JBOSS_HOME       = "jboss.home.dir";
    private static final String PROP_WILDFLY_HOME     = "jboss.server.base.dir";
    private static final String PROP_WAS_INSTALL      = "was.install.root";
    private static final String PROP_WAS_USER_DIR     = "user.install.root";
    private static final String PROP_RESIN_HOME       = "resin.home";
    private static final String PROP_GLASSFISH_HOME   = "com.sun.aas.instanceRoot";
    private static final String PROP_JAVA_COMMAND     = "sun.java.command";

    // ─────────────────────────────────────────────────────────────────────────────
    // 환경 변수 키
    // ─────────────────────────────────────────────────────────────────────────────
    private static final String ENV_JEUS_HOME        = "JEUS_HOME";
    private static final String ENV_CATALINA_HOME    = "CATALINA_HOME";
    private static final String ENV_JBOSS_HOME       = "JBOSS_HOME";
    private static final String ENV_WEBLOGIC_HOME    = "WL_HOME";
    private static final String ENV_WAS_HOME         = "WAS_HOME";
    private static final String ENV_RESIN_HOME       = "RESIN_HOME";
    private static final String ENV_GLASSFISH_HOME   = "AS_DEF_DOMAINS_PATH";
    private static final String ENV_PAYARA_HOME      = "PAYARA_HOME";

    private WasDetector() {}

    /**
     * WAS 런타임 유형을 감지해 반환한다.
     *
     * <p>감지 실패 시 {@link WasType#UNKNOWN}을 반환하며 예외를 던지지 않는다.
     *
     * @param log 감지 과정 출력에 사용할 PrintStream (null 허용)
     * @return 감지된 {@link WasType} — 절대 {@code null}이 아님
     */
    public static WasType detect(PrintStream log) {

        // ── 1단계: 명시적 오버라이드 ─────────────────────────────────────────
        String override = System.getProperty(OVERRIDE_PROP);
        if (override != null && !override.isEmpty()) {
            WasType overridden = parseOverride(override);
            if (overridden != null) {
                logInfo(log, "[WasDetector] 오버라이드 적용: -D" + OVERRIDE_PROP + "=" + override + " → " + overridden);
                return overridden;
            }
            logWarn(log, "[WasDetector] 알 수 없는 WAS 유형 오버라이드: " + override + " — 자동 감지로 전환");
        }

        // ── 2단계: 클래스패스 탐색 ──────────────────────────────────────────
        // JEUS 최우선 (한국 공공기관 특화)
        WasType jeusType = detectJeusByClasspath(log);
        if (jeusType != null) return jeusType;

        // Tomcat 버전별 감지
        WasType tomcatType = detectTomcatByClasspath(log);
        if (tomcatType != null) return tomcatType;

        // WildFly (JBoss 이전에 - 더 새로운 버전)
        WasType wildflyType = detectWildFlyByClasspath(log);
        if (wildflyType != null) return wildflyType;

        // JBoss
        WasType jbossType = detectJBossByClasspath(log);
        if (jbossType != null) return jbossType;

        // WebLogic 버전별
        WasType weblogicType = detectWebLogicByClasspath(log);
        if (weblogicType != null) return weblogicType;

        // WebSphere
        WasType websphereType = detectWebSphereByClasspath(log);
        if (websphereType != null) return websphereType;

        // GlassFish / Payara
        WasType glassfishType = detectGlassFishByClasspath(log);
        if (glassfishType != null) return glassfishType;

        // Resin
        if (isClassPresent(CLS_RESIN_SERVER) || isClassPresent(CLS_RESIN_HTTP) || isClassPresent(CLS_RESIN_DISPATCH)) {
            logInfo(log, "[WasDetector] Resin 클래스 감지: " + CLS_RESIN_SERVER);
            return WasType.RESIN;
        }

        // Undertow (standalone)
        if (isClassPresent(CLS_UNDERTOW) || isClassPresent(CLS_UNDERTOW_SERVLET)) {
            logInfo(log, "[WasDetector] Undertow 클래스 감지");
            return WasType.UNDERTOW;
        }

        // Jetty 버전별
        WasType jettyType = detectJettyByClasspath(log);
        if (jettyType != null) return jettyType;

        // ── 3단계: 시스템 프로퍼티 패턴 ────────────────────────────────────
        WasType jeusByProp = detectJeusBySystemProperty(log);
        if (jeusByProp != null) return jeusByProp;

        if (System.getProperty(PROP_CATALINA_HOME) != null || System.getProperty(PROP_CATALINA_BASE) != null) {
            logInfo(log, "[WasDetector] 시스템 프로퍼티 감지: catalina.home/base");
            return detectTomcatVersionByProperties(log);
        }
        if (System.getProperty(PROP_WEBLOGIC_NAME) != null || System.getProperty(PROP_WEBLOGIC_HOME) != null) {
            logInfo(log, "[WasDetector] 시스템 프로퍼티 감지: weblogic");
            return detectWebLogicVersionByProperties(log);
        }
        if (System.getProperty(PROP_JBOSS_HOME) != null || System.getProperty(PROP_WILDFLY_HOME) != null) {
            logInfo(log, "[WasDetector] 시스템 프로퍼티 감지: JBoss/WildFly home");
            return detectJBossVersionByProperties(log);
        }
        if (System.getProperty(PROP_WAS_INSTALL) != null || System.getProperty(PROP_WAS_USER_DIR) != null) {
            logInfo(log, "[WasDetector] 시스템 프로퍼티 감지: IBM WebSphere");
            return detectWebSphereVersionByProperties(log);
        }
        if (System.getProperty(PROP_RESIN_HOME) != null) {
            logInfo(log, "[WasDetector] 시스템 프로퍼티 감지: resin.home");
            return WasType.RESIN;
        }
        if (System.getProperty(PROP_GLASSFISH_HOME) != null) {
            logInfo(log, "[WasDetector] 시스템 프로퍼티 감지: GlassFish instanceRoot");
            return detectGlassFishVersionByProperties(log);
        }

        // ── 4단계: 환경 변수 ─────────────────────────────────────────────────
        WasType jeusByEnv = detectJeusByEnvironment(log);
        if (jeusByEnv != null) return jeusByEnv;

        if (getEnv(ENV_CATALINA_HOME) != null) {
            logInfo(log, "[WasDetector] 환경변수 감지: CATALINA_HOME=" + getEnv(ENV_CATALINA_HOME));
            return detectTomcatVersionFromPath(getEnv(ENV_CATALINA_HOME), log);
        }
        if (getEnv(ENV_WEBLOGIC_HOME) != null) {
            logInfo(log, "[WasDetector] 환경변수 감지: WL_HOME=" + getEnv(ENV_WEBLOGIC_HOME));
            return detectWebLogicVersionFromPath(getEnv(ENV_WEBLOGIC_HOME), log);
        }
        if (getEnv(ENV_JBOSS_HOME) != null) {
            logInfo(log, "[WasDetector] 환경변수 감지: JBOSS_HOME=" + getEnv(ENV_JBOSS_HOME));
            return detectJBossVersionFromPath(getEnv(ENV_JBOSS_HOME), log);
        }
        if (getEnv(ENV_WAS_HOME) != null) {
            logInfo(log, "[WasDetector] 환경변수 감지: WAS_HOME=" + getEnv(ENV_WAS_HOME));
            return detectWebSphereVersionFromPath(getEnv(ENV_WAS_HOME), log);
        }
        if (getEnv(ENV_RESIN_HOME) != null) {
            logInfo(log, "[WasDetector] 환경변수 감지: RESIN_HOME");
            return WasType.RESIN;
        }
        if (getEnv(ENV_GLASSFISH_HOME) != null || getEnv(ENV_PAYARA_HOME) != null) {
            logInfo(log, "[WasDetector] 환경변수 감지: GlassFish/Payara");
            return WasType.GLASSFISH;
        }

        // ── 5단계: JVM 인수 / 클래스패스 문자열 스캔 ────────────────────────
        WasType byJvmArgs = detectByJvmArgs(log);
        if (byJvmArgs != null) return byJvmArgs;

        // ── 6단계: 파일시스템 힌트 ──────────────────────────────────────────
        WasType byFilesystem = detectByFilesystem(log);
        if (byFilesystem != null) return byFilesystem;

        // ── Fallback ─────────────────────────────────────────────────────────
        logWarn(log, "[WasDetector] WAS 자동 감지 실패 — UNKNOWN (Generic Servlet Filter Fallback)");
        logWarn(log, "[WasDetector] WAS를 수동 지정하려면: -Donepass.was.type=<WAS_TYPE>");
        logWarn(log, "[WasDetector] 지원 WAS_TYPE: JEUS_7, JEUS_8, JEUS_9_PLUS, TOMCAT_8, TOMCAT_9, TOMCAT_10_PLUS,");
        logWarn(log, "[WasDetector]               JBOSS, WILDFLY, WEBLOGIC, WEBSPHERE, GLASSFISH, RESIN, JETTY, UNDERTOW");
        return WasType.UNKNOWN;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // JEUS 전용 감지 메서드 (기존 유지 + 개선)
    // ─────────────────────────────────────────────────────────────────────────────

    private static WasType detectJeusByClasspath(PrintStream log) {
        if (isClassPresent(CLS_JEUS9_SERVLET_ENGINE)) {
            logInfo(log, "[WasDetector] JEUS 9+ 클래스 감지: " + CLS_JEUS9_SERVLET_ENGINE);
            return WasType.JEUS_9_PLUS;
        }
        if (isClassPresent(CLS_JAKARTA_SERVLET)
                && (isClassPresent(CLS_JEUS_SERVLET_ENGINE) || isClassPresent(CLS_JEUS_WEB_UTILS))) {
            logInfo(log, "[WasDetector] JEUS 9+ 감지 (jakarta.servlet + JEUS 공통 클래스 병행)");
            return WasType.JEUS_9_PLUS;
        }
        if (isClassPresent(CLS_JEUS8_5_CONTAINER) || isClassPresent(CLS_JEUS8_5_HTTP2)) {
            logInfo(log, "[WasDetector] JEUS 8.5 클래스 감지");
            return WasType.JEUS_8_5;
        }
        if (isClassPresent(CLS_JEUS8_CONNECTOR) || isClassPresent(CLS_JEUS8_WEBSOCKET)) {
            logInfo(log, "[WasDetector] JEUS 8 클래스 감지");
            return WasType.JEUS_8;
        }
        if (isClassPresent(CLS_JEUS7_DEPLOYER) || isClassPresent(CLS_JEUS7_ASYNC)) {
            logInfo(log, "[WasDetector] JEUS 7 클래스 감지");
            return WasType.JEUS_7;
        }
        if (isClassPresent(CLS_JEUS6_WEBCONTAINER) || isClassPresent(CLS_JEUS6_SERVLET_HANDLER)) {
            logInfo(log, "[WasDetector] JEUS 6 클래스 감지");
            return WasType.JEUS_6;
        }
        if (isClassPresent(CLS_JEUS45_HTTP_WRAPPER) || isClassPresent(CLS_JEUS45_ENGINE) || isClassPresent(CLS_JEUS45_MAIN)) {
            logInfo(log, "[WasDetector] JEUS 4/5 (Legacy) 클래스 감지: com.tmax.jeus.*");
            return WasType.JEUS_LEGACY;
        }
        if (isClassPresent(CLS_JEUS_SERVLET_ENGINE) || isClassPresent(CLS_JEUS_WEB_UTILS)) {
            logInfo(log, "[WasDetector] JEUS 공통 클래스 감지 (버전 특화 클래스 없음) — 버전 프로퍼티로 분기");
            return resolveJeusVersionFromProperty(log);
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Tomcat 버전별 감지 메서드 (신규 구현)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Tomcat 버전을 클래스패스 탐색으로 판별한다.
     *
     * <p>탐색 순서: Tomcat 10+ → 9 → 8.5 → 8 → 7 → 6(Legacy) → 공통
     *
     * <h3>Tomcat 버전별 핵심 판별 포인트</h3>
     * <ul>
     *   <li>Tomcat 10+: {@code jakarta.servlet.*} 존재 + Tomcat Catalina 클래스 병행</li>
     *   <li>Tomcat 9: {@code javax.servlet.http.HttpServletMapping} 존재 (Servlet 4.0 고유)</li>
     *   <li>Tomcat 8.5: {@code org.apache.coyote.UpgradeToken} (HTTP 업그레이드 핸들러)</li>
     *   <li>Tomcat 8: {@code org.apache.tomcat.util.net.Nio2Channel} (NIO2 기본 커넥터)</li>
     *   <li>Tomcat 7: {@code org.apache.catalina.core.AsyncContextImpl} (Servlet 3.0 Async)</li>
     *   <li>Tomcat 6: {@code org.apache.catalina.util.RequestUtil} (구형 유틸, 7+에서 제거)</li>
     * </ul>
     */
    private static WasType detectTomcatByClasspath(PrintStream log) {
        // Tomcat 존재 여부 먼저 확인
        boolean isTomcat = isClassPresent(CLS_TOMCAT_CATALINA)
                || isClassPresent(CLS_TOMCAT_VALVE)
                || isClassPresent(CLS_TOMCAT_CONNECTOR);
        if (!isTomcat) return null;

        logInfo(log, "[WasDetector] Tomcat 공통 클래스 감지 → 버전 판별 시작");

        // Tomcat 10+ (jakarta.servlet 전환, javax.servlet 없음)
        if (isClassPresent(CLS_JAKARTA_SERVLET) && !isClassPresent("javax.servlet.http.HttpServletRequest")) {
            // jakarta만 있고 javax가 없으면 Tomcat 10+
            logInfo(log, "[WasDetector] Tomcat 10+ 감지 (jakarta.servlet 전용)");
            return WasType.TOMCAT_10_PLUS;
        }

        // Tomcat 10+: HTTP/2 + NIO2 + Jakarta 병행 확인
        if (isClassPresent(CLS_TOMCAT10_HTTP2) && isClassPresent(CLS_JAKARTA_SERVLET)) {
            logInfo(log, "[WasDetector] Tomcat 10+ 감지 (HTTP/2 + jakarta.servlet)");
            return WasType.TOMCAT_10_PLUS;
        }

        // Tomcat 9: Servlet 4.0 HttpServletMapping (Servlet 4.0 고유 API)
        if (isClassPresent(CLS_TOMCAT9_SERVLET40)) {
            logInfo(log, "[WasDetector] Tomcat 9 감지 (javax.servlet.http.HttpServletMapping - Servlet 4.0)");
            return WasType.TOMCAT_9;
        }

        // Tomcat 8.5: UpgradeToken (ALPN/HTTP2 핸들러, 8.0에는 없음)
        if (isClassPresent(CLS_TOMCAT85_UPGRADETOKEN)) {
            logInfo(log, "[WasDetector] Tomcat 8.5 감지 (UpgradeToken - ALPN 지원)");
            return WasType.TOMCAT_8;
        }

        // Tomcat 8: NIO2Channel (Tomcat 8에서 NIO2 기본 커넥터 도입)
        if (isClassPresent(CLS_TOMCAT8_NIO2HANDLER)) {
            logInfo(log, "[WasDetector] Tomcat 8 감지 (Nio2Channel - NIO2 기본)");
            return WasType.TOMCAT_8;
        }

        // Tomcat 7: AsyncContextImpl (Servlet 3.0 Async 지원)
        if (isClassPresent(CLS_TOMCAT7_ASYNC)) {
            logInfo(log, "[WasDetector] Tomcat 7 감지 (AsyncContextImpl - Servlet 3.0)");
            return WasType.TOMCAT_7;
        }

        // Tomcat 6: RequestUtil (7+에서 제거된 레거시 유틸)
        if (isClassPresent(CLS_TOMCAT6_LEGACY)) {
            logInfo(log, "[WasDetector] Tomcat 5.x/6.x (Legacy) 감지 (RequestUtil)");
            return WasType.TOMCAT_LEGACY;
        }

        // Tomcat 공통 클래스만 감지 → JDK 버전으로 추정
        int jdkMajor = getRuntimeJdkMajor();
        logInfo(log, "[WasDetector] Tomcat 버전 특화 클래스 없음 — JDK " + jdkMajor + " 기반 추정");
        return estimateTomcatVersionByJdk(jdkMajor, log);
    }

    private static WasType estimateTomcatVersionByJdk(int jdkMajor, PrintStream log) {
        if (jdkMajor >= 11) {
            // JDK 11+: Tomcat 10+ (jakarta) 또는 Tomcat 9 (javax)
            // jakarta 클래스 존재 여부로 최종 분기
            if (isClassPresent(CLS_JAKARTA_SERVLET)) {
                logInfo(log, "[WasDetector] JDK 11+ + jakarta.servlet → Tomcat 10+ 추정");
                return WasType.TOMCAT_10_PLUS;
            }
            logInfo(log, "[WasDetector] JDK 11+ (javax.servlet) → Tomcat 9 추정");
            return WasType.TOMCAT_9;
        }
        if (jdkMajor >= 8) {
            logInfo(log, "[WasDetector] JDK 8 → Tomcat 9 추정");
            return WasType.TOMCAT_9;
        }
        if (jdkMajor == 7) {
            logInfo(log, "[WasDetector] JDK 7 → Tomcat 7 추정");
            return WasType.TOMCAT_7;
        }
        logInfo(log, "[WasDetector] JDK " + jdkMajor + " → Tomcat Legacy 추정");
        return WasType.TOMCAT_LEGACY;
    }

    private static WasType detectTomcatVersionByProperties(PrintStream log) {
        String catalinaHome = System.getProperty(PROP_CATALINA_HOME);
        if (catalinaHome != null) {
            return detectTomcatVersionFromPath(catalinaHome, log);
        }
        return estimateTomcatVersionByJdk(getRuntimeJdkMajor(), log);
    }

    /** CATALINA_HOME 경로 문자열로 버전 추정 */
    private static WasType detectTomcatVersionFromPath(String path, PrintStream log) {
        if (path == null) return WasType.TOMCAT;
        String p = path.toLowerCase();
        if (p.contains("tomcat-11") || p.contains("tomcat11")) return pathHint(log, path, WasType.TOMCAT_10_PLUS);
        if (p.contains("tomcat-10") || p.contains("tomcat10")) return pathHint(log, path, WasType.TOMCAT_10_PLUS);
        if (p.contains("tomcat-9") || p.contains("tomcat9"))   return pathHint(log, path, WasType.TOMCAT_9);
        if (p.contains("tomcat-8") || p.contains("tomcat8"))   return pathHint(log, path, WasType.TOMCAT_8);
        if (p.contains("tomcat-7") || p.contains("tomcat7"))   return pathHint(log, path, WasType.TOMCAT_7);
        if (p.contains("tomcat-6") || p.contains("tomcat6"))   return pathHint(log, path, WasType.TOMCAT_LEGACY);
        // 경로에서 버전 추정 불가 → JDK 버전 기반 추정
        return estimateTomcatVersionByJdk(getRuntimeJdkMajor(), log);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // WildFly 감지 (JBoss보다 먼저)
    // ─────────────────────────────────────────────────────────────────────────────

    private static WasType detectWildFlyByClasspath(PrintStream log) {
        if (isClassPresent(CLS_WILDFLY_BOOT) || isClassPresent(CLS_WILDFLY_UNDERTOW)) {
            logInfo(log, "[WasDetector] WildFly 27+ 감지 (Jakarta EE 계열)");
            return WasType.WILDFLY;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // JBoss 버전별 감지
    // ─────────────────────────────────────────────────────────────────────────────

    private static WasType detectJBossByClasspath(PrintStream log) {
        // JBoss EAP 7+ (Undertow 기반)
        if (isClassPresent(CLS_JBOSS_EAP7_BOOT) || isClassPresent(CLS_JBOSS_EAP7_UNDERTOW)) {
            logInfo(log, "[WasDetector] JBoss EAP 7+ 감지");
            return WasType.JBOSS;
        }
        // JBoss AS 5/6 (레거시, Microcontainer 기반)
        if (isClassPresent(CLS_JBOSS_LEGACY_MICRO) || isClassPresent(CLS_JBOSS_LEGACY_DEPLOY)) {
            logInfo(log, "[WasDetector] JBoss AS 5/6 (Legacy) 감지");
            return WasType.JBOSS_LEGACY;
        }
        return null;
    }

    private static WasType detectJBossVersionByProperties(PrintStream log) {
        String jbossHome = System.getProperty(PROP_JBOSS_HOME);
        if (jbossHome == null) jbossHome = System.getProperty(PROP_WILDFLY_HOME);
        return detectJBossVersionFromPath(jbossHome, log);
    }

    private static WasType detectJBossVersionFromPath(String path, PrintStream log) {
        if (path == null) return WasType.JBOSS;
        String p = path.toLowerCase();
        if (p.contains("wildfly"))     return pathHint(log, path, WasType.WILDFLY);
        if (p.contains("jboss-eap-7") || p.contains("eap7")) return pathHint(log, path, WasType.JBOSS);
        if (p.contains("jboss-eap-6") || p.contains("eap6")) return pathHint(log, path, WasType.JBOSS);
        if (p.contains("jboss-5") || p.contains("jboss-6"))  return pathHint(log, path, WasType.JBOSS_LEGACY);
        return WasType.JBOSS;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // WebLogic 버전별 감지
    // ─────────────────────────────────────────────────────────────────────────────

    private static WasType detectWebLogicByClasspath(PrintStream log) {
        // WebLogic 14c (최신): ServerLifecycleListener 존재
        if (isClassPresent(CLS_WEBLOGIC14_STARTUP)) {
            int jdkMajor = getRuntimeJdkMajor();
            if (jdkMajor >= 8) {
                logInfo(log, "[WasDetector] WebLogic 12c(후기)/14c 감지");
                return WasType.WEBLOGIC;
            }
        }
        // WebLogic 공통 (T3Srvr)
        if (isClassPresent(CLS_WEBLOGIC_T3SVR) || isClassPresent(CLS_WEBLOGIC_SERVER)) {
            int jdkMajor = getRuntimeJdkMajor();
            if (jdkMajor >= 8) {
                logInfo(log, "[WasDetector] WebLogic 12c+ 감지 (T3Srvr + JDK 8+)");
                return WasType.WEBLOGIC;
            } else {
                logInfo(log, "[WasDetector] WebLogic 10.x/11g/12c(초기) 감지 (T3Srvr + JDK 6~7)");
                return WasType.WEBLOGIC_LEGACY;
            }
        }
        // WebLogic FilterChainImpl (내부 서블릿 필터 체인)
        if (isClassPresent(CLS_WEBLOGIC_FILTERCHAIN)) {
            logInfo(log, "[WasDetector] WebLogic 감지 (FilterChainImpl)");
            return WasType.WEBLOGIC;
        }
        return null;
    }

    private static WasType detectWebLogicVersionByProperties(PrintStream log) {
        String wlHome = System.getProperty(PROP_WEBLOGIC_HOME);
        return detectWebLogicVersionFromPath(wlHome, log);
    }

    private static WasType detectWebLogicVersionFromPath(String path, PrintStream log) {
        if (path == null) return WasType.WEBLOGIC;
        String p = path.toLowerCase();
        if (p.contains("wls14") || p.contains("weblogic14")) return pathHint(log, path, WasType.WEBLOGIC);
        if (p.contains("wls12") || p.contains("weblogic12")) {
            int jdkMajor = getRuntimeJdkMajor();
            return jdkMajor >= 8 ? pathHint(log, path, WasType.WEBLOGIC) : pathHint(log, path, WasType.WEBLOGIC_LEGACY);
        }
        if (p.contains("wls10") || p.contains("wls11") || p.contains("weblogic10") || p.contains("weblogic11")) {
            return pathHint(log, path, WasType.WEBLOGIC_LEGACY);
        }
        return WasType.WEBLOGIC;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // WebSphere 버전별 감지
    // ─────────────────────────────────────────────────────────────────────────────

    private static WasType detectWebSphereByClasspath(PrintStream log) {
        // WebSphere Liberty / Open Liberty (경량 프로파일)
        if (isClassPresent(CLS_WAS_LIBERTY)) {
            logInfo(log, "[WasDetector] WebSphere Liberty / Open Liberty 감지");
            return WasType.WEBSPHERE;
        }
        // WebSphere Traditional (전통적 WAS)
        if (isClassPresent(CLS_WAS_WEBCONTAINER) || isClassPresent(CLS_WAS_FILTER_MGR)) {
            int jdkMajor = getRuntimeJdkMajor();
            if (jdkMajor >= 8) {
                logInfo(log, "[WasDetector] WebSphere (Liberty 계열) 감지");
                return WasType.WEBSPHERE;
            } else {
                logInfo(log, "[WasDetector] WebSphere 7/8 (Legacy) 감지");
                return WasType.WEBSPHERE_LEGACY;
            }
        }
        if (isClassPresent(CLS_WAS_LEGACY_SERVER)) {
            logInfo(log, "[WasDetector] IBM WebSphere (Legacy AdminService) 감지");
            return WasType.WEBSPHERE_LEGACY;
        }
        return null;
    }

    private static WasType detectWebSphereVersionByProperties(PrintStream log) {
        String wasInstall = System.getProperty(PROP_WAS_INSTALL);
        return detectWebSphereVersionFromPath(wasInstall, log);
    }

    private static WasType detectWebSphereVersionFromPath(String path, PrintStream log) {
        if (path == null) return WasType.WEBSPHERE;
        String p = path.toLowerCase();
        if (p.contains("liberty") || p.contains("open_liberty")) return pathHint(log, path, WasType.WEBSPHERE);
        if (p.contains("websphere") || p.contains("appserver")) {
            int jdkMajor = getRuntimeJdkMajor();
            return jdkMajor >= 8 ? pathHint(log, path, WasType.WEBSPHERE) : pathHint(log, path, WasType.WEBSPHERE_LEGACY);
        }
        return WasType.WEBSPHERE;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // GlassFish / Payara 감지
    // ─────────────────────────────────────────────────────────────────────────────

    private static WasType detectGlassFishByClasspath(PrintStream log) {
        // Payara Micro
        if (isClassPresent(CLS_PAYARA_EXECUTOR)) {
            logInfo(log, "[WasDetector] Payara Micro 감지");
            return WasType.GLASSFISH;
        }
        // GlassFish 7+ (Jakarta EE) - GlassFish 특화 로그 핸들러
        if (isClassPresent(CLS_GF_JAKARTA_LOG)) {
            logInfo(log, "[WasDetector] GlassFish 6+/7+ (Jakarta EE) 감지");
            return WasType.GLASSFISH_JAKARTA;
        }
        // GlassFish 공통 (WebContainer, Grizzly)
        if (isClassPresent(CLS_GF_WEBCONTAINER) || isClassPresent(CLS_GF_GRIZZLY)) {
            int jdkMajor = getRuntimeJdkMajor();
            if (jdkMajor >= 11 && isClassPresent(CLS_JAKARTA_SERVLET)) {
                logInfo(log, "[WasDetector] GlassFish 6+ (Jakarta EE) 감지");
                return WasType.GLASSFISH_JAKARTA;
            }
            logInfo(log, "[WasDetector] GlassFish 3/4 / Payara 5 감지");
            return WasType.GLASSFISH;
        }
        return null;
    }

    private static WasType detectGlassFishVersionByProperties(PrintStream log) {
        int jdkMajor = getRuntimeJdkMajor();
        if (jdkMajor >= 11 && isClassPresent(CLS_JAKARTA_SERVLET)) {
            return WasType.GLASSFISH_JAKARTA;
        }
        return WasType.GLASSFISH;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Jetty 버전별 감지
    // ─────────────────────────────────────────────────────────────────────────────

    private static WasType detectJettyByClasspath(PrintStream log) {
        // Jetty 12 (Jakarta EE 10, ee10 패키지)
        if (isClassPresent(CLS_JETTY12_EE10)) {
            logInfo(log, "[WasDetector] Jetty 12+ (Jakarta EE 10) 감지");
            return WasType.JETTY_JAKARTA;
        }
        // Jetty 11 (ee9 패키지)
        if (isClassPresent(CLS_JETTY11_EE9)) {
            logInfo(log, "[WasDetector] Jetty 11 (Jakarta EE 9) 감지");
            return WasType.JETTY;
        }
        // Jetty 9~10 (eclipse.jetty 패키지, Servlet 3.1~4.0)
        if (isClassPresent(CLS_JETTY_SERVER) || isClassPresent(CLS_JETTY_HANDLER)) {
            logInfo(log, "[WasDetector] Jetty 9~10 감지");
            return WasType.JETTY;
        }
        // Jetty 구형 (mortbay 패키지)
        if (isClassPresent(CLS_JETTY_LEGACY)) {
            logInfo(log, "[WasDetector] Jetty 7/8 (Legacy, org.mortbay) 감지");
            return WasType.JETTY_LEGACY;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // JVM 인수 / 클래스패스 문자열 스캔 (5단계)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * sun.java.command, java.class.path 시스템 프로퍼티를 분석하여 WAS를 추론한다.
     *
     * <p>일부 WAS는 시작 스크립트에서 WAS 홈 경로를 클래스패스에 포함시키므로,
     * 경로 문자열에서 WAS 이름과 버전 힌트를 탐색한다.
     */
    private static WasType detectByJvmArgs(PrintStream log) {
        String[] props = {PROP_JAVA_COMMAND, "java.class.path", "java.library.path"};
        for (String prop : props) {
            String value = System.getProperty(prop);
            if (value == null) continue;

            String v = value.toLowerCase();

            // JEUS 추가 힌트
            if (v.contains("jeus")) {
                logInfo(log, "[WasDetector] JVM 인수에서 JEUS 힌트 발견: " + prop);
                return resolveJeusVersionFromProperty(log);
            }
            // Tomcat 힌트
            if (v.contains("catalina") || v.contains("tomcat")) {
                logInfo(log, "[WasDetector] JVM 인수에서 Tomcat 힌트 발견: " + prop);
                return estimateTomcatVersionByJdk(getRuntimeJdkMajor(), log);
            }
            // JBoss/WildFly 힌트
            if (v.contains("jboss") || v.contains("wildfly")) {
                logInfo(log, "[WasDetector] JVM 인수에서 JBoss/WildFly 힌트 발견: " + prop);
                return detectJBossVersionFromPath(value, log);
            }
            // WebLogic 힌트
            if (v.contains("weblogic") || v.contains("wlserver")) {
                logInfo(log, "[WasDetector] JVM 인수에서 WebLogic 힌트 발견: " + prop);
                return WasType.WEBLOGIC;
            }
            // WebSphere 힌트
            if (v.contains("websphere") || v.contains("liberty") || v.contains("ibm/java")) {
                logInfo(log, "[WasDetector] JVM 인수에서 WebSphere 힌트 발견: " + prop);
                return WasType.WEBSPHERE;
            }
            // GlassFish 힌트
            if (v.contains("glassfish") || v.contains("payara") || v.contains("com.sun.enterprise")) {
                logInfo(log, "[WasDetector] JVM 인수에서 GlassFish/Payara 힌트 발견: " + prop);
                return WasType.GLASSFISH;
            }
            // Resin 힌트
            if (v.contains("resin") || v.contains("caucho")) {
                logInfo(log, "[WasDetector] JVM 인수에서 Resin 힌트 발견: " + prop);
                return WasType.RESIN;
            }
            // Jetty 힌트
            if (v.contains("jetty") || v.contains("mortbay")) {
                logInfo(log, "[WasDetector] JVM 인수에서 Jetty 힌트 발견: " + prop);
                return detectJettyByClasspath(log) != null ? detectJettyByClasspath(log) : WasType.JETTY;
            }
        }
        return null;
    }

    /**
     * 파일시스템 힌트로 WAS를 탐지한다.
     *
     * <p>WAS 설치 디렉토리 내 고유 파일(descriptor, 시작 스크립트 등)의 존재로
     * WAS 유형을 추론한다. 클래스패스나 시스템 프로퍼티로 감지 못한 WAS가 대상.
     *
     * <p>탐색 대상 디렉토리: /opt, /usr/local, /home, C:\, D:\, E:\
     */
    private static WasType detectByFilesystem(PrintStream log) {
        // 한국 공공기관 일반적인 설치 경로 패턴
        String[] searchRoots = {"/opt", "/usr/local", "/home", "/jeus", "C:\\", "D:\\"};

        for (String root : searchRoots) {
            File rootDir = new File(root);
            if (!rootDir.exists() || !rootDir.isDirectory()) continue;

            String[] children;
            try {
                children = rootDir.list();
            } catch (SecurityException e) {
                continue;
            }
            if (children == null) continue;

            for (String child : children) {
                String lower = child.toLowerCase();

                // JEUS 설치 경로
                if (lower.startsWith("jeus")) {
                    File jeusBin = new File(rootDir, child + "/bin/jeusadmin");
                    if (jeusBin.exists()) {
                        logInfo(log, "[WasDetector] 파일시스템 JEUS 감지: " + jeusBin.getAbsolutePath());
                        return parseJeusVersionFromPath(root + "/" + child, log);
                    }
                }
                // Tomcat 설치 경로
                if (lower.startsWith("tomcat") || lower.startsWith("apache-tomcat")) {
                    File catalinaShell = new File(rootDir, child + "/bin/catalina.sh");
                    File catalinaBat = new File(rootDir, child + "/bin/catalina.bat");
                    if (catalinaShell.exists() || catalinaBat.exists()) {
                        logInfo(log, "[WasDetector] 파일시스템 Tomcat 감지: " + new File(rootDir, child));
                        return detectTomcatVersionFromPath(new File(rootDir, child).getAbsolutePath(), log);
                    }
                }
                // JBoss / WildFly
                if (lower.startsWith("jboss") || lower.startsWith("wildfly")) {
                    File jbossBin = new File(rootDir, child + "/bin/standalone.sh");
                    if (jbossBin.exists()) {
                        logInfo(log, "[WasDetector] 파일시스템 JBoss/WildFly 감지");
                        return lower.startsWith("wildfly") ? WasType.WILDFLY : WasType.JBOSS;
                    }
                }
                // WebLogic
                if (lower.contains("weblogic") || lower.contains("wlserver")) {
                    logInfo(log, "[WasDetector] 파일시스템 WebLogic 감지: " + child);
                    return WasType.WEBLOGIC;
                }
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // JEUS 전용 헬퍼 (기존 유지)
    // ─────────────────────────────────────────────────────────────────────────────

    private static WasType detectJeusBySystemProperty(PrintStream log) {
        String jeusHome   = System.getProperty(PROP_JEUS_HOME);
        String jeusServer = System.getProperty(PROP_JEUS_SERVER_NAME);
        String jeusEngine = System.getProperty(PROP_JEUS_ENGINE_NAME);
        String jeusVer    = System.getProperty(PROP_JEUS_VERSION);

        if (jeusHome == null && jeusServer == null && jeusEngine == null) return null;

        logInfo(log, "[WasDetector] JEUS 시스템 프로퍼티 감지: "
                + "jeus.home=" + jeusHome + ", jeus.version=" + jeusVer);

        if (jeusVer != null && !jeusVer.isEmpty()) {
            WasType fromVer = parseJeusVersionString(jeusVer, log);
            if (fromVer != null) return fromVer;
        }
        if (jeusHome != null) {
            WasType fromPath = parseJeusVersionFromPath(jeusHome, log);
            if (fromPath != null) return fromPath;
        }
        return resolveJeusVersionFromProperty(log);
    }

    private static WasType detectJeusByEnvironment(PrintStream log) {
        String jeusHome = getEnv(ENV_JEUS_HOME);
        if (jeusHome == null) return null;
        logInfo(log, "[WasDetector] 환경변수 JEUS_HOME=" + jeusHome);
        WasType fromPath = parseJeusVersionFromPath(jeusHome, log);
        if (fromPath != null) return fromPath;
        logWarn(log, "[WasDetector] JEUS_HOME 경로 버전 추정 불가 → JEUS_7(기본값)");
        return WasType.JEUS_7;
    }

    public static WasType parseJeusVersionString(String version, PrintStream log) {
        if (version == null || version.isEmpty()) return null;
        String v = version.trim().toLowerCase();
        if (v.startsWith("21") || v.contains("jeus 21") || v.contains("jeus21")) return WasType.JEUS_9_PLUS;
        if (v.startsWith("9")  || v.contains("jeus 9")  || v.contains("jeus9"))  return WasType.JEUS_9_PLUS;
        if (v.startsWith("8.5") || v.contains("jeus 8.5") || v.contains("jeus8.5")) return WasType.JEUS_8_5;
        if (v.startsWith("8")  || v.contains("jeus 8")  || v.contains("jeus8"))  return WasType.JEUS_8;
        if (v.startsWith("7")  || v.contains("jeus 7")  || v.contains("jeus7"))  return WasType.JEUS_7;
        if (v.startsWith("6")  || v.contains("jeus 6")  || v.contains("jeus6"))  return WasType.JEUS_6;
        if (v.startsWith("5")  || v.contains("jeus 5")  || v.contains("jeus5"))  return WasType.JEUS_LEGACY;
        if (v.startsWith("4")  || v.contains("jeus 4")  || v.contains("jeus4"))  return WasType.JEUS_LEGACY;
        logWarn(log, "[WasDetector] jeus.version 파싱 실패: " + version);
        return null;
    }

    public static WasType parseJeusVersionFromPath(String path, PrintStream log) {
        if (path == null) return null;
        String p = path.toLowerCase();
        if (p.contains("jeus21"))  return pathHint(log, path, WasType.JEUS_9_PLUS);
        if (p.contains("jeus9"))   return pathHint(log, path, WasType.JEUS_9_PLUS);
        if (p.contains("jeus8.5")) return pathHint(log, path, WasType.JEUS_8_5);
        if (p.contains("jeus8"))   return pathHint(log, path, WasType.JEUS_8);
        if (p.contains("jeus7"))   return pathHint(log, path, WasType.JEUS_7);
        if (p.contains("jeus6"))   return pathHint(log, path, WasType.JEUS_6);
        if (p.contains("jeus5"))   return pathHint(log, path, WasType.JEUS_LEGACY);
        if (p.contains("jeus4"))   return pathHint(log, path, WasType.JEUS_LEGACY);
        return null;
    }

    private static WasType resolveJeusVersionFromProperty(PrintStream log) {
        int jdkMajor = getRuntimeJdkMajor();
        logInfo(log, "[WasDetector] JEUS 버전 추정 — 런타임 JDK major=" + jdkMajor);
        if (jdkMajor >= 11) {
            if (isClassPresent(CLS_JAKARTA_SERVLET)) return WasType.JEUS_9_PLUS;
            return WasType.JEUS_8_5;
        }
        if (jdkMajor == 8) return WasType.JEUS_8;
        if (jdkMajor == 7) return WasType.JEUS_7;
        if (jdkMajor == 6) return WasType.JEUS_6;
        return WasType.JEUS_LEGACY;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 공용 내부 헬퍼
    // ─────────────────────────────────────────────────────────────────────────────

    private static WasType pathHint(PrintStream log, String path, WasType type) {
        logInfo(log, "[WasDetector] 경로 버전 힌트: " + path + " → " + type);
        return type;
    }

    private static boolean isClassPresent(String className) {
        try {
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

    private static WasType parseOverride(String value) {
        try {
            return WasType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String getEnv(String name) {
        try {
            return System.getenv(name);
        } catch (SecurityException ignored) {
            return null;
        }
    }

    public static int getRuntimeJdkMajor() {
        String version = System.getProperty("java.version", "1.8");
        try {
            if (version.startsWith("1.")) {
                String[] parts = version.split("\\.");
                if (parts.length >= 2) return Integer.parseInt(parts[1]);
            } else {
                int dot = version.indexOf('.');
                String majorStr = (dot < 0) ? version : version.substring(0, dot);
                return Integer.parseInt(majorStr);
            }
        } catch (NumberFormatException ignored) {}
        return 8;
    }

    private static void logInfo(PrintStream log, String message) {
        if (log != null) log.println(message);
    }

    private static void logWarn(PrintStream log, String message) {
        if (log != null) log.println("[WARN] " + message);
    }
}

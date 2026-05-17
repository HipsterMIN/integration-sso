package kr.go.smes.agent.was;

import java.io.PrintStream;

/**
 * WAS 런타임 유형 자동 감지기.
 *
 * <h2>감지 전략 (우선순위 순)</h2>
 * <ol>
 *   <li><b>시스템 프로퍼티 오버라이드</b>: {@code onepass.was.type} 명시적 지정</li>
 *   <li><b>클래스패스 탐색</b>: WAS 고유 클래스의 {@link Class#forName(String)} 성공 여부
 *       — JEUS의 경우 버전별 고유 클래스를 역순(신→구)으로 탐색</li>
 *   <li><b>시스템 프로퍼티 패턴</b>: {@code jeus.home}, {@code jeus.server.name},
 *       {@code weblogic.Name}, {@code jboss.home.dir} 등</li>
 *   <li><b>환경 변수</b>: {@code JEUS_HOME}, {@code CATALINA_HOME}, {@code JBOSS_HOME} 등</li>
 *   <li><b>Fallback</b>: 위 모든 수단 실패 시 {@link WasType#UNKNOWN}</li>
 * </ol>
 *
 * <h2>JEUS 버전 판별 상세</h2>
 * <p>JEUS는 버전에 따라 JDK 요구사항과 위빙 전략이 크게 달라지므로 정밀 버전 판별이 필요하다.
 *
 * <pre>
 *  판별 단서                         JEUS 버전  이유
 *  ─────────────────────────────────────────────────────────────────────────────
 *  com.tmaxsoft.jeus.web.servlet.Jeus9Servlet   JEUS 9+    신규 클래스 (JEUS 9 도입)
 *  com.tmaxsoft.jeus.web.servlet.JeusServlet8_5 JEUS 8.5   JEUS 8.5 도입 클래스
 *  com.tmaxsoft.jeus.web.servlet.JeusFilter8    JEUS 8     JEUS 8 고유
 *  com.tmaxsoft.jeus.web.servlet.JeusFilter7    JEUS 7     JEUS 7 고유
 *  com.tmaxsoft.jeus.web.servlet.JeusFilter     JEUS 6     com.tmaxsoft 최초 도입
 *  com.tmaxsoft.jeus.web.JeusWebContainer       JEUS 6+    신 패키지 식별자
 *  jeus.servlet.JeusServletEngine               JEUS 4~6   공통 내부 클래스
 *  com.tmax.jeus.web.servlet.HttpServletWrapper JEUS 4/5   구 패키지 (com.tmax.jeus)
 *  jeus.home 시스템 프로퍼티                    JEUS 전 버전
 *  jeus.server.name 시스템 프로퍼티             JEUS 전 버전
 *  JEUS_HOME 환경변수                           JEUS 전 버전 (설치 경로)
 * </pre>
 *
 * <p><b>중요</b>: 실제 운영 환경에서 JEUS 고유 클래스는 WAS 클래스로더에 의해 로드된다.
 * Agent의 premain 단계에서는 WAS가 아직 완전히 초기화되지 않았을 수 있으므로,
 * 시스템 프로퍼티/환경변수 방식이 더 신뢰성 높은 경우가 많다.
 * 두 단계를 모두 수행하고 최고 신뢰도 결과를 선택한다.
 *
 * <h2>JDK 버전 기반 위빙 엔진 분기</h2>
 * <p>JEUS 버전이 결정된 후, 실제 위빙 엔진(byte-buddy vs Javassist)은
 * 런타임 JDK 버전으로 최종 결정한다. WasType은 WAS 계열을 나타내며,
 * {@link kr.go.smes.agent.weaving.jeus.JeusWeavingEngineSelector}가
 * 런타임 JDK 버전을 합산하여 최적 엔진을 선택한다.
 *
 * <h2>스레드 안전성</h2>
 * 멱등(idempotent) 조회이며 결과를 캐싱하지 않는다.
 * premain 단계에서 단 1회 호출되므로 성능 영향 없음.
 */
public final class WasDetector {

    // ── 시스템 프로퍼티 오버라이드 키 ───────────────────────────────────────────
    private static final String OVERRIDE_PROP = "onepass.was.type";

    // ── JEUS 버전 판별 — 클래스패스 탐색 (신→구 순서, 버전 특화 클래스 우선) ──

    // JEUS 9+ (Jakarta EE): jakarta.servlet 패키지 + 새 JEUS 내부 클래스
    // JEUS 9의 고유 내부 서블릿 엔진 클래스 (JEUS 8.5 이하에는 없음)
    private static final String CLS_JEUS9_SERVLET_ENGINE =
            "com.tmaxsoft.jeus.web.servlet.engine.JeusServletEngine9";
    // Jakarta Servlet — JEUS 9+에서 javax.servlet 대체 (Tomcat 10+도 해당이나, JEUS 프로퍼티와 병행 확인)
    private static final String CLS_JAKARTA_SERVLET_REQUEST =
            "jakarta.servlet.ServletRequest";

    // JEUS 8.5: JDK 8/11, Servlet 4.0
    private static final String CLS_JEUS8_5_CONTAINER =
            "com.tmaxsoft.jeus.web.servlet.JeusServlet4Container";
    // JEUS 8.5 도입된 HTTP/2 지원 클래스
    private static final String CLS_JEUS8_5_HTTP2 =
            "com.tmaxsoft.jeus.web.http2.JeusHttp2Handler";

    // JEUS 8: JDK 1.7~1.8, Servlet 3.1
    private static final String CLS_JEUS8_CONNECTOR =
            "com.tmaxsoft.jeus.web.connector.JeusConnector8";
    // JEUS 8 도입된 WebSocket 지원 핸들러
    private static final String CLS_JEUS8_WEBSOCKET =
            "com.tmaxsoft.jeus.web.websocket.JeusWebSocketHandler";

    // JEUS 7: JDK 1.6~1.8, Servlet 3.0
    private static final String CLS_JEUS7_DEPLOYER =
            "com.tmaxsoft.jeus.web.deployer.JeusWebDeployer7";
    // JEUS 7에서 도입된 Servlet 3.0 Async 지원 클래스
    private static final String CLS_JEUS7_ASYNC =
            "com.tmaxsoft.jeus.web.async.JeusAsyncContext";

    // JEUS 6: JDK 1.5~1.7, com.tmaxsoft.jeus.* (신 패키지 최초 도입)
    // 구 패키지(com.tmax)에서 신 패키지(com.tmaxsoft)로 전환된 버전
    private static final String CLS_JEUS6_WEBCONTAINER =
            "com.tmaxsoft.jeus.web.JeusWebContainer";
    private static final String CLS_JEUS6_SERVLET_HANDLER =
            "com.tmaxsoft.jeus.web.servlet.JeusServletHandler";

    // JEUS 4/5 공통: com.tmax.jeus.* (구 패키지)
    // 패키지 루트가 com.tmax.jeus 인 경우 JEUS 4 또는 5
    private static final String CLS_JEUS45_HTTP_WRAPPER =
            "com.tmax.jeus.web.servlet.HttpServletWrapper";
    private static final String CLS_JEUS45_ENGINE =
            "com.tmax.jeus.util.engine.ServiceEngine";
    private static final String CLS_JEUS45_MAIN =
            "com.tmax.jeus.server.JeusMain";

    // JEUS 공통 내부 클래스 (버전 무관 — 존재 시 JEUS 계열 확인용)
    private static final String CLS_JEUS_SERVLET_ENGINE =
            "jeus.servlet.JeusServletEngine";
    // JEUS 6+ 공통 프로퍼티 기반 웹컨테이너 초기화 클래스
    private static final String CLS_JEUS_WEB_UTILS =
            "jeus.util.JeusWebUtils";

    // ── JEUS 시스템 프로퍼티 단서 ───────────────────────────────────────────────
    /** JEUS 설치 홈 디렉토리 — 모든 JEUS 버전에서 설정됨 */
    private static final String PROP_JEUS_HOME        = "jeus.home";
    /** JEUS 서버 이름 — 부팅 시 JVM 인수로 전달 */
    private static final String PROP_JEUS_SERVER_NAME = "jeus.server.name";
    /** JEUS 엔진 이름 (JEUS 6+ 멀티 엔진 아키텍처) */
    private static final String PROP_JEUS_ENGINE_NAME = "jeus.engine.name";
    /** JEUS 버전 정보 프로퍼티 (JEUS 7+ 에서 간혹 설정됨) */
    private static final String PROP_JEUS_VERSION     = "jeus.version";

    // ── 기타 WAS 시스템 프로퍼티 단서 ──────────────────────────────────────────
    private static final String PROP_WEBLOGIC_NAME    = "weblogic.Name";
    private static final String PROP_JBOSS_HOME       = "jboss.home.dir";
    private static final String PROP_WILDFLY_HOME     = "jboss.server.base.dir";
    private static final String PROP_CATALINA_HOME    = "catalina.home";

    // ── WAS 고유 클래스 (클래스패스 탐색용, 기타 WAS) ──────────────────────────
    private static final String CLS_TOMCAT_CATALINA  = "org.apache.catalina.startup.Catalina";
    private static final String CLS_TOMCAT_VALVE     = "org.apache.catalina.Valve";
    private static final String CLS_JBOSS_BOOTSTRAP  = "org.jboss.as.server.Bootstrap";
    private static final String CLS_WILDFLY_UNDERTOW = "org.wildfly.extension.undertow.UndertowService";
    private static final String CLS_WEBLOGIC_SERVER  = "weblogic.t3.srvr.T3Srvr";
    private static final String CLS_WEBLOGIC_MAIN    = "weblogic.Server";
    private static final String CLS_UNDERTOW         = "io.undertow.Undertow";
    private static final String CLS_UNDERTOW_SERVLET = "io.undertow.servlet.api.DeploymentManager";
    private static final String CLS_JETTY_SERVER     = "org.eclipse.jetty.server.Server";
    private static final String CLS_JETTY_LEGACY     = "org.mortbay.jetty.Server";

    // ── 환경 변수 단서 ──────────────────────────────────────────────────────────
    /** JEUS 설치 경로 환경변수 — 관리자 스크립트(jeusadmin, startDomainAdminServer) 등에서 설정 */
    private static final String ENV_JEUS_HOME        = "JEUS_HOME";
    private static final String ENV_CATALINA_HOME    = "CATALINA_HOME";
    private static final String ENV_JBOSS_HOME       = "JBOSS_HOME";
    private static final String ENV_WEBLOGIC_HOME    = "WL_HOME";

    private WasDetector() {
        // 유틸리티 클래스 — 인스턴스화 금지
    }

    /**
     * WAS 런타임 유형을 감지해 반환한다.
     *
     * <p>감지 실패 시 {@link WasType#UNKNOWN}을 반환하며 예외를 던지지 않는다.
     * Agent의 premain 단계에서 JVM이 종료되면 안 되므로 방어적으로 설계됐다.
     *
     * @param log 감지 과정 출력에 사용할 PrintStream (null 허용 — null이면 로깅 생략)
     * @return 감지된 {@link WasType} — 절대 {@code null}이 아님
     */
    public static WasType detect(PrintStream log) {

        // ── 1단계: 명시적 오버라이드 ─────────────────────────────────────────
        String override = System.getProperty(OVERRIDE_PROP);
        if (override != null && !override.isEmpty()) {
            WasType overridden = parseOverride(override);
            if (overridden != null) {
                logInfo(log, "[WasDetector] 오버라이드 적용: -D" + OVERRIDE_PROP
                        + "=" + override + " → " + overridden);
                return overridden;
            }
            logWarn(log, "[WasDetector] 알 수 없는 WAS 유형 오버라이드: " + override
                    + " — 자동 감지로 전환");
        }

        // ── 2단계: 클래스패스 탐색 (신뢰도 최고) ────────────────────────────
        //  NOTE: JEUS를 가장 먼저, 버전 높은 것부터 탐색한다.
        //        한국 공공기관 특화 Agent이므로 JEUS 감지 우선순위를 최상위로.
        WasType jeusType = detectJeusByClasspath(log);
        if (jeusType != null) {
            return jeusType;
        }

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

        if (isClassPresent(CLS_UNDERTOW) || isClassPresent(CLS_UNDERTOW_SERVLET)) {
            logInfo(log, "[WasDetector] Undertow 클래스 감지");
            return WasType.UNDERTOW;
        }

        if (isClassPresent(CLS_JETTY_SERVER) || isClassPresent(CLS_JETTY_LEGACY)) {
            logInfo(log, "[WasDetector] Jetty 클래스 감지");
            return WasType.JETTY;
        }

        // ── 3단계: 시스템 프로퍼티 패턴 ─────────────────────────────────────
        //  NOTE: JEUS 시스템 프로퍼티를 가장 먼저 확인
        WasType jeusByProp = detectJeusBySystemProperty(log);
        if (jeusByProp != null) {
            return jeusByProp;
        }

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
        WasType jeusByEnv = detectJeusByEnvironment(log);
        if (jeusByEnv != null) {
            return jeusByEnv;
        }

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

    // ─────────────────────────────────────────────────────────────────────────────
    // JEUS 전용 감지 메서드
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * JEUS 버전을 클래스패스 탐색으로 판별한다.
     *
     * <p>탐색 순서: JEUS 9+ → JEUS 8.5 → JEUS 8 → JEUS 7 → JEUS 6 → JEUS 4/5
     * 상위 버전 클래스가 없으면 하위 버전으로 폴백한다.
     *
     * <p>JEUS 고유 클래스는 WAS 클래스로더 영역에 있어서 premain 초기 단계에
     * 탐색되지 않을 수 있다. 이 경우 3단계(시스템 프로퍼티)가 보완한다.
     *
     * @return 감지된 JEUS {@link WasType}, 감지 실패 시 null
     */
    private static WasType detectJeusByClasspath(PrintStream log) {

        // JEUS 9+: Jakarta EE → com.tmaxsoft.jeus + jakarta.servlet 공존
        // jakarta.servlet.ServletRequest 단독으로는 Tomcat 10+/WildFly 27+와 구분 안 됨
        // JEUS 9 고유 내부 클래스와 jakarta.servlet 병행 확인
        if (isClassPresent(CLS_JEUS9_SERVLET_ENGINE)) {
            logInfo(log, "[WasDetector] JEUS 9+ 클래스 감지: " + CLS_JEUS9_SERVLET_ENGINE);
            return WasType.JEUS_9_PLUS;
        }
        // 대체 판별: jakarta.servlet + JEUS 공통 클래스 동시 존재
        if (isClassPresent(CLS_JAKARTA_SERVLET_REQUEST)
                && (isClassPresent(CLS_JEUS_SERVLET_ENGINE) || isClassPresent(CLS_JEUS_WEB_UTILS))) {
            logInfo(log, "[WasDetector] JEUS 9+ 감지 (jakarta.servlet + JEUS 공통 클래스 병행)");
            return WasType.JEUS_9_PLUS;
        }

        // JEUS 8.5: Servlet 4.0, JDK 8/11, HTTP/2 지원
        if (isClassPresent(CLS_JEUS8_5_CONTAINER) || isClassPresent(CLS_JEUS8_5_HTTP2)) {
            logInfo(log, "[WasDetector] JEUS 8.5 클래스 감지");
            return WasType.JEUS_8_5;
        }

        // JEUS 8: Servlet 3.1, JDK 1.7~1.8, WebSocket 지원
        if (isClassPresent(CLS_JEUS8_CONNECTOR) || isClassPresent(CLS_JEUS8_WEBSOCKET)) {
            logInfo(log, "[WasDetector] JEUS 8 클래스 감지");
            return WasType.JEUS_8;
        }

        // JEUS 7: Servlet 3.0, JDK 1.6~1.8, Async 지원
        if (isClassPresent(CLS_JEUS7_DEPLOYER) || isClassPresent(CLS_JEUS7_ASYNC)) {
            logInfo(log, "[WasDetector] JEUS 7 클래스 감지");
            return WasType.JEUS_7;
        }

        // JEUS 6: com.tmaxsoft.jeus.* 최초 패키지, Servlet 2.5
        if (isClassPresent(CLS_JEUS6_WEBCONTAINER) || isClassPresent(CLS_JEUS6_SERVLET_HANDLER)) {
            logInfo(log, "[WasDetector] JEUS 6 클래스 감지: " + CLS_JEUS6_WEBCONTAINER);
            return WasType.JEUS_6;
        }

        // JEUS 4/5: com.tmax.jeus.* 구 패키지
        if (isClassPresent(CLS_JEUS45_HTTP_WRAPPER)
                || isClassPresent(CLS_JEUS45_ENGINE)
                || isClassPresent(CLS_JEUS45_MAIN)) {
            logInfo(log, "[WasDetector] JEUS 4/5 (Legacy) 클래스 감지: com.tmax.jeus.*");
            return WasType.JEUS_LEGACY;
        }

        // JEUS 공통 클래스만 존재 (버전 특화 클래스 없음) → jeus.version 프로퍼티로 분기
        if (isClassPresent(CLS_JEUS_SERVLET_ENGINE) || isClassPresent(CLS_JEUS_WEB_UTILS)) {
            logInfo(log, "[WasDetector] JEUS 공통 클래스 감지 (버전 특화 클래스 없음) — 버전 프로퍼티로 분기");
            return resolveJeusVersionFromProperty(log);
        }

        return null;
    }

    /**
     * JEUS 버전을 시스템 프로퍼티로 판별한다.
     *
     * <p>JEUS가 설치된 환경에서는 JVM 기동 스크립트에 의해 다음 프로퍼티가 설정된다:
     * <ul>
     *   <li>{@code jeus.home}: JEUS 설치 경로 (예: /jeus8)</li>
     *   <li>{@code jeus.server.name}: JEUS 서버 이름 (예: MyServer)</li>
     *   <li>{@code jeus.engine.name}: JEUS 6+ 엔진 이름</li>
     *   <li>{@code jeus.version}: JEUS 버전 문자열 (항상 존재하지 않음)</li>
     * </ul>
     *
     * <p>프로퍼티로 JEUS임을 확인한 후, {@code jeus.version} 값이 있으면 파싱하고
     * 없으면 {@link #resolveJeusVersionFromProperty(PrintStream)}로 추가 판별한다.
     *
     * @return 감지된 JEUS {@link WasType}, JEUS가 아니면 null
     */
    private static WasType detectJeusBySystemProperty(PrintStream log) {
        String jeusHome   = System.getProperty(PROP_JEUS_HOME);
        String jeusServer = System.getProperty(PROP_JEUS_SERVER_NAME);
        String jeusEngine = System.getProperty(PROP_JEUS_ENGINE_NAME);
        String jeusVer    = System.getProperty(PROP_JEUS_VERSION);

        if (jeusHome == null && jeusServer == null && jeusEngine == null) {
            return null; // JEUS 프로퍼티 없음
        }

        logInfo(log, "[WasDetector] JEUS 시스템 프로퍼티 감지: "
                + "jeus.home=" + jeusHome
                + ", jeus.server.name=" + jeusServer
                + ", jeus.version=" + jeusVer);

        // jeus.version 프로퍼티가 명시적으로 있으면 우선 파싱
        if (jeusVer != null && !jeusVer.isEmpty()) {
            WasType fromVer = parseJeusVersionString(jeusVer, log);
            if (fromVer != null) return fromVer;
        }

        // jeus.home 경로에 버전 힌트 포함 여부 탐색 (예: /jeus8, C:\jeus7)
        if (jeusHome != null) {
            WasType fromPath = parseJeusVersionFromPath(jeusHome, log);
            if (fromPath != null) return fromPath;
        }

        // 버전 판별 불가 → 추가 단서 활용 후 안전 기본값 반환
        return resolveJeusVersionFromProperty(log);
    }

    /**
     * JEUS 버전을 환경 변수로 판별한다.
     *
     * <p>{@code JEUS_HOME} 환경변수는 관리자가 직접 설정하는 경우가 많으며
     * 경로에 버전 힌트를 포함하는 경우가 흔하다 (예: {@code /opt/jeus8.5}).
     * 경로 파싱으로 버전을 추정하고, 불가 시 안전 기본값을 반환한다.
     *
     * @return 감지된 JEUS {@link WasType}, JEUS_HOME 없으면 null
     */
    private static WasType detectJeusByEnvironment(PrintStream log) {
        String jeusHome = getEnv(ENV_JEUS_HOME);
        if (jeusHome == null) return null;

        logInfo(log, "[WasDetector] 환경변수 JEUS_HOME=" + jeusHome);

        WasType fromPath = parseJeusVersionFromPath(jeusHome, log);
        if (fromPath != null) return fromPath;

        // 경로에 버전 힌트 없음 → 안전 기본값 (JEUS 7로 간주 — 가장 많이 사용)
        logWarn(log, "[WasDetector] JEUS_HOME 경로에서 버전 추정 불가 → JEUS_7(기본값) 적용"
                + " (정확한 버전 지정: -D" + OVERRIDE_PROP + "=JEUS_7 등)");
        return WasType.JEUS_7;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // JEUS 버전 파싱 헬퍼
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * {@code jeus.version} 문자열을 파싱하여 WasType을 결정한다.
     *
     * <p>예상 형식: "JEUS 8.5 Fix1", "8.5.0.1", "9.0", "21.0.0" 등.
     * 파싱에 실패하면 null을 반환하고 상위 로직이 대안 경로를 사용한다.
     *
     * @param version jeus.version 프로퍼티 값
     * @param log     로그 스트림
     * @return 매핑된 {@link WasType} 또는 null
     */
    public static WasType parseJeusVersionString(String version, PrintStream log) {
        if (version == null || version.isEmpty()) return null;

        String v = version.trim().toLowerCase();

        // "21" 또는 "21.x" — JEUS 21
        if (v.startsWith("21") || v.contains("jeus 21") || v.contains("jeus21")) {
            logInfo(log, "[WasDetector] jeus.version 파싱 → JEUS 21: " + version);
            return WasType.JEUS_9_PLUS; // JEUS 21도 JEUS_9_PLUS 계열
        }
        // "9" 또는 "9.x" — JEUS 9
        if (v.startsWith("9") || v.contains("jeus 9") || v.contains("jeus9")) {
            logInfo(log, "[WasDetector] jeus.version 파싱 → JEUS 9: " + version);
            return WasType.JEUS_9_PLUS;
        }
        // "8.5" — JEUS 8.5
        if (v.startsWith("8.5") || v.contains("jeus 8.5") || v.contains("jeus8.5")) {
            logInfo(log, "[WasDetector] jeus.version 파싱 → JEUS 8.5: " + version);
            return WasType.JEUS_8_5;
        }
        // "8" — JEUS 8
        if (v.startsWith("8") || v.contains("jeus 8") || v.contains("jeus8")) {
            logInfo(log, "[WasDetector] jeus.version 파싱 → JEUS 8: " + version);
            return WasType.JEUS_8;
        }
        // "7" — JEUS 7
        if (v.startsWith("7") || v.contains("jeus 7") || v.contains("jeus7")) {
            logInfo(log, "[WasDetector] jeus.version 파싱 → JEUS 7: " + version);
            return WasType.JEUS_7;
        }
        // "6" — JEUS 6
        if (v.startsWith("6") || v.contains("jeus 6") || v.contains("jeus6")) {
            logInfo(log, "[WasDetector] jeus.version 파싱 → JEUS 6: " + version);
            return WasType.JEUS_6;
        }
        // "5" — JEUS 5
        if (v.startsWith("5") || v.contains("jeus 5") || v.contains("jeus5")) {
            logInfo(log, "[WasDetector] jeus.version 파싱 → JEUS 5 (Legacy): " + version);
            return WasType.JEUS_LEGACY;
        }
        // "4" — JEUS 4
        if (v.startsWith("4") || v.contains("jeus 4") || v.contains("jeus4")) {
            logInfo(log, "[WasDetector] jeus.version 파싱 → JEUS 4 (Legacy): " + version);
            return WasType.JEUS_LEGACY;
        }

        logWarn(log, "[WasDetector] jeus.version 파싱 실패: " + version);
        return null;
    }

    /**
     * JEUS 설치 경로 문자열에서 버전을 추정한다.
     *
     * <p>예: {@code /opt/jeus8.5} → JEUS_8_5, {@code C:\jeus7} → JEUS_7
     * 한국 공공기관 표준 설치 경로 규칙에 따름.
     *
     * @param path JEUS 설치 경로 문자열
     * @param log  로그 스트림
     * @return 추정된 {@link WasType} 또는 null
     */
    public static WasType parseJeusVersionFromPath(String path, PrintStream log) {
        if (path == null) return null;

        String p = path.toLowerCase();

        // 경로 내 버전 힌트: jeus21, jeus9, jeus8.5, jeus8, jeus7, jeus6, jeus5, jeus4
        if (p.contains("jeus21"))  return hint(log, path, WasType.JEUS_9_PLUS);
        if (p.contains("jeus9"))   return hint(log, path, WasType.JEUS_9_PLUS);
        if (p.contains("jeus8.5")) return hint(log, path, WasType.JEUS_8_5);
        if (p.contains("jeus8"))   return hint(log, path, WasType.JEUS_8);
        if (p.contains("jeus7"))   return hint(log, path, WasType.JEUS_7);
        if (p.contains("jeus6"))   return hint(log, path, WasType.JEUS_6);
        if (p.contains("jeus5"))   return hint(log, path, WasType.JEUS_LEGACY);
        if (p.contains("jeus4"))   return hint(log, path, WasType.JEUS_LEGACY);

        return null;
    }

    private static WasType hint(PrintStream log, String path, WasType type) {
        logInfo(log, "[WasDetector] JEUS 경로 버전 힌트: " + path + " → " + type);
        return type;
    }

    /**
     * JEUS임은 확인됐으나 버전을 특정하기 어려울 때 추가 단서로 버전을 추정한다.
     *
     * <p>추정 우선순위:
     * <ol>
     *   <li>런타임 JDK 버전으로 상한 추정
     *       (JDK 11+ → 최소 JEUS_8_5, JDK 8 → JEUS_8, JDK 7 → JEUS_7, 이하 → JEUS_LEGACY)</li>
     *   <li>javax.servlet.http.HttpServletRequest 버전 클래스 존재 여부</li>
     *   <li>판별 불가 → 가장 보수적 버전(JEUS_7) 반환 + 경고</li>
     * </ol>
     *
     * <p>이 메서드는 과대 감지(높은 버전으로 오판) 시 위빙 실패 위험보다
     * 과소 감지(낮은 버전으로 오판) 시 동작 중단 위험이 더 크다고 판단하여
     * 약간 높은 버전으로 오판하는 방향으로 설계됐다.
     * 운영자는 {@code -Donepass.was.type=JEUS_6} 등으로 명시적 오버라이드 가능.
     *
     * @return 추정된 {@link WasType} (null 반환 없음)
     */
    private static WasType resolveJeusVersionFromProperty(PrintStream log) {
        int jdkMajor = getRuntimeJdkMajor();

        logInfo(log, "[WasDetector] JEUS 버전 추정 — 런타임 JDK major=" + jdkMajor);

        if (jdkMajor >= 11) {
            // JDK 11+ → JEUS 8.5 또는 9+
            // jakarta.servlet 존재 여부로 추가 분기
            if (isClassPresent(CLS_JAKARTA_SERVLET_REQUEST)) {
                logInfo(log, "[WasDetector] JDK 11+ + jakarta.servlet → JEUS 9+ 추정");
                return WasType.JEUS_9_PLUS;
            }
            logInfo(log, "[WasDetector] JDK 11+ (javax.servlet) → JEUS 8.5 추정");
            return WasType.JEUS_8_5;
        }
        if (jdkMajor == 8) {
            // JDK 8 → JEUS 7 Fix5 이상 또는 JEUS 8/8.5
            // 더 정확한 판별 불가 → JEUS_8로 추정 (byte-buddy 사용 가능)
            logInfo(log, "[WasDetector] JDK 8 → JEUS 8 추정");
            return WasType.JEUS_8;
        }
        if (jdkMajor == 7) {
            // JDK 7 → JEUS 6 Fix9 ~ JEUS 7 Fix4
            logInfo(log, "[WasDetector] JDK 7 → JEUS 7 추정");
            return WasType.JEUS_7;
        }
        if (jdkMajor == 6) {
            // JDK 6 → JEUS 6 Fix1~8 또는 JEUS 7 Fix1~4
            logInfo(log, "[WasDetector] JDK 6 → JEUS 6 추정");
            return WasType.JEUS_6;
        }
        // JDK 5 이하 → JEUS 4 또는 5 (레거시)
        logWarn(log, "[WasDetector] JDK " + jdkMajor + " → JEUS 4/5 (Legacy) 추정"
                + " (Javassist 위빙 경로 사용)");
        return WasType.JEUS_LEGACY;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 공용 내부 헬퍼
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 지정 클래스가 현재 클래스로더(또는 부트스트랩 클래스로더)에서 로드 가능한지 확인.
     *
     * <p>Agent는 부트스트랩 클래스로더 위에서 동작하므로, WAS 클래스를 직접 참조하지 않고
     * {@link Class#forName(String)} 성공 여부만으로 존재를 확인한다.
     * Thread ContextClassLoader → 시스템 ClassLoader 순으로 탐색.
     */
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

    /**
     * WAS 유형 오버라이드 문자열을 파싱한다 (대소문자 무시).
     *
     * <p>오버라이드 예: {@code -Donepass.was.type=JEUS_7},
     * {@code -Donepass.was.type=jeus_legacy} (소문자도 허용)
     */
    private static WasType parseOverride(String value) {
        try {
            return WasType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 환경 변수 읽기 (SecurityManager 예외 방어).
     *
     * <p>레거시 JDK 환경에서 SecurityManager가 환경변수 읽기를 제한할 수 있으므로
     * SecurityException을 잡아 null을 반환한다.
     */
    private static String getEnv(String name) {
        try {
            return System.getenv(name);
        } catch (SecurityException ignored) {
            return null;
        }
    }

    /**
     * 현재 JVM의 주요 Java 버전 번호를 반환한다.
     *
     * <p>JDK 8: {@code java.version} = "1.8.0_xxx" → major=8<br>
     * JDK 11: {@code java.version} = "11.0.x" → major=11<br>
     * JDK 21: {@code java.version} = "21.0.x" → major=21
     *
     * <p>레거시(1.x) 형식과 모던(x.y) 형식 모두 처리한다.
     *
     * @return JDK major 버전 번호. 파싱 실패 시 8(보수적 기본값)
     */
    public static int getRuntimeJdkMajor() {
        String version = System.getProperty("java.version", "1.8");
        try {
            if (version.startsWith("1.")) {
                // 레거시: "1.5.0_22" → major=5
                String[] parts = version.split("\\.");
                if (parts.length >= 2) {
                    return Integer.parseInt(parts[1]);
                }
            } else {
                // 모던: "11.0.18" → major=11
                int dot = version.indexOf('.');
                String majorStr = (dot < 0) ? version : version.substring(0, dot);
                return Integer.parseInt(majorStr);
            }
        } catch (NumberFormatException ignored) {
            // 파싱 실패 → 보수적 기본값
        }
        return 8;
    }

    private static void logInfo(PrintStream log, String message) {
        if (log != null) log.println(message);
    }

    private static void logWarn(PrintStream log, String message) {
        if (log != null) log.println("[WARN] " + message);
    }
}

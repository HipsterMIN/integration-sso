package io.github.hipstermin.idem.agent.was;

/**
 * 유관기관 WAS 런타임 유형 열거형.
 *
 * <p>OnePass Agent가 위빙 전략(WeavingStrategy)을 선택하는 기준이 된다.
 * 각 enum 상수는 감지 신뢰도 순으로 선언됐으며, {@link WasDetector}가
 * 클래스패스·시스템 프로퍼티·환경변수 등 다중 단서를 합산해 판정한다.
 *
 * <h2>JEUS 버전별 위빙 전략 요약</h2>
 * <pre>
 *  WAS 유형          JDK 요구 사항    Servlet API   위빙 엔진         위빙 포인트
 *  ──────────────────────────────────────────────────────────────────────────────────
 *  JEUS_LEGACY       JDK 1.4~1.5     2.3/2.4       Javassist 3.x     jeusMain.WEBMain
 *  JEUS_6            JDK 1.5~1.7     2.5           Javassist 3.x     jeus.servlet.*
 *  JEUS_7            JDK 1.6~1.8     3.0           byte-buddy        javax.servlet.Filter
 *  JEUS_8            JDK 1.7~1.8     3.1           byte-buddy        javax.servlet.Filter
 *  JEUS_8_5          JDK 1.8 or 11   4.0           byte-buddy        javax.servlet.Filter
 *  JEUS_9_PLUS       JDK 11+         5.0+          byte-buddy        jakarta.servlet.Filter
 *  ──────────────────────────────────────────────────────────────────────────────────
 *  TOMCAT_LEGACY     JDK 6~7         2.4~2.5       Javassist         ApplicationFilterChain
 *  TOMCAT_7          JDK 7           3.0           byte-buddy        CoyoteAdapter/Filter
 *  TOMCAT_8          JDK 8           3.1           byte-buddy        Catalina Valve
 *  TOMCAT_9          JDK 8+          4.0           byte-buddy        Catalina Valve
 *  TOMCAT_10_PLUS    JDK 11+         5.0+          byte-buddy        jakarta.servlet.Filter
 *  TOMCAT            JDK 8+          3.x~5.x       byte-buddy        Catalina Valve (기본)
 *  ──────────────────────────────────────────────────────────────────────────────────
 *  JBOSS_LEGACY      JDK 6~7         2.x~3.0       Javassist         javax.servlet.Filter
 *  JBOSS             JDK 8+          3.x~4.x       byte-buddy        javax.servlet.Filter
 *  WILDFLY           JDK 11+         5.0+          byte-buddy        jakarta.servlet.Filter
 *  WEBLOGIC_LEGACY   JDK 6~7         2.x~3.0       Javassist         javax.servlet.Filter
 *  WEBLOGIC          JDK 8+          3.x~4.x       byte-buddy        javax.servlet.Filter
 *  WEBSPHERE_LEGACY  JDK 6~7         2.x~3.0       Javassist         javax.servlet.Filter
 *  WEBSPHERE         JDK 8+          3.x~4.x       byte-buddy        javax.servlet.Filter
 *  GLASSFISH         JDK 8+          3.x~4.x       byte-buddy        javax.servlet.Filter
 *  RESIN             JDK 6+          2.x~4.x       byte-buddy/Jassist javax.servlet.Filter
 *  UNDERTOW          JDK 8+          3.x~5.x       byte-buddy        javax.servlet.Filter
 *  JETTY_LEGACY      JDK 6~7         2.x~3.0       Javassist         javax.servlet.Filter
 *  JETTY             JDK 8+          3.x~5.x       byte-buddy        javax.servlet.Filter
 *  UNKNOWN           JDK 8+          —             byte-buddy        javax.servlet.Filter (Fallback)
 * </pre>
 *
 * <h2>JEUS 버전별 클래스 패키지 패턴</h2>
 * <ul>
 *   <li>JEUS 4/5: {@code com.tmax.jeus.*} (구 패키지, TmaxSoft 전신 Tmax 시절)</li>
 *   <li>JEUS 6+:  {@code com.tmaxsoft.jeus.*} (신 패키지, TmaxSoft 분사 이후)</li>
 *   <li>JEUS 내부 서블릿: {@code jeus.servlet.*} (버전 공통 내부 API)</li>
 *   <li>JEUS 9+:  {@code jakarta.*} (Jakarta EE 9+ 네임스페이스 마이그레이션)</li>
 * </ul>
 *
 * <h2>JDK 1.5 SSO 가능성</h2>
 * <ul>
 *   <li>{@code java.lang.instrument.Instrumentation} premain: JDK 1.5에 도입(JSR-163) → 사용 가능</li>
 *   <li>Attach API(agentmain): JDK 1.6에 도입 → JDK 1.5에서 동적 어태치 불가, premain 전용</li>
 *   <li>byte-buddy 1.17.x: JDK 8 런타임 필수 → 레거시 환경에서 사용 불가</li>
 *   <li>Javassist 3.x: JDK 1.3+ 호환 → JDK 1.5에서 사용 가능, JEUS 4/5/6에 적용</li>
 *   <li>결론: JEUS_LEGACY(4/5), JEUS_6는 Javassist 기반 위빙 엔진 사용</li>
 * </ul>
 *
 * <h2>감지 불가 WAS 대응 (UNKNOWN)</h2>
 * <p>WasDetector가 WAS를 식별하지 못하면 UNKNOWN을 반환하고,
 * GenericFilterWeavingStrategy(Fallback)를 통해 javax/jakarta.servlet.Filter 양쪽을 위빙한다.
 * 이 방식으로 대부분의 Servlet API 기반 WAS에서 SSO가 동작한다.
 * 정확한 WAS 유형은 {@code -Donepass.was.type=XXX} 시스템 프로퍼티로 강제 지정 가능하다.
 */
public enum WasType {

    // ── JEUS 버전별 (한국 공공기관 특화) ────────────────────────────────────────

    /**
     * TmaxSoft JEUS 4 / JEUS 5 — 레거시 (JDK 1.4~1.5 환경).
     * Javassist 위빙 전용 (byte-buddy 사용 불가).
     */
    JEUS_LEGACY("JEUS 4/5 (Legacy, JDK 1.4~1.5)"),

    /** TmaxSoft JEUS 6 — (JDK 1.5~1.7 환경). Javassist 통일 위빙. */
    JEUS_6("JEUS 6 (JDK 1.5~1.7)"),

    /** TmaxSoft JEUS 7 — (JDK 1.6~1.8 환경). JDK 8: byte-buddy, JDK 7-: Javassist. */
    JEUS_7("JEUS 7 (JDK 1.6~1.8)"),

    /** TmaxSoft JEUS 8 — (JDK 1.7~1.8 환경). byte-buddy (Javassist fallback). */
    JEUS_8("JEUS 8 (JDK 1.7~1.8)"),

    /** TmaxSoft JEUS 8.5 — (JDK 1.8 or JDK 11 환경). byte-buddy, javax 마지막. */
    JEUS_8_5("JEUS 8.5 (JDK 8/11)"),

    /** TmaxSoft JEUS 9 / JEUS 21 — Jakarta EE (JDK 11+). byte-buddy + jakarta. */
    JEUS_9_PLUS("JEUS 9/21 (JDK 11+, Jakarta EE)"),

    // ── Apache Tomcat 버전별 ────────────────────────────────────────────────────

    /**
     * Apache Tomcat 5.x / 6.x — 레거시 (JDK 6~7, Servlet 2.4~2.5).
     *
     * <ul>
     *   <li>Tomcat 5.5: JDK 5+ / Servlet 2.4 / ApplicationFilterChain 위빙</li>
     *   <li>Tomcat 6.0: JDK 5~6 / Servlet 2.5 / ApplicationFilterChain 위빙</li>
     *   <li>byte-buddy 사용 불가(JDK 5~6) → Javassist로 ApplicationFilterChain.internalDoFilter() 위빙</li>
     *   <li>내부 클래스: {@code org.apache.catalina.core.ApplicationFilterChain}</li>
     *   <li>위빙 포인트: {@code ApplicationFilterChain.internalDoFilter()} — 서블릿 호출 직전</li>
     * </ul>
     *
     * <p><b>Tomcat 6 특이사항</b>: APR(Apache Portable Runtime) 커넥터 사용 시
     * {@code org.apache.catalina.connector.CoyoteAdapter}가 최상위 진입점.
     * AJP/APR 환경에서는 AJP 커넥터를 통한 요청이 Valve 체인을 거치지 않을 수 있음.
     */
    TOMCAT_LEGACY("Tomcat 5.x/6.x (JDK 5~6, Servlet 2.4~2.5)"),

    /**
     * Apache Tomcat 7.x — (JDK 7+, Servlet 3.0, Java EE 6).
     *
     * <ul>
     *   <li>JDK 7 환경에서 byte-buddy는 제한적으로 사용 가능 (JDK 8+ 권장)</li>
     *   <li>Servlet 3.0 Async 지원 시작</li>
     *   <li>위빙 포인트: {@code org.apache.catalina.core.StandardContextValve#invoke()}</li>
     *   <li>위빙 엔진: JDK 8+이면 byte-buddy, JDK 7이면 Javassist fallback</li>
     *   <li>NIO 커넥터: {@code org.apache.tomcat.util.net.NioEndpoint} 기반</li>
     *   <li>Spring Boot Embedded Tomcat 7.x 지원 포함</li>
     * </ul>
     */
    TOMCAT_7("Tomcat 7.x (JDK 7+, Servlet 3.0)"),

    /**
     * Apache Tomcat 8.x / 8.5.x — (JDK 8, Servlet 3.1, Java EE 7).
     *
     * <ul>
     *   <li>JDK 8 필수 → byte-buddy 정상 동작</li>
     *   <li>Servlet 3.1: Non-Blocking I/O 추가</li>
     *   <li>Tomcat 8.5: ALPN/HTTP2 지원, Tomcat Native 1.2</li>
     *   <li>위빙 포인트: {@code org.apache.catalina.core.StandardHostValve#invoke()} — 권장</li>
     *   <li>NIO2 커넥터 기본: {@code org.apache.tomcat.util.net.Nio2Endpoint}</li>
     *   <li>Spring Boot 1.x/2.x Embedded Tomcat 주요 버전</li>
     * </ul>
     */
    TOMCAT_8("Tomcat 8.x/8.5 (JDK 8, Servlet 3.1)"),

    /**
     * Apache Tomcat 9.x — (JDK 8+, Servlet 4.0, Java EE 8).
     *
     * <ul>
     *   <li>JDK 8+ 권장, JDK 11 지원</li>
     *   <li>Servlet 4.0 (javax 네임스페이스 마지막 버전)</li>
     *   <li>HTTP/2 서버 푸시 지원</li>
     *   <li>위빙 포인트: {@code org.apache.catalina.core.StandardHostValve#invoke()}</li>
     *   <li>Spring Boot 2.x/3.0(초기) Embedded Tomcat 주요 버전</li>
     *   <li>JMX 기반 동적 재설정 지원</li>
     * </ul>
     */
    TOMCAT_9("Tomcat 9.x (JDK 8+, Servlet 4.0)"),

    /**
     * Apache Tomcat 10.x / 10.1+ / 11.x — (JDK 11+, Servlet 5.0+, Jakarta EE 9+).
     *
     * <ul>
     *   <li>JDK 11+ 필수 (Tomcat 11: JDK 17+)</li>
     *   <li>Servlet 5.0+: {@code javax.*} → {@code jakarta.*} 완전 전환</li>
     *   <li>위빙 포인트: {@code jakarta.servlet.Filter#doFilter()} — javax 없음</li>
     *   <li>Tomcat 10.1: Servlet 6.0 (Jakarta EE 10)</li>
     *   <li>Tomcat 11: Servlet 6.1 (Jakarta EE 11, JDK 21+)</li>
     *   <li>Spring Boot 3.x Embedded Tomcat</li>
     * </ul>
     *
     * <p><b>주의</b>: javax.servlet.Filter 위빙은 동작하지 않으며,
     * 반드시 jakarta.servlet.Filter를 위빙해야 한다.
     */
    TOMCAT_10_PLUS("Tomcat 10+/11 (JDK 11+, jakarta.servlet)"),

    /**
     * Apache Tomcat — 버전 미감지 시 일반 Tomcat (JDK 8+ 기본).
     *
     * <p>버전 특화 클래스를 감지하지 못했을 때 사용.
     * TomcatWeavingStrategy(Catalina Valve + javax/jakarta 이중 지원)로 처리.
     */
    TOMCAT("Tomcat (버전 미감지, 기본)"),

    // ── JBoss / WildFly ──────────────────────────────────────────────────────────

    /**
     * JBoss AS 5.x / 6.x — 레거시 (JDK 6~7, Servlet 2.4~3.0).
     *
     * <ul>
     *   <li>JBoss AS 5: JDK 6 / Servlet 2.4 / JBoss Microcontainer 기반</li>
     *   <li>JBoss AS 6: JDK 6~7 / Servlet 3.0 / CDI 1.0</li>
     *   <li>위빙 엔진: Javassist (JDK 6~7 환경)</li>
     *   <li>위빙 포인트: {@code javax.servlet.Filter#doFilter()}</li>
     *   <li>클래스: {@code org.jboss.web.tomcat.service.TomcatDeployer}</li>
     * </ul>
     */
    JBOSS_LEGACY("JBoss AS 5/6 (JDK 6~7, Legacy)"),

    /**
     * JBoss EAP 6.x / 7.x — (JDK 8+, Servlet 3.1, Java EE 7).
     *
     * <ul>
     *   <li>JBoss EAP 7: JDK 8+ / Servlet 3.1 / Jakarta EE 8(EAP 7.4)</li>
     *   <li>내부적으로 Undertow 서블릿 컨테이너 사용</li>
     *   <li>위빙 포인트: {@code javax.servlet.Filter#doFilter()}</li>
     *   <li>클래스: {@code org.jboss.as.server.Bootstrap}</li>
     * </ul>
     */
    JBOSS("JBoss EAP 6/7 (JDK 8+)"),

    /**
     * Red Hat WildFly 27+ / WildFly Preview — (JDK 11+, Jakarta EE 9+).
     *
     * <ul>
     *   <li>WildFly 27+: Jakarta EE 10 / Servlet 6.0 ({@code jakarta.*})</li>
     *   <li>내부적으로 Undertow + RESTEasy 조합</li>
     *   <li>위빙 포인트: {@code jakarta.servlet.Filter#doFilter()}</li>
     *   <li>클래스: {@code org.wildfly.extension.undertow.UndertowService}</li>
     * </ul>
     */
    WILDFLY("WildFly 27+ (JDK 11+, Jakarta EE)"),

    // ── Oracle WebLogic ──────────────────────────────────────────────────────────

    /**
     * Oracle WebLogic Server 10.x / 11g / 12c(초기) — 레거시 (JDK 6~7).
     *
     * <ul>
     *   <li>WebLogic 10.3.x: JDK 6 / Servlet 2.5 / Java EE 5</li>
     *   <li>WebLogic 12.1.1: JDK 7 / Servlet 3.0 / Java EE 6</li>
     *   <li>위빙 엔진: Javassist (JDK 6~7 환경에서 byte-buddy 불가)</li>
     *   <li>위빙 포인트: {@code javax.servlet.Filter#doFilter()}</li>
     *   <li>클래스: {@code weblogic.servlet.internal.FilterChainImpl}</li>
     *   <li>WebLogic 고유 클래스로더 계층이 복잡 → 위빙 주의 필요</li>
     * </ul>
     */
    WEBLOGIC_LEGACY("WebLogic 10.x/11g/12c-early (JDK 6~7)"),

    /**
     * Oracle WebLogic Server 12c(후기) / 14c — (JDK 8+, Servlet 3.1~4.0, Java EE 7~8).
     *
     * <ul>
     *   <li>WebLogic 12.2.x: JDK 8+ / Servlet 3.1 / Java EE 7</li>
     *   <li>WebLogic 14.1.1: JDK 11/17 / Servlet 4.0 / Java EE 8 + Jakarta EE 8</li>
     *   <li>위빙 포인트: {@code javax.servlet.Filter#doFilter()}</li>
     *   <li>클래스: {@code weblogic.t3.srvr.T3Srvr}</li>
     *   <li>WebLogic Filtering ClassLoader: Agent 클래스 가시성 이슈 주의</li>
     * </ul>
     */
    WEBLOGIC("WebLogic 12c(후기)/14c (JDK 8+)"),

    // ── IBM WebSphere ────────────────────────────────────────────────────────────

    /**
     * IBM WebSphere Application Server (WAS) 7.x / 8.x — 레거시 (JDK 6~7).
     *
     * <ul>
     *   <li>WebSphere 7.0: JDK 6 / Servlet 2.5 / Java EE 5</li>
     *   <li>WebSphere 8.0/8.5: JDK 6~7 / Servlet 3.0 / Java EE 6/7</li>
     *   <li>위빙 엔진: Javassist (JDK 6~7, byte-buddy 불가)</li>
     *   <li>위빙 포인트: {@code javax.servlet.Filter#doFilter()}</li>
     *   <li>클래스: {@code com.ibm.ws.webcontainer.filter.WebAppFilterManager}</li>
     *   <li>IBM JVM(J9)과 Oracle HotSpot의 byte-buddy 동작 차이 주의</li>
     * </ul>
     */
    WEBSPHERE_LEGACY("WebSphere 7.x/8.x (JDK 6~7)"),

    /**
     * IBM WebSphere Liberty / Open Liberty — (JDK 8+, Servlet 3.1~6.0).
     *
     * <ul>
     *   <li>WebSphere Liberty: JDK 8+ / Servlet 3.1~4.0 / Java EE 7~8</li>
     *   <li>Open Liberty: JDK 11+ / Servlet 5.0~6.0 / Jakarta EE 9~10</li>
     *   <li>위빙 포인트: {@code javax.servlet.Filter} 또는 {@code jakarta.servlet.Filter}</li>
     *   <li>클래스: {@code com.ibm.ws.webcontainer.WebContainer}</li>
     *   <li>IBM J9 JVM 특화: JVM TI 인터페이스 차이 고려</li>
     * </ul>
     */
    WEBSPHERE("WebSphere Liberty / Open Liberty (JDK 8+)"),

    // ── GlassFish / Payara ───────────────────────────────────────────────────────

    /**
     * Oracle GlassFish Server 3.x / 4.x / Payara — (JDK 7~8+, Servlet 3.0~3.1).
     *
     * <ul>
     *   <li>GlassFish 3.1: JDK 7 / Servlet 3.0 / Java EE 6</li>
     *   <li>GlassFish 4.x: JDK 8 / Servlet 3.1 / Java EE 7</li>
     *   <li>Payara 5.x: GlassFish 4 fork, JDK 8+ / Servlet 4.0</li>
     *   <li>위빙 포인트: {@code javax.servlet.Filter#doFilter()}</li>
     *   <li>클래스: {@code com.sun.enterprise.web.WebContainer}</li>
     *   <li>Grizzly NIO 프레임워크 기반 — 위빙 포인트 선택 주의</li>
     * </ul>
     */
    GLASSFISH("GlassFish 3/4 / Payara (JDK 7~8+)"),

    /**
     * Eclipse GlassFish 6+ / Payara 6+ — (JDK 11+, Servlet 5.0+, Jakarta EE 9+).
     *
     * <ul>
     *   <li>GlassFish 6.x: JDK 11+ / Servlet 5.0 / Jakarta EE 9</li>
     *   <li>GlassFish 7.x: JDK 11+ / Servlet 6.0 / Jakarta EE 10</li>
     *   <li>Payara 6.x: JDK 11+ / Jakarta EE 10</li>
     *   <li>위빙 포인트: {@code jakarta.servlet.Filter#doFilter()}</li>
     *   <li>클래스: {@code org.glassfish.main.jul.handler.GlassFishLogHandler} (GF7+)</li>
     * </ul>
     */
    GLASSFISH_JAKARTA("GlassFish 6+/Payara 6+ (JDK 11+, Jakarta EE)"),

    // ── Caucho Resin ─────────────────────────────────────────────────────────────

    /**
     * Caucho Resin — 공공기관에서 간혹 사용되는 경량 WAS (JDK 6+, Servlet 2.4~3.1).
     *
     * <ul>
     *   <li>Resin 3.x: JDK 6 / Servlet 2.4~2.5</li>
     *   <li>Resin 4.x: JDK 7+ / Servlet 3.0~3.1</li>
     *   <li>위빙 포인트: {@code javax.servlet.Filter#doFilter()}</li>
     *   <li>클래스: {@code com.caucho.server.http.HttpRequest}</li>
     *   <li>경량 서버로 공공기관 일부에서 사용 확인됨</li>
     * </ul>
     */
    RESIN("Caucho Resin (JDK 6+)"),

    // ── Eclipse Jetty 버전별 ─────────────────────────────────────────────────────

    /**
     * Eclipse Jetty 7.x / 8.x — 레거시 (JDK 7, Servlet 3.0).
     * 구 패키지: {@code org.eclipse.jetty} (이후 동일 유지).
     */
    JETTY_LEGACY("Jetty 7/8 (JDK 7, Servlet 3.0)"),

    /**
     * Eclipse Jetty 9.x / 10.x / 11.x — (JDK 8~11, Servlet 3.1~4.0).
     *
     * <ul>
     *   <li>Jetty 9.4: JDK 8 / Servlet 3.1</li>
     *   <li>Jetty 10: JDK 11 / Servlet 4.0 (javax 마지막)</li>
     *   <li>Jetty 11: JDK 11 / Servlet 5.0 (jakarta.servlet 전환)</li>
     * </ul>
     */
    JETTY("Jetty 9~11 (JDK 8~11)"),

    /**
     * Eclipse Jetty 12+ — (JDK 17+, Servlet 6.0+, Jakarta EE 10+).
     *
     * <ul>
     *   <li>Jetty 12: JDK 17+ / Jakarta EE 10 / Servlet 6.0</li>
     *   <li>EE10 API Core Handler 아키텍처 도입</li>
     *   <li>위빙 포인트: {@code jakarta.servlet.Filter#doFilter()}</li>
     * </ul>
     */
    JETTY_JAKARTA("Jetty 12+ (JDK 17+, Jakarta EE 10)"),

    // ── Standalone Undertow ──────────────────────────────────────────────────────

    /** Red Hat Undertow — Standalone (JBoss/WildFly 내장이 아닌 독립 실행). */
    UNDERTOW("Undertow Standalone"),

    // ── 감지 불가 Fallback ───────────────────────────────────────────────────────

    /**
     * WAS 감지 실패 — Generic Servlet Filter로 Fallback.
     *
     * <p>WasDetector가 WAS를 식별하지 못하면 이 유형이 반환된다.
     * GenericFilterWeavingStrategy를 통해 javax/jakarta.servlet.Filter 양쪽을 위빙하므로
     * 대부분의 Servlet API 기반 WAS에서 SSO가 동작한다.
     *
     * <p><b>운영자 조치</b>: 감지되지 않은 WAS가 있으면 OnePass 팀에 신고하여
     * 다음 버전에서 전용 전략을 추가할 수 있도록 한다.
     * 즉시 수동 오버라이드: {@code -Donepass.was.type=UNKNOWN} (또는 가장 가까운 WAS 유형)
     */
    UNKNOWN("Unknown (Generic Fallback)");

    // ─────────────────────────────────────────────────────────────────────────────

    private final String displayName;

    WasType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * JEUS 계열 WAS인지 여부 (버전 무관).
     */
    public boolean isJeus() {
        return this == JEUS_LEGACY
                || this == JEUS_6
                || this == JEUS_7
                || this == JEUS_8
                || this == JEUS_8_5
                || this == JEUS_9_PLUS;
    }

    /**
     * Apache Tomcat 계열 WAS인지 여부 (버전 무관).
     */
    public boolean isTomcat() {
        return this == TOMCAT_LEGACY
                || this == TOMCAT_7
                || this == TOMCAT_8
                || this == TOMCAT_9
                || this == TOMCAT_10_PLUS
                || this == TOMCAT;
    }

    /**
     * JBoss/WildFly 계열 WAS인지 여부.
     */
    public boolean isJBoss() {
        return this == JBOSS_LEGACY || this == JBOSS || this == WILDFLY;
    }

    /**
     * Oracle WebLogic 계열 WAS인지 여부.
     */
    public boolean isWebLogic() {
        return this == WEBLOGIC_LEGACY || this == WEBLOGIC;
    }

    /**
     * IBM WebSphere 계열 WAS인지 여부.
     */
    public boolean isWebSphere() {
        return this == WEBSPHERE_LEGACY || this == WEBSPHERE;
    }

    /**
     * GlassFish/Payara 계열 WAS인지 여부.
     */
    public boolean isGlassFish() {
        return this == GLASSFISH || this == GLASSFISH_JAKARTA;
    }

    /**
     * Jetty 계열 WAS인지 여부.
     */
    public boolean isJetty() {
        return this == JETTY_LEGACY || this == JETTY || this == JETTY_JAKARTA;
    }

    /**
     * Jakarta EE 9+ 네임스페이스를 사용하는 WAS인지 여부.
     *
     * <p>이 메서드가 true를 반환하면 javax.servlet.Filter 위빙은 불필요하며,
     * jakarta.servlet.Filter만 위빙해야 한다.
     *
     * @return javax.servlet 없이 jakarta.servlet만 있는 WAS이면 true
     */
    public boolean isJakartaOnly() {
        return this == JEUS_9_PLUS
                || this == TOMCAT_10_PLUS
                || this == WILDFLY
                || this == GLASSFISH_JAKARTA
                || this == JETTY_JAKARTA;
    }

    /**
     * byte-buddy 위빙 엔진을 우선 사용하는 WAS 계열인지 여부.
     *
     * <p>JEUS_LEGACY(JDK 1.4~1.5)와 JEUS_6(JDK 1.5~1.7)는 Javassist 전용.
     * 레거시 계열 Tomcat, JBoss, WebLogic, WebSphere, Jetty는 런타임 JDK 버전에 따라 분기.
     *
     * @return Javassist 전용 계열이면 false, 나머지 true
     */
    public boolean prefersByteBuddy() {
        return this != JEUS_LEGACY
                && this != JEUS_6
                && this != TOMCAT_LEGACY
                && this != JBOSS_LEGACY
                && this != WEBLOGIC_LEGACY
                && this != WEBSPHERE_LEGACY
                && this != JETTY_LEGACY;
    }

    /**
     * Javassist 위빙 엔진을 우선 사용하는 레거시 WAS 계열인지 여부.
     */
    public boolean isLegacyJavassist() {
        return this == JEUS_LEGACY
                || this == JEUS_6
                || this == TOMCAT_LEGACY
                || this == JBOSS_LEGACY
                || this == WEBLOGIC_LEGACY
                || this == WEBSPHERE_LEGACY
                || this == JETTY_LEGACY;
    }

    /**
     * javax와 jakarta 양쪽 위빙이 필요한 WAS인지 여부 (전환기 WAS).
     *
     * <p>JEUS 9+, Tomcat 10+(Java EE 8 호환 모드), JBoss EAP 7.4 등
     * 일부 WAS는 javax/jakarta 모두를 지원하는 호환 레이어를 제공.
     */
    public boolean needsDualNamespace() {
        return this == JEUS_9_PLUS
                || this == JEUS_8_5
                || this == TOMCAT_10_PLUS
                || this == WEBSPHERE;
    }

    @Override
    public String toString() {
        return displayName;
    }
}

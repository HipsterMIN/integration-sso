package kr.go.smes.agent.was;

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
 *  TOMCAT            JDK 8+          3.x~5.x       byte-buddy        org.apache.catalina.Valve
 *  JBOSS             JDK 8+          3.x~5.x       byte-buddy        javax.servlet.Filter
 *  WEBLOGIC          JDK 8+          3.x~4.x       byte-buddy        javax.servlet.Filter
 *  UNDERTOW          JDK 8+          3.x~5.x       byte-buddy        javax.servlet.Filter
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
 */
public enum WasType {

    // ── JEUS 버전별 (한국 공공기관 특화) ────────────────────────────────────────

    /**
     * TmaxSoft JEUS 4 / JEUS 5 — 레거시 (JDK 1.4~1.5 환경).
     *
     * <ul>
     *   <li>JDK: 1.4~1.5 (byte-buddy 사용 불가 → Javassist 3.x 위빙)</li>
     *   <li>Servlet: 2.3 (JEUS 4) / 2.4 (JEUS 5)</li>
     *   <li>Java EE: J2EE 1.3 / J2EE 1.4</li>
     *   <li>클래스 패키지: {@code com.tmax.jeus.*} (구 패키지)</li>
     *   <li>위빙 포인트: {@code jeus.servlet.JeusHttpServlet}, {@code com.tmax.jeus.web.*}</li>
     *   <li>premain 전용 (agentmain/Attach API는 JDK 1.6+ 전용)</li>
     * </ul>
     *
     * <p><b>주의</b>: 한국 공공기관에서 아직 JEUS 4를 운영하는 경우,
     * JDK 1.5 환경에서 {@code premain()}을 통해 SSO 위빙이 가능하다.
     * 단, byte-buddy를 쓸 수 없으므로 반드시 Javassist 경로를 사용해야 한다.
     */
    JEUS_LEGACY("JEUS 4/5 (Legacy, JDK 1.4~1.5)"),

    /**
     * TmaxSoft JEUS 6 — (JDK 1.5~1.7 환경).
     *
     * <ul>
     *   <li>JDK: 1.5~1.7 (Fix1~8: 1.5~1.6, Fix9 이상: 1.7까지)</li>
     *   <li>Servlet: 2.5 / Java EE 5</li>
     *   <li>클래스 패키지: {@code com.tmaxsoft.jeus.*} (신 패키지로 전환)</li>
     *   <li>위빙 포인트: {@code jeus.servlet.JeusHttpServlet},
     *       {@code com.tmaxsoft.jeus.web.servlet.*}</li>
     *   <li>JDK 1.5 환경이면 Javassist 위빙 / JDK 1.6이면 byte-buddy 가능하나
     *       안전을 위해 Javassist 통일 (런타임 JDK 버전 자동 분기)</li>
     * </ul>
     */
    JEUS_6("JEUS 6 (JDK 1.5~1.7)"),

    /**
     * TmaxSoft JEUS 7 — (JDK 1.6~1.8 환경).
     *
     * <ul>
     *   <li>JDK: 1.6~1.8 (Fix1~4: 1.6~1.7, Fix5 이상: 1.8까지)</li>
     *   <li>Servlet: 3.0 / Java EE 6</li>
     *   <li>클래스 패키지: {@code com.tmaxsoft.jeus.*}</li>
     *   <li>위빙 포인트: {@code javax.servlet.Filter} (Servlet 3.0 표준)</li>
     *   <li>JDK 1.7이면 Javassist, JDK 1.8이면 byte-buddy 선택 가능
     *       (런타임 JDK 버전으로 자동 분기)</li>
     * </ul>
     */
    JEUS_7("JEUS 7 (JDK 1.6~1.8)"),

    /**
     * TmaxSoft JEUS 8 — (JDK 1.7~1.8 환경).
     *
     * <ul>
     *   <li>JDK: 1.7~1.8</li>
     *   <li>Servlet: 3.1 / Java EE 7</li>
     *   <li>클래스 패키지: {@code com.tmaxsoft.jeus.*}</li>
     *   <li>위빙 포인트: {@code javax.servlet.Filter}</li>
     *   <li>JDK 1.8 기준 byte-buddy 사용 가능 (JDK 1.7이면 Javassist fallback)</li>
     * </ul>
     */
    JEUS_8("JEUS 8 (JDK 1.7~1.8)"),

    /**
     * TmaxSoft JEUS 8.5 — (JDK 1.8 or JDK 11 환경).
     *
     * <ul>
     *   <li>JDK: 1.8 또는 11 (Java 11 LTS 정식 지원)</li>
     *   <li>Servlet: 4.0 / Java EE 8</li>
     *   <li>클래스 패키지: {@code com.tmaxsoft.jeus.*}</li>
     *   <li>위빙 포인트: {@code javax.servlet.Filter} (Servlet 4.0 = javax 네임스페이스 마지막)</li>
     *   <li>byte-buddy 정상 동작</li>
     * </ul>
     */
    JEUS_8_5("JEUS 8.5 (JDK 8/11)"),

    /**
     * TmaxSoft JEUS 9 / JEUS 21 — 최신 버전 (JDK 11+ / JDK 21+).
     *
     * <ul>
     *   <li>JEUS 9: JDK 11+ / Servlet 5.0 / Jakarta EE 9 ({@code jakarta.servlet.*})</li>
     *   <li>JEUS 21: JDK 21+ / Servlet 6.0 / Jakarta EE 10</li>
     *   <li>클래스 패키지: {@code com.tmaxsoft.jeus.*} + {@code jakarta.*}</li>
     *   <li>위빙 포인트: {@code jakarta.servlet.Filter}
     *       ({@code javax.servlet.Filter}는 제거됨)</li>
     *   <li>byte-buddy + Jakarta 이중 지원 전략 (GenericFilterWeavingStrategy)</li>
     * </ul>
     */
    JEUS_9_PLUS("JEUS 9/21 (JDK 11+, Jakarta EE)"),

    // ── 기존 WAS ─────────────────────────────────────────────────────────────────

    /** Apache Tomcat / Spring Boot Embedded Tomcat */
    TOMCAT("Tomcat"),

    /** JBoss EAP / WildFly (Undertow 기반이지만 JBoss 전용 클래스 존재) */
    JBOSS("JBoss/WildFly"),

    /** Oracle WebLogic Server */
    WEBLOGIC("WebLogic"),

    /** Standalone Red Hat Undertow */
    UNDERTOW("Undertow"),

    /** Eclipse Jetty */
    JETTY("Jetty"),

    /** 감지 불가 — Generic Servlet Filter로 Fallback */
    UNKNOWN("Unknown");

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
     *
     * <p>위빙 전략 선택 분기, 로그 필터링 등에 활용.
     *
     * @return JEUS_LEGACY / JEUS_6 / JEUS_7 / JEUS_8 / JEUS_8_5 / JEUS_9_PLUS 중 하나이면 true
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
     * byte-buddy 위빙 엔진을 사용할 수 있는 JDK 버전인지 여부.
     *
     * <p>byte-buddy 1.17.x는 JDK 8 런타임 이상 필요.
     * JEUS_LEGACY(JDK 1.4~1.5)와 JEUS_6(JDK 1.5~1.7)는 Javassist 위빙으로 대응.
     * JEUS_7/8은 JDK 1.6~1.8 범위이므로 런타임 버전에 따라 동적 분기.
     *
     * <p><b>설계 결정</b>: 이 메서드는 "이 WasType 상수가 byte-buddy를 우선 사용하는
     * 버전 계열인가"를 나타낸다. 실제 런타임 JDK 버전 체크는
     * {@link kr.go.smes.agent.weaving.jeus.JeusWeavingEngineSelector}에서 수행.
     *
     * @return JEUS_LEGACY, JEUS_6 이면 false (Javassist 전용 계열); 나머지 true
     */
    public boolean prefersByteBuddy() {
        return this != JEUS_LEGACY && this != JEUS_6;
    }

    @Override
    public String toString() {
        return displayName;
    }
}

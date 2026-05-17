package kr.go.smes.agent.was;

/**
 * 유관기관 WAS 런타임 유형 열거형.
 *
 * <p>OnePass Agent가 위빙 전략(WeavingStrategy)을 선택하는 기준이 된다.
 * 각 enum 상수는 감지 신뢰도 순으로 선언됐으며, {@link WasDetector}가
 * 클래스패스·시스템 프로퍼티·JNDI 등 다중 단서를 합산해 판정한다.
 *
 * <pre>
 *  WAS                  위빙 포인트
 *  ─────────────────────────────────────────────────
 *  TOMCAT               org.apache.catalina.Valve 체인
 *  JBOSS / WILDFLY      javax.servlet.Filter (Undertow 위에서 동작)
 *  WEBLOGIC             javax.servlet.Filter (WebLogic-specific Valve 없음)
 *  UNDERTOW             javax.servlet.Filter (io.undertow.servlet 경유)
 *  JETTY                javax.servlet.Filter
 *  UNKNOWN              javax.servlet.Filter (Generic Fallback)
 * </pre>
 */
public enum WasType {

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

    private final String displayName;

    WasType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}

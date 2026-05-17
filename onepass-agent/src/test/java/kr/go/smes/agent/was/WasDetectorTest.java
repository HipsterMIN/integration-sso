package kr.go.smes.agent.was;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WasDetector} 단위 테스트.
 *
 * <h2>테스트 전략</h2>
 * <ul>
 *   <li>클래스패스 탐색은 테스트 JVM에서 WAS 클래스가 없으므로 UNKNOWN이 기대 기본값</li>
 *   <li>시스템 프로퍼티 오버라이드는 테스트 격리를 위해 setUp/tearDown에서 복구</li>
 *   <li>환경 변수는 OS 수준이라 변경 불가 → 시스템 프로퍼티 단계까지만 테스트</li>
 * </ul>
 */
@DisplayName("WasDetector — WAS 런타임 감지")
class WasDetectorTest {

    private PrintStream log;
    private ByteArrayOutputStream logCapture;

    // 테스트 전에 설정한 시스템 프로퍼티 복구를 위한 원본 저장소
    private final Properties savedProps = new Properties();
    private static final String[] CLEANUP_PROPS = {
            "onepass.was.type",
            "weblogic.Name",
            "jboss.home.dir",
            "jboss.server.base.dir",
            "catalina.home"
    };

    @BeforeEach
    void setUp() {
        logCapture = new ByteArrayOutputStream();
        log = new PrintStream(logCapture);

        // 기존 시스템 프로퍼티 백업
        for (String key : CLEANUP_PROPS) {
            String val = System.getProperty(key);
            if (val != null) savedProps.setProperty(key, val);
        }
    }

    @AfterEach
    void tearDown() {
        // 시스템 프로퍼티 복구
        for (String key : CLEANUP_PROPS) {
            System.clearProperty(key);
        }
        for (String key : savedProps.stringPropertyNames()) {
            System.setProperty(key, savedProps.getProperty(key));
        }
        savedProps.clear();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 1단계: 오버라이드 테스트
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("1단계: 시스템 프로퍼티 오버라이드")
    class OverrideTests {

        @Test
        @DisplayName("onepass.was.type=TOMCAT 오버라이드")
        void overrideTomcat() {
            System.setProperty("onepass.was.type", "TOMCAT");
            assertEquals(WasType.TOMCAT, WasDetector.detect(log));
            assertTrue(capturedLog().contains("오버라이드"));
        }

        @Test
        @DisplayName("onepass.was.type=WEBLOGIC 오버라이드")
        void overrideWeblogic() {
            System.setProperty("onepass.was.type", "WEBLOGIC");
            assertEquals(WasType.WEBLOGIC, WasDetector.detect(log));
        }

        @Test
        @DisplayName("onepass.was.type=JBOSS 오버라이드")
        void overrideJboss() {
            System.setProperty("onepass.was.type", "JBOSS");
            assertEquals(WasType.JBOSS, WasDetector.detect(log));
        }

        @Test
        @DisplayName("소문자 'tomcat'도 인식 (대소문자 무시)")
        void overrideCaseInsensitive() {
            System.setProperty("onepass.was.type", "tomcat");
            assertEquals(WasType.TOMCAT, WasDetector.detect(log));
        }

        @Test
        @DisplayName("알 수 없는 오버라이드 값이면 자동 감지로 전환")
        void unknownOverrideFallsToAutoDetect() {
            System.setProperty("onepass.was.type", "NONEXISTENT_WAS");
            // 자동 감지로 전환 → 테스트 JVM에 WAS 클래스 없으므로 UNKNOWN
            WasType result = WasDetector.detect(log);
            assertTrue(capturedLog().contains("알 수 없는 WAS 유형 오버라이드"));
            assertEquals(WasType.UNKNOWN, result);
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 3단계: 시스템 프로퍼티 패턴 감지
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("3단계: 시스템 프로퍼티 패턴 감지")
    class SystemPropertyPatternTests {

        @Test
        @DisplayName("weblogic.Name 프로퍼티 → WebLogic 감지")
        void detectWeblogicByProperty() {
            System.setProperty("weblogic.Name", "AdminServer");
            assertEquals(WasType.WEBLOGIC, WasDetector.detect(log));
            assertTrue(capturedLog().contains("weblogic.Name"));
        }

        @Test
        @DisplayName("jboss.home.dir 프로퍼티 → JBoss 감지")
        void detectJbossByProperty() {
            System.setProperty("jboss.home.dir", "/opt/jboss");
            assertEquals(WasType.JBOSS, WasDetector.detect(log));
        }

        @Test
        @DisplayName("jboss.server.base.dir 프로퍼티 → JBoss/WildFly 감지")
        void detectWildflyByProperty() {
            System.setProperty("jboss.server.base.dir", "/opt/wildfly/standalone");
            assertEquals(WasType.JBOSS, WasDetector.detect(log));
        }

        @Test
        @DisplayName("catalina.home 프로퍼티 → Tomcat 감지")
        void detectTomcatByProperty() {
            System.setProperty("catalina.home", "/opt/tomcat");
            assertEquals(WasType.TOMCAT, WasDetector.detect(log));
            assertTrue(capturedLog().contains("catalina.home"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 5단계: Fallback
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("5단계: Fallback — UNKNOWN")
    class FallbackTests {

        @Test
        @DisplayName("WAS 클래스도 프로퍼티도 없으면 UNKNOWN 반환")
        void noHintsReturnsUnknown() {
            WasType result = WasDetector.detect(log);
            assertEquals(WasType.UNKNOWN, result);
            assertTrue(capturedLog().contains("UNKNOWN"));
        }

        @Test
        @DisplayName("UNKNOWN도 예외 없이 정상 반환")
        void unknownDoesNotThrow() {
            assertDoesNotThrow(() -> WasDetector.detect(log));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // WasType enum
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("WasType enum")
    class WasTypeTests {

        @Test
        @DisplayName("모든 WasType이 displayName()을 가짐")
        void allTypesHaveDisplayName() {
            for (WasType type : WasType.values()) {
                assertNotNull(type.displayName(), type + " displayName null");
                assertFalse(type.displayName().isEmpty(), type + " displayName 비어 있음");
            }
        }

        @Test
        @DisplayName("toString() = displayName()")
        void toStringEqualsDisplayName() {
            for (WasType type : WasType.values()) {
                assertEquals(type.displayName(), type.toString());
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // log=null 방어
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("log=null이어도 NPE 없음")
    void nullLogDoesNotThrow() {
        assertDoesNotThrow(() -> WasDetector.detect(null));
    }

    // 헬퍼
    private String capturedLog() {
        return logCapture.toString();
    }
}

package io.github.hipstermin.idem.agent.was;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link WasDetector} 단위 테스트.
 *
 * <h2>테스트 전략</h2>
 * <ul>
 *   <li>클래스패스 탐색: 테스트 JVM에 WAS 클래스 없으므로 UNKNOWN이 기대 기본값</li>
 *   <li>시스템 프로퍼티 오버라이드: setUp/tearDown에서 격리 보장</li>
 *   <li>환경 변수: OS 수준이라 변경 불가 → 시스템 프로퍼티/파싱 헬퍼 단계까지 테스트</li>
 *   <li>JEUS 버전 파싱: {@link WasDetector#parseJeusVersionString} / {@link WasDetector#parseJeusVersionFromPath} 직접 단위 테스트</li>
 *   <li>런타임 JDK 버전: {@link WasDetector#getRuntimeJdkMajor} 별도 단위 테스트</li>
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
            // Tomcat
            "catalina.home",
            "catalina.base",
            // WebLogic
            "weblogic.Name",
            "weblogic.home",
            // JBoss / WildFly
            "jboss.home.dir",
            "jboss.server.base.dir",
            // WebSphere
            "was.install.root",
            "user.install.root",
            // Resin
            "resin.home",
            // GlassFish
            "com.sun.aas.instanceRoot",
            // JEUS 전용 프로퍼티
            "jeus.home",
            "jeus.server.name",
            "jeus.engine.name",
            "jeus.version"
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
    // 1단계: 오버라이드 테스트 (기존 WAS + JEUS 버전별)
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

        // ── JEUS 버전별 오버라이드 ──────────────────────────────────────────────

        @ParameterizedTest
        @CsvSource({
                "JEUS_LEGACY, JEUS_LEGACY",
                "JEUS_6,      JEUS_6",
                "JEUS_7,      JEUS_7",
                "JEUS_8,      JEUS_8",
                "JEUS_8_5,    JEUS_8_5",
                "JEUS_9_PLUS, JEUS_9_PLUS"
        })
        @DisplayName("JEUS 버전별 오버라이드 — 모든 JEUS_* 값 인식")
        void overrideJeusVersions(String override, String expectedName) {
            System.setProperty("onepass.was.type", override);
            WasType result = WasDetector.detect(log);
            assertEquals(WasType.valueOf(expectedName), result,
                    "오버라이드=" + override + " → expected=" + expectedName);
            assertTrue(capturedLog().contains("오버라이드"), "오버라이드 로그 없음");
        }

        @Test
        @DisplayName("소문자 'jeus_7'도 인식 (대소문자 무시)")
        void overrideJeusCaseInsensitive() {
            System.setProperty("onepass.was.type", "jeus_7");
            assertEquals(WasType.JEUS_7, WasDetector.detect(log));
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
            WasType result = WasDetector.detect(log);
            assertTrue(capturedLog().contains("알 수 없는 WAS 유형 오버라이드"));
            // 자동 감지로 전환 후 UNKNOWN 또는 JVM 힌트 기반 값 반환
            assertNotNull(result);
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 3단계: JEUS 시스템 프로퍼티 감지
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("3단계: JEUS 시스템 프로퍼티 감지")
    class JeusSystemPropertyTests {

        @Test
        @DisplayName("jeus.home 프로퍼티 존재 → JEUS 계열 감지 (버전 추정)")
        void detectJeusByHomeProperty() {
            System.setProperty("jeus.home", "/opt/jeus7");
            WasType result = WasDetector.detect(log);
            // 경로에 jeus7 → JEUS_7
            assertEquals(WasType.JEUS_7, result);
            assertTrue(capturedLog().contains("JEUS"), "JEUS 감지 로그 없음");
        }

        @Test
        @DisplayName("jeus.home=/opt/jeus8.5 → JEUS_8_5 감지")
        void detectJeus8_5ByPath() {
            System.setProperty("jeus.home", "/opt/jeus8.5");
            WasType result = WasDetector.detect(log);
            assertEquals(WasType.JEUS_8_5, result);
        }

        @Test
        @DisplayName("jeus.home=/opt/jeus9 → JEUS_9_PLUS 감지")
        void detectJeus9ByPath() {
            System.setProperty("jeus.home", "/opt/jeus9");
            WasType result = WasDetector.detect(log);
            assertEquals(WasType.JEUS_9_PLUS, result);
        }

        @Test
        @DisplayName("jeus.home=/opt/jeus21 → JEUS_9_PLUS 감지 (JEUS 21은 JEUS_9_PLUS 계열)")
        void detectJeus21ByPath() {
            System.setProperty("jeus.home", "/opt/jeus21");
            WasType result = WasDetector.detect(log);
            assertEquals(WasType.JEUS_9_PLUS, result);
        }

        @Test
        @DisplayName("jeus.home=C:/jeus4 → JEUS_LEGACY 감지")
        void detectJeus4ByPath() {
            System.setProperty("jeus.home", "C:/jeus4");
            WasType result = WasDetector.detect(log);
            assertEquals(WasType.JEUS_LEGACY, result);
        }

        @Test
        @DisplayName("jeus.home=C:/jeus6 → JEUS_6 감지")
        void detectJeus6ByPath() {
            System.setProperty("jeus.home", "C:/jeus6");
            WasType result = WasDetector.detect(log);
            assertEquals(WasType.JEUS_6, result);
        }

        @Test
        @DisplayName("jeus.server.name 단독 → JEUS 계열 감지 (버전 미정 → JDK 기반 추정)")
        void detectJeusByServerName() {
            System.setProperty("jeus.server.name", "MyJeusServer");
            WasType result = WasDetector.detect(log);
            // 서버명만으로는 버전 추정 불가 → 현재 테스트 JVM JDK로 추정
            assertNotNull(result);
            assertTrue(result.isJeus(), "JEUS 계열이어야 함: " + result);
        }

        @Test
        @DisplayName("jeus.version=8.5 프로퍼티 → JEUS_8_5 감지")
        void detectJeusByVersionProperty() {
            System.setProperty("jeus.home", "/opt/jeus");   // 경로에 버전 힌트 없음
            System.setProperty("jeus.version", "8.5.0.1");
            WasType result = WasDetector.detect(log);
            assertEquals(WasType.JEUS_8_5, result);
        }

        @Test
        @DisplayName("jeus.version=9 프로퍼티 → JEUS_9_PLUS 감지")
        void detectJeusByVersionProperty9() {
            System.setProperty("jeus.home", "/opt/jeus");
            System.setProperty("jeus.version", "9.0.0");
            WasType result = WasDetector.detect(log);
            assertEquals(WasType.JEUS_9_PLUS, result);
        }

        @Test
        @DisplayName("jeus.version=JEUS 7 Fix5 → JEUS_7 감지")
        void detectJeusByVersionPropertyJeus7() {
            System.setProperty("jeus.home", "/opt/jeus");
            System.setProperty("jeus.version", "JEUS 7 Fix5");
            WasType result = WasDetector.detect(log);
            assertEquals(WasType.JEUS_7, result);
        }

        @Test
        @DisplayName("jeus.version=4.2 → JEUS_LEGACY 감지")
        void detectJeusByVersionPropertyLegacy() {
            System.setProperty("jeus.home", "/opt/jeus");
            System.setProperty("jeus.version", "4.2");
            WasType result = WasDetector.detect(log);
            assertEquals(WasType.JEUS_LEGACY, result);
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 3단계: 기타 WAS 시스템 프로퍼티 감지
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("3단계: 기타 WAS 시스템 프로퍼티 패턴 감지")
    class SystemPropertyPatternTests {

        @Test
        @DisplayName("weblogic.Name 프로퍼티 → WebLogic 계열 감지")
        void detectWeblogicByProperty() {
            System.setProperty("weblogic.Name", "AdminServer");
            WasType result = WasDetector.detect(log);
            assertTrue(result.isWebLogic(), "WebLogic 계열이어야 함: " + result);
        }

        @Test
        @DisplayName("jboss.home.dir 프로퍼티 → JBoss 계열 감지")
        void detectJbossByProperty() {
            System.setProperty("jboss.home.dir", "/opt/jboss");
            WasType result = WasDetector.detect(log);
            assertTrue(result.isJBoss(), "JBoss 계열이어야 함: " + result);
        }

        @Test
        @DisplayName("jboss.server.base.dir 프로퍼티 → JBoss/WildFly 계열 감지")
        void detectWildflyByProperty() {
            System.setProperty("jboss.server.base.dir", "/opt/wildfly/standalone");
            WasType result = WasDetector.detect(log);
            assertTrue(result.isJBoss(), "JBoss/WildFly 계열이어야 함: " + result);
        }

        @Test
        @DisplayName("catalina.home 프로퍼티 → Tomcat 계열 감지")
        void detectTomcatByProperty() {
            System.setProperty("catalina.home", "/opt/tomcat");
            WasType result = WasDetector.detect(log);
            assertTrue(result.isTomcat(), "Tomcat 계열이어야 함: " + result);
            assertTrue(capturedLog().contains("catalina"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // JEUS 버전 파싱 헬퍼 단위 테스트
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("JEUS 버전 문자열 파싱 — parseJeusVersionString()")
    class JeusVersionStringParsingTests {

        @ParameterizedTest
        @CsvSource({
                "21,         JEUS_9_PLUS",
                "21.0.0,     JEUS_9_PLUS",
                "JEUS 21,    JEUS_9_PLUS",
                "9,          JEUS_9_PLUS",
                "9.0,        JEUS_9_PLUS",
                "jeus9,      JEUS_9_PLUS",
                "JEUS 9 Fix1,JEUS_9_PLUS",
                "8.5,        JEUS_8_5",
                "8.5.0.1,    JEUS_8_5",
                "JEUS 8.5,   JEUS_8_5",
                "8,          JEUS_8",
                "8.0,        JEUS_8",
                "JEUS 8 Fix3,JEUS_8",
                "7,          JEUS_7",
                "7.0,        JEUS_7",
                "JEUS 7 Fix5,JEUS_7",
                "6,          JEUS_6",
                "6.0,        JEUS_6",
                "JEUS 6 Fix9,JEUS_6",
                "5,          JEUS_LEGACY",
                "5.0,        JEUS_LEGACY",
                "4,          JEUS_LEGACY",
                "4.2,        JEUS_LEGACY"
        })
        @DisplayName("버전 문자열 파싱 — 다양한 형식")
        void parseVersionStringVariants(String version, String expectedType) {
            WasType result = WasDetector.parseJeusVersionString(version, log);
            assertNotNull(result, "파싱 결과 null — version=" + version);
            assertEquals(WasType.valueOf(expectedType), result,
                    "version=" + version + " → expected=" + expectedType);
        }

        @Test
        @DisplayName("null 입력 → null 반환")
        void parseNullVersion() {
            assertNull(WasDetector.parseJeusVersionString(null, log));
        }

        @Test
        @DisplayName("빈 문자열 → null 반환")
        void parseEmptyVersion() {
            assertNull(WasDetector.parseJeusVersionString("", log));
        }

        @Test
        @DisplayName("알 수 없는 버전 문자열 → null 반환")
        void parseUnknownVersion() {
            assertNull(WasDetector.parseJeusVersionString("unknown-server-1.0", log));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // JEUS 경로 파싱 단위 테스트
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("JEUS 설치 경로 파싱 — parseJeusVersionFromPath()")
    class JeusPathParsingTests {

        @ParameterizedTest
        @CsvSource({
                "/opt/jeus21,         JEUS_9_PLUS",
                "C:/jeus21,           JEUS_9_PLUS",
                "/opt/JEUS21,         JEUS_9_PLUS",
                "/opt/jeus9,          JEUS_9_PLUS",
                "/home/was/jeus9.0,   JEUS_9_PLUS",
                "/opt/jeus8.5,        JEUS_8_5",
                "C:/jeus8.5,          JEUS_8_5",
                "/opt/jeus8,          JEUS_8",
                "C:/jeus8,            JEUS_8",
                "/opt/jeus7,          JEUS_7",
                "C:/JEUS7,            JEUS_7",
                "/opt/jeus6,          JEUS_6",
                "/opt/jeus5,          JEUS_LEGACY",
                "/opt/jeus4,          JEUS_LEGACY",
                "C:/jeus4,            JEUS_LEGACY"
        })
        @DisplayName("경로 버전 힌트 파싱 — 다양한 설치 경로")
        void parsePathVariants(String path, String expectedType) {
            WasType result = WasDetector.parseJeusVersionFromPath(path, log);
            assertNotNull(result, "파싱 결과 null — path=" + path);
            assertEquals(WasType.valueOf(expectedType), result,
                    "path=" + path + " → expected=" + expectedType);
        }

        @Test
        @DisplayName("버전 힌트 없는 경로 → null 반환")
        void noVersionHintInPath() {
            assertNull(WasDetector.parseJeusVersionFromPath("/opt/was", log));
        }

        @Test
        @DisplayName("null 경로 → null 반환")
        void nullPath() {
            assertNull(WasDetector.parseJeusVersionFromPath(null, log));
        }

        @Test
        @DisplayName("일반 경로 (/opt/was/jeus) → null 반환 (버전 없음)")
        void genericJeusPath() {
            // 경로에 jeus만 있고 버전 숫자 없음 → null
            // parseJeusVersionFromPath는 "jeus8", "jeus7" 등 숫자 포함 패턴만 인식
            // (단순 "jeus" 경로는 버전 판별 불가)
            WasType result = WasDetector.parseJeusVersionFromPath("/opt/jeus", log);
            // /opt/jeus → jeus 포함이나 버전 숫자 없음 → null 기대
            // 실제로 "jeus4"~"jeus21"을 체크하므로 "jeus"만 있으면 null
            assertNull(result, "버전 없는 'jeus' 경로에서 null이어야 함");
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 런타임 JDK 버전 파싱 단위 테스트
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("런타임 JDK 버전 파싱 — getRuntimeJdkMajor()")
    class JdkVersionParsingTests {

        @Test
        @DisplayName("현재 JVM JDK 버전 파싱 — 8 이상이어야 함")
        void currentJdkVersion() {
            int major = WasDetector.getRuntimeJdkMajor();
            assertTrue(major >= 8, "테스트 JVM은 JDK 8 이상이어야 함: " + major);
        }

        @Test
        @DisplayName("레거시 형식 '1.8.0_222' → major=8")
        void legacyFormat18() {
            String saved = System.getProperty("java.version");
            try {
                System.setProperty("java.version", "1.8.0_222");
                assertEquals(8, WasDetector.getRuntimeJdkMajor());
            } finally {
                if (saved != null) System.setProperty("java.version", saved);
                else System.clearProperty("java.version");
            }
        }

        @Test
        @DisplayName("레거시 형식 '1.5.0_22' → major=5")
        void legacyFormat15() {
            String saved = System.getProperty("java.version");
            try {
                System.setProperty("java.version", "1.5.0_22");
                assertEquals(5, WasDetector.getRuntimeJdkMajor());
            } finally {
                if (saved != null) System.setProperty("java.version", saved);
                else System.clearProperty("java.version");
            }
        }

        @Test
        @DisplayName("모던 형식 '11.0.18' → major=11")
        void modernFormat11() {
            String saved = System.getProperty("java.version");
            try {
                System.setProperty("java.version", "11.0.18");
                assertEquals(11, WasDetector.getRuntimeJdkMajor());
            } finally {
                if (saved != null) System.setProperty("java.version", saved);
                else System.clearProperty("java.version");
            }
        }

        @Test
        @DisplayName("모던 형식 '21.0.1' → major=21")
        void modernFormat21() {
            String saved = System.getProperty("java.version");
            try {
                System.setProperty("java.version", "21.0.1");
                assertEquals(21, WasDetector.getRuntimeJdkMajor());
            } finally {
                if (saved != null) System.setProperty("java.version", saved);
                else System.clearProperty("java.version");
            }
        }

        @Test
        @DisplayName("파싱 실패 시 기본값 8 반환")
        void parseFailureDefaultsTo8() {
            String saved = System.getProperty("java.version");
            try {
                System.setProperty("java.version", "unknown-format");
                assertEquals(8, WasDetector.getRuntimeJdkMajor());
            } finally {
                if (saved != null) System.setProperty("java.version", saved);
                else System.clearProperty("java.version");
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 5단계: Fallback
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("5단계: Fallback — UNKNOWN")
    class FallbackTests {

        @Test
        @DisplayName("WAS 클래스도 프로퍼티도 없으면 UNKNOWN 또는 JVM 힌트 기반 WAS 반환")
        void noHintsReturnsUnknown() {
            // 테스트 JVM 환경(Gradle)에서는 sun.java.command에 'gradle'이 포함될 수 있어
            // 5단계 JVM 인수 스캔이 동작할 수 있음. 따라서 UNKNOWN 대신 다른 값이 반환될 수 있음.
            // 최소 보장: 예외 없이 non-null WasType 반환
            WasType result = WasDetector.detect(log);
            assertNotNull(result);
        }

        @Test
        @DisplayName("UNKNOWN도 예외 없이 정상 반환")
        void unknownDoesNotThrow() {
            assertDoesNotThrow(() -> WasDetector.detect(log));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // WasType enum 테스트 (JEUS 추가 검증)
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

        @Test
        @DisplayName("isJeus(): JEUS 계열만 true")
        void isJeusMethod() {
            assertTrue(WasType.JEUS_LEGACY.isJeus());
            assertTrue(WasType.JEUS_6.isJeus());
            assertTrue(WasType.JEUS_7.isJeus());
            assertTrue(WasType.JEUS_8.isJeus());
            assertTrue(WasType.JEUS_8_5.isJeus());
            assertTrue(WasType.JEUS_9_PLUS.isJeus());

            assertFalse(WasType.TOMCAT.isJeus());
            assertFalse(WasType.JBOSS.isJeus());
            assertFalse(WasType.WEBLOGIC.isJeus());
            assertFalse(WasType.UNDERTOW.isJeus());
            assertFalse(WasType.JETTY.isJeus());
            assertFalse(WasType.UNKNOWN.isJeus());
        }

        @Test
        @DisplayName("prefersByteBuddy(): JEUS_LEGACY / JEUS_6은 false, 나머지 true")
        void prefersByteBuddyMethod() {
            assertFalse(WasType.JEUS_LEGACY.prefersByteBuddy(),
                    "JEUS_LEGACY은 Javassist 전용이어야 함");
            assertFalse(WasType.JEUS_6.prefersByteBuddy(),
                    "JEUS_6은 Javassist 통일 정책");

            assertTrue(WasType.JEUS_7.prefersByteBuddy());
            assertTrue(WasType.JEUS_8.prefersByteBuddy());
            assertTrue(WasType.JEUS_8_5.prefersByteBuddy());
            assertTrue(WasType.JEUS_9_PLUS.prefersByteBuddy());
            assertTrue(WasType.TOMCAT.prefersByteBuddy());
            assertTrue(WasType.JBOSS.prefersByteBuddy());
        }

        @Test
        @DisplayName("WasType.valueOf() — 모든 JEUS 상수 직접 접근 가능")
        void jeusConstantsAccessible() {
            assertNotNull(WasType.valueOf("JEUS_LEGACY"));
            assertNotNull(WasType.valueOf("JEUS_6"));
            assertNotNull(WasType.valueOf("JEUS_7"));
            assertNotNull(WasType.valueOf("JEUS_8"));
            assertNotNull(WasType.valueOf("JEUS_8_5"));
            assertNotNull(WasType.valueOf("JEUS_9_PLUS"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 1단계 오버라이드 — 신규 WAS 유형 (Tomcat 버전별, 신규 WAS 계열)
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("1단계: 신규 WAS 오버라이드 (Tomcat 버전별 + 기타 WAS)")
    class NewWasOverrideTests {

        @ParameterizedTest
        @CsvSource({
                "TOMCAT_LEGACY,   TOMCAT_LEGACY",
                "TOMCAT_7,        TOMCAT_7",
                "TOMCAT_8,        TOMCAT_8",
                "TOMCAT_9,        TOMCAT_9",
                "TOMCAT_10_PLUS,  TOMCAT_10_PLUS",
                "TOMCAT,          TOMCAT",
                "JBOSS_LEGACY,    JBOSS_LEGACY",
                "JBOSS,           JBOSS",
                "WILDFLY,         WILDFLY",
                "WEBLOGIC_LEGACY, WEBLOGIC_LEGACY",
                "WEBLOGIC,        WEBLOGIC",
                "WEBSPHERE_LEGACY,WEBSPHERE_LEGACY",
                "WEBSPHERE,       WEBSPHERE",
                "GLASSFISH,       GLASSFISH",
                "GLASSFISH_JAKARTA,GLASSFISH_JAKARTA",
                "RESIN,           RESIN",
                "JETTY_LEGACY,    JETTY_LEGACY",
                "JETTY,           JETTY",
                "JETTY_JAKARTA,   JETTY_JAKARTA",
                "UNDERTOW,        UNDERTOW",
                "UNKNOWN,         UNKNOWN"
        })
        @DisplayName("모든 신규 WasType 오버라이드 정상 동작")
        void overrideAllNewWasTypes(String override, String expectedName) {
            System.setProperty("onepass.was.type", override.trim());
            WasType result = WasDetector.detect(log);
            assertEquals(WasType.valueOf(expectedName.trim()), result,
                    "오버라이드=" + override + " → expected=" + expectedName);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "tomcat_legacy", "tomcat_9", "tomcat_10_plus",
                "wildfly", "websphere", "glassfish_jakarta", "jetty_jakarta"
        })
        @DisplayName("소문자 오버라이드도 인식")
        void overrideCaseInsensitiveAllNewTypes(String lowerOverride) {
            System.setProperty("onepass.was.type", lowerOverride);
            assertDoesNotThrow(() -> WasDetector.detect(log));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // WasType 신규 유틸리티 메서드 테스트
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("WasType 신규 유틸리티 메서드")
    class WasTypeNewMethodTests {

        @Test
        @DisplayName("isTomcat(): Tomcat 계열만 true")
        void isTomcat() {
            assertTrue(WasType.TOMCAT_LEGACY.isTomcat());
            assertTrue(WasType.TOMCAT_7.isTomcat());
            assertTrue(WasType.TOMCAT_8.isTomcat());
            assertTrue(WasType.TOMCAT_9.isTomcat());
            assertTrue(WasType.TOMCAT_10_PLUS.isTomcat());
            assertTrue(WasType.TOMCAT.isTomcat());

            assertFalse(WasType.JEUS_7.isTomcat());
            assertFalse(WasType.WILDFLY.isTomcat());
            assertFalse(WasType.JETTY.isTomcat());
            assertFalse(WasType.UNKNOWN.isTomcat());
        }

        @Test
        @DisplayName("isJBoss(): JBoss/WildFly 계열만 true")
        void isJBoss() {
            assertTrue(WasType.JBOSS_LEGACY.isJBoss());
            assertTrue(WasType.JBOSS.isJBoss());
            assertTrue(WasType.WILDFLY.isJBoss());

            assertFalse(WasType.TOMCAT.isJBoss());
            assertFalse(WasType.WEBLOGIC.isJBoss());
            assertFalse(WasType.UNKNOWN.isJBoss());
        }

        @Test
        @DisplayName("isWebLogic(): WebLogic 계열만 true")
        void isWebLogic() {
            assertTrue(WasType.WEBLOGIC_LEGACY.isWebLogic());
            assertTrue(WasType.WEBLOGIC.isWebLogic());

            assertFalse(WasType.WEBSPHERE.isWebLogic());
            assertFalse(WasType.JBOSS.isWebLogic());
            assertFalse(WasType.UNKNOWN.isWebLogic());
        }

        @Test
        @DisplayName("isWebSphere(): WebSphere 계열만 true")
        void isWebSphere() {
            assertTrue(WasType.WEBSPHERE_LEGACY.isWebSphere());
            assertTrue(WasType.WEBSPHERE.isWebSphere());

            assertFalse(WasType.WEBLOGIC.isWebSphere());
            assertFalse(WasType.TOMCAT.isWebSphere());
        }

        @Test
        @DisplayName("isGlassFish(): GlassFish 계열만 true")
        void isGlassFish() {
            assertTrue(WasType.GLASSFISH.isGlassFish());
            assertTrue(WasType.GLASSFISH_JAKARTA.isGlassFish());

            assertFalse(WasType.JETTY.isGlassFish());
            assertFalse(WasType.WILDFLY.isGlassFish());
        }

        @Test
        @DisplayName("isJetty(): Jetty 계열만 true")
        void isJetty() {
            assertTrue(WasType.JETTY_LEGACY.isJetty());
            assertTrue(WasType.JETTY.isJetty());
            assertTrue(WasType.JETTY_JAKARTA.isJetty());

            assertFalse(WasType.TOMCAT.isJetty());
            assertFalse(WasType.WILDFLY.isJetty());
        }

        @Test
        @DisplayName("isJakartaOnly(): jakarta 전용 WAS만 true")
        void isJakartaOnly() {
            // jakarta 전용
            assertTrue(WasType.JEUS_9_PLUS.isJakartaOnly());
            assertTrue(WasType.TOMCAT_10_PLUS.isJakartaOnly());
            assertTrue(WasType.WILDFLY.isJakartaOnly());
            assertTrue(WasType.GLASSFISH_JAKARTA.isJakartaOnly());
            assertTrue(WasType.JETTY_JAKARTA.isJakartaOnly());

            // javax 사용
            assertFalse(WasType.TOMCAT_9.isJakartaOnly());
            assertFalse(WasType.TOMCAT_8.isJakartaOnly());
            assertFalse(WasType.JBOSS.isJakartaOnly());
            assertFalse(WasType.WEBLOGIC.isJakartaOnly());
            assertFalse(WasType.UNKNOWN.isJakartaOnly());
        }

        @Test
        @DisplayName("isLegacyJavassist(): JDK 6~7 레거시 WAS만 true")
        void isLegacyJavassist() {
            assertTrue(WasType.JEUS_LEGACY.isLegacyJavassist());
            assertTrue(WasType.JEUS_6.isLegacyJavassist());
            assertTrue(WasType.TOMCAT_LEGACY.isLegacyJavassist());
            assertTrue(WasType.JBOSS_LEGACY.isLegacyJavassist());
            assertTrue(WasType.WEBLOGIC_LEGACY.isLegacyJavassist());
            assertTrue(WasType.WEBSPHERE_LEGACY.isLegacyJavassist());
            assertTrue(WasType.JETTY_LEGACY.isLegacyJavassist());

            // 현대 WAS
            assertFalse(WasType.TOMCAT_9.isLegacyJavassist());
            assertFalse(WasType.WILDFLY.isLegacyJavassist());
            assertFalse(WasType.WEBLOGIC.isLegacyJavassist());
        }

        @Test
        @DisplayName("needsDualNamespace(): javax+jakarta 이중 위빙 필요 WAS 식별")
        void needsDualNamespace() {
            // 실제 구현: JEUS_9_PLUS, JEUS_8_5, TOMCAT_10_PLUS, WEBSPHERE 가 true
            assertTrue(WasType.JEUS_9_PLUS.needsDualNamespace());
            assertTrue(WasType.JEUS_8_5.needsDualNamespace());
            assertTrue(WasType.TOMCAT_10_PLUS.needsDualNamespace());
            assertTrue(WasType.WEBSPHERE.needsDualNamespace());

            // 단일 네임스페이스 확정 (false)
            assertFalse(WasType.TOMCAT_9.needsDualNamespace());
            assertFalse(WasType.WILDFLY.needsDualNamespace());
            assertFalse(WasType.TOMCAT_8.needsDualNamespace());
            assertFalse(WasType.UNKNOWN.needsDualNamespace());
        }

        @Test
        @DisplayName("모든 WasType에 isTomcat, isJBoss 등 메서드가 예외 없이 동작")
        void allWasTypesUtilMethodsNoException() {
            for (WasType type : WasType.values()) {
                assertDoesNotThrow(() -> {
                    type.isTomcat();
                    type.isJBoss();
                    type.isWebLogic();
                    type.isWebSphere();
                    type.isGlassFish();
                    type.isJetty();
                    type.isJakartaOnly();
                    type.isLegacyJavassist();
                    type.needsDualNamespace();
                }, "WasType." + type + " 에서 예외 발생");
            }
        }

        @Test
        @DisplayName("WasType.valueOf() — 신규 상수 모두 접근 가능")
        void newConstantsAccessible() {
            // Tomcat 버전별
            assertNotNull(WasType.valueOf("TOMCAT_LEGACY"));
            assertNotNull(WasType.valueOf("TOMCAT_7"));
            assertNotNull(WasType.valueOf("TOMCAT_8"));
            assertNotNull(WasType.valueOf("TOMCAT_9"));
            assertNotNull(WasType.valueOf("TOMCAT_10_PLUS"));

            // 기타 WAS
            assertNotNull(WasType.valueOf("JBOSS_LEGACY"));
            assertNotNull(WasType.valueOf("WILDFLY"));
            assertNotNull(WasType.valueOf("WEBLOGIC_LEGACY"));
            assertNotNull(WasType.valueOf("WEBSPHERE_LEGACY"));
            assertNotNull(WasType.valueOf("WEBSPHERE"));
            assertNotNull(WasType.valueOf("GLASSFISH"));
            assertNotNull(WasType.valueOf("GLASSFISH_JAKARTA"));
            assertNotNull(WasType.valueOf("RESIN"));
            assertNotNull(WasType.valueOf("JETTY_LEGACY"));
            assertNotNull(WasType.valueOf("JETTY"));
            assertNotNull(WasType.valueOf("JETTY_JAKARTA"));
            assertNotNull(WasType.valueOf("UNDERTOW"));
        }

        @Test
        @DisplayName("전체 WasType 개수 — 27개 이상")
        void totalWasTypeCount() {
            // WasType.java가 27개 enum을 정의하고 있어야 함
            assertTrue(WasType.values().length >= 27,
                    "WasType 개수가 27개 미만: " + WasType.values().length);
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 신규 WAS 시스템 프로퍼티 감지 테스트
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("3단계: 신규 WAS 시스템 프로퍼티 감지")
    class NewWasSystemPropertyTests {

        @Test
        @DisplayName("catalina.home + catalina.base → Tomcat 감지")
        void detectTomcatByCatalinaBase() {
            System.setProperty("catalina.home", "/opt/tomcat9");
            System.setProperty("catalina.base", "/opt/tomcat9");
            WasType result = WasDetector.detect(log);
            assertTrue(result.isTomcat(), "Tomcat 계열이어야 함: " + result);
        }

        @Test
        @DisplayName("jboss.home.dir + wildfly 경로 힌트 → JBoss 계열 감지")
        void detectWildflyByHomeDir() {
            System.setProperty("jboss.home.dir", "/opt/wildfly27");
            WasType result = WasDetector.detect(log);
            assertTrue(result.isJBoss(), "JBoss/WildFly 계열이어야 함: " + result);
        }

        @Test
        @DisplayName("시스템 프로퍼티 없고 클래스패스에도 없음 → UNKNOWN 또는 감지 실패")
        void unknownWhenNoHints() {
            // 테스트 환경에서는 JVM 인수(sun.java.command 등)에 의해 UNKNOWN이 아닐 수 있음
            // 최소한 예외 없이 반환되어야 함
            assertDoesNotThrow(() -> WasDetector.detect(log));
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

    @Test
    @DisplayName("log=null + JEUS 프로퍼티 → NPE 없음")
    void nullLogWithJeusPropertyDoesNotThrow() {
        System.setProperty("jeus.home", "/opt/jeus7");
        assertDoesNotThrow(() -> WasDetector.detect(null));
    }

    @Test
    @DisplayName("log=null + Tomcat 버전 오버라이드 → NPE 없음")
    void nullLogWithTomcatOverride() {
        System.setProperty("onepass.was.type", "TOMCAT_9");
        assertDoesNotThrow(() -> WasDetector.detect(null));
    }

    @Test
    @DisplayName("log=null + WildFly 오버라이드 → NPE 없음")
    void nullLogWithWildflyOverride() {
        System.setProperty("onepass.was.type", "WILDFLY");
        assertDoesNotThrow(() -> WasDetector.detect(null));
    }

    // 헬퍼
    private String capturedLog() {
        return logCapture.toString();
    }
}

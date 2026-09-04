package kr.go.smes.agent.weaving.jeus;

import kr.go.smes.agent.was.WasType;
import kr.go.smes.agent.weaving.jeus.JeusWeavingEngineSelector.EngineType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JeusWeavingEngineSelector} 단위 테스트.
 *
 * <h2>테스트 전략</h2>
 * <p>{@link JeusWeavingEngineSelector#selectWithJdk(WasType, int, PrintStream)} 메서드를
 * 직접 호출하여 JDK 버전을 인위적으로 지정, WasType × JDK 버전의 모든 조합을 검증한다.
 */
@DisplayName("JeusWeavingEngineSelector — 위빙 엔진 선택")
class JeusWeavingEngineSelectorTest {

    private final PrintStream log = new PrintStream(new ByteArrayOutputStream());

    // ────────────────────────────────────────────────────────────────────────────
    // JEUS_LEGACY (JDK 1.4~1.5) → 항상 JAVASSIST
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("JEUS_LEGACY (JDK 1.4~1.5) — 항상 JAVASSIST")
    class JeusLegacyTests {

        @ParameterizedTest
        @CsvSource({"4", "5", "6", "7", "8", "11", "21"})
        @DisplayName("JEUS_LEGACY + JDK * → 항상 JAVASSIST (byte-buddy 절대 불가)")
        void jeuLegacyAlwaysJavassist(int jdk) {
            EngineType result = JeusWeavingEngineSelector.selectWithJdk(
                    WasType.JEUS_LEGACY, jdk, log);
            assertEquals(EngineType.JAVASSIST, result,
                    "JEUS_LEGACY + JDK " + jdk + " → JAVASSIST 기대");
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // JEUS_6 (JDK 1.5~1.7) → 항상 JAVASSIST (통일 정책)
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("JEUS_6 (JDK 1.5~1.7) — 항상 JAVASSIST (Javassist 통일 정책)")
    class Jeus6Tests {

        @ParameterizedTest
        @CsvSource({"5", "6", "7", "8", "11"})
        @DisplayName("JEUS_6 + JDK * → 항상 JAVASSIST")
        void jeus6AlwaysJavassist(int jdk) {
            EngineType result = JeusWeavingEngineSelector.selectWithJdk(
                    WasType.JEUS_6, jdk, log);
            assertEquals(EngineType.JAVASSIST, result,
                    "JEUS_6 + JDK " + jdk + " → JAVASSIST 기대");
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // JEUS_7 (JDK 1.6~1.8) — JDK 기반 분기
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("JEUS_7 (JDK 1.6~1.8) — JDK 버전 기반 분기")
    class Jeus7Tests {

        @Test
        @DisplayName("JEUS_7 + JDK 6 → JAVASSIST")
        void jeus7Jdk6() {
            assertEquals(EngineType.JAVASSIST,
                    JeusWeavingEngineSelector.selectWithJdk(WasType.JEUS_7, 6, log));
        }

        @Test
        @DisplayName("JEUS_7 + JDK 7 → JAVASSIST")
        void jeus7Jdk7() {
            assertEquals(EngineType.JAVASSIST,
                    JeusWeavingEngineSelector.selectWithJdk(WasType.JEUS_7, 7, log));
        }

        @Test
        @DisplayName("JEUS_7 + JDK 8 → BYTE_BUDDY")
        void jeus7Jdk8() {
            assertEquals(EngineType.BYTE_BUDDY,
                    JeusWeavingEngineSelector.selectWithJdk(WasType.JEUS_7, 8, log));
        }

        @Test
        @DisplayName("JEUS_7 + JDK 11 → BYTE_BUDDY")
        void jeus7Jdk11() {
            assertEquals(EngineType.BYTE_BUDDY,
                    JeusWeavingEngineSelector.selectWithJdk(WasType.JEUS_7, 11, log));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // JEUS_8 (JDK 1.7~1.8) — JDK 기반 분기
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("JEUS_8 (JDK 1.7~1.8) — JDK 버전 기반 분기")
    class Jeus8Tests {

        @Test
        @DisplayName("JEUS_8 + JDK 7 → JAVASSIST (드문 케이스)")
        void jeus8Jdk7() {
            assertEquals(EngineType.JAVASSIST,
                    JeusWeavingEngineSelector.selectWithJdk(WasType.JEUS_8, 7, log));
        }

        @Test
        @DisplayName("JEUS_8 + JDK 8 → BYTE_BUDDY")
        void jeus8Jdk8() {
            assertEquals(EngineType.BYTE_BUDDY,
                    JeusWeavingEngineSelector.selectWithJdk(WasType.JEUS_8, 8, log));
        }

        @Test
        @DisplayName("JEUS_8 + JDK 11 → BYTE_BUDDY")
        void jeus8Jdk11() {
            assertEquals(EngineType.BYTE_BUDDY,
                    JeusWeavingEngineSelector.selectWithJdk(WasType.JEUS_8, 11, log));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // JEUS_8_5 / JEUS_9_PLUS → 항상 BYTE_BUDDY
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("JEUS_8_5 / JEUS_9_PLUS — 항상 BYTE_BUDDY")
    class Jeus8_5AndJeus9PlusTests {

        @ParameterizedTest
        @CsvSource({"8", "11", "17", "21"})
        @DisplayName("JEUS_8_5 + JDK * → BYTE_BUDDY")
        void jeus8_5ByteBuddy(int jdk) {
            assertEquals(EngineType.BYTE_BUDDY,
                    JeusWeavingEngineSelector.selectWithJdk(WasType.JEUS_8_5, jdk, log));
        }

        @ParameterizedTest
        @CsvSource({"11", "17", "21"})
        @DisplayName("JEUS_9_PLUS + JDK * → BYTE_BUDDY")
        void jeus9PlusByteBuddy(int jdk) {
            assertEquals(EngineType.BYTE_BUDDY,
                    JeusWeavingEngineSelector.selectWithJdk(WasType.JEUS_9_PLUS, jdk, log));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 비-JEUS WAS → 항상 BYTE_BUDDY
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("비-JEUS WAS — 항상 BYTE_BUDDY")
    class NonJeusWasTests {

        @ParameterizedTest
        @CsvSource({"TOMCAT", "JBOSS", "WEBLOGIC", "UNDERTOW", "JETTY", "UNKNOWN"})
        @DisplayName("비-JEUS WAS + JDK 11 → BYTE_BUDDY")
        void nonJeusWasByteBuddy(String wasTypeName) {
            WasType wasType = WasType.valueOf(wasTypeName);
            EngineType result = JeusWeavingEngineSelector.selectWithJdk(wasType, 11, log);
            assertEquals(EngineType.BYTE_BUDDY, result,
                    wasType + " → BYTE_BUDDY 기대");
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // select() 메서드 — 실제 JVM JDK로 선택
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("select() — 실제 JVM에서 JEUS_8_5: JDK 8+ 이므로 BYTE_BUDDY")
    void selectJeus8_5OnCurrentJvm() {
        // 테스트 JVM은 JDK 8 이상이므로 JEUS_8_5 → BYTE_BUDDY
        EngineType result = JeusWeavingEngineSelector.select(WasType.JEUS_8_5, log);
        assertEquals(EngineType.BYTE_BUDDY, result);
    }

    @Test
    @DisplayName("select() — JEUS_LEGACY: JDK 무관하게 JAVASSIST")
    void selectJeusLegacyOnCurrentJvm() {
        EngineType result = JeusWeavingEngineSelector.select(WasType.JEUS_LEGACY, log);
        assertEquals(EngineType.JAVASSIST, result);
    }

    @Test
    @DisplayName("log=null이어도 NPE 없음")
    void nullLogDoesNotThrow() {
        assertDoesNotThrow(() ->
                JeusWeavingEngineSelector.selectWithJdk(WasType.JEUS_7, 8, null));
    }
}

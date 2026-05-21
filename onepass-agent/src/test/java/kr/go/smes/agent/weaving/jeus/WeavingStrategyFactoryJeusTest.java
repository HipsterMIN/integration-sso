package kr.go.smes.agent.weaving.jeus;

import kr.go.smes.agent.config.AgentConfig;
import kr.go.smes.agent.was.WasType;
import kr.go.smes.agent.weaving.GenericFilterWeavingStrategy;
import kr.go.smes.agent.weaving.TomcatWeavingStrategy;
import kr.go.smes.agent.weaving.WeavingStrategy;
import kr.go.smes.agent.weaving.WeavingStrategyFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WeavingStrategyFactory} JEUS 라우팅 검증 테스트.
 *
 * <h2>검증 목적</h2>
 * <ul>
 *   <li>JEUS 버전별 WasType → 올바른 전략 클래스 매핑 확인</li>
 *   <li>비-JEUS WAS → 기존 전략 유지 확인 (회귀)</li>
 *   <li>strategy.name() 비어있지 않음 확인</li>
 *   <li>Factory 로그 출력 확인</li>
 * </ul>
 */
@DisplayName("WeavingStrategyFactory — JEUS 라우팅 검증")
class WeavingStrategyFactoryJeusTest {

    private AgentConfig config;
    private final PrintStream log = new PrintStream(new ByteArrayOutputStream());
    private final ByteArrayOutputStream logCapture = new ByteArrayOutputStream();
    private final PrintStream captureLog = new PrintStream(logCapture);
    private File tempConfig;

    @BeforeEach
    void setUp() throws Exception {
        // 임시 설정 파일 생성
        tempConfig = File.createTempFile("onepass-agent-test", ".properties");
        tempConfig.deleteOnExit();
        try (FileWriter w = new FileWriter(tempConfig)) {
            w.write("onepass.agent.endpoint=http://localhost:8080\n");
            w.write("onepass.agent.api-key=test-key-for-factory-test\n");
        }
        config = AgentConfig.load("config=" + tempConfig.getAbsolutePath(), log);
    }

    @AfterEach
    void tearDown() {
        if (tempConfig != null) tempConfig.delete();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // JEUS 버전별 전략 매핑
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("JEUS_LEGACY → JeusLegacyWeavingStrategy")
    void jeusLegacyMapsToLegacyStrategy() {
        WeavingStrategy strategy = WeavingStrategyFactory.create(
                WasType.JEUS_LEGACY, config, log);
        assertInstanceOf(JeusLegacyWeavingStrategy.class, strategy,
                "JEUS_LEGACY → JeusLegacyWeavingStrategy 기대");
    }

    @Test
    @DisplayName("JEUS_6 → Jeus6WeavingStrategy")
    void jeus6MapsToJeus6Strategy() {
        WeavingStrategy strategy = WeavingStrategyFactory.create(
                WasType.JEUS_6, config, log);
        assertInstanceOf(Jeus6WeavingStrategy.class, strategy,
                "JEUS_6 → Jeus6WeavingStrategy 기대");
    }

    @Test
    @DisplayName("JEUS_7 → Jeus7PlusWeavingStrategy")
    void jeus7MapsToJeus7PlusStrategy() {
        WeavingStrategy strategy = WeavingStrategyFactory.create(
                WasType.JEUS_7, config, log);
        assertInstanceOf(Jeus7PlusWeavingStrategy.class, strategy,
                "JEUS_7 → Jeus7PlusWeavingStrategy 기대");
    }

    @Test
    @DisplayName("JEUS_8 → Jeus7PlusWeavingStrategy (JEUS 7/8 공유)")
    void jeus8MapsToJeus7PlusStrategy() {
        WeavingStrategy strategy = WeavingStrategyFactory.create(
                WasType.JEUS_8, config, log);
        assertInstanceOf(Jeus7PlusWeavingStrategy.class, strategy,
                "JEUS_8 → Jeus7PlusWeavingStrategy 기대");
    }

    @Test
    @DisplayName("JEUS_8_5 → Jeus8_5PlusWeavingStrategy")
    void jeus8_5MapsToJeus8_5Strategy() {
        WeavingStrategy strategy = WeavingStrategyFactory.create(
                WasType.JEUS_8_5, config, log);
        assertInstanceOf(Jeus8_5PlusWeavingStrategy.class, strategy,
                "JEUS_8_5 → Jeus8_5PlusWeavingStrategy 기대");
    }

    @Test
    @DisplayName("JEUS_9_PLUS → Jeus8_5PlusWeavingStrategy (JEUS 8.5/9/21 공유)")
    void jeus9PlusMapsToJeus8_5Strategy() {
        WeavingStrategy strategy = WeavingStrategyFactory.create(
                WasType.JEUS_9_PLUS, config, log);
        assertInstanceOf(Jeus8_5PlusWeavingStrategy.class, strategy,
                "JEUS_9_PLUS → Jeus8_5PlusWeavingStrategy 기대");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 기존 WAS 전략 회귀 테스트
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("TOMCAT → TomcatWeavingStrategy (회귀)")
    void tomcatMapsToTomcatStrategy() {
        WeavingStrategy strategy = WeavingStrategyFactory.create(
                WasType.TOMCAT, config, log);
        assertInstanceOf(TomcatWeavingStrategy.class, strategy);
    }

    @ParameterizedTest
    @EnumSource(value = WasType.class,
            names = {"JBOSS", "WEBLOGIC", "UNDERTOW", "JETTY", "UNKNOWN"})
    @DisplayName("JBOSS/WEBLOGIC/UNDERTOW/JETTY/UNKNOWN → GenericFilterWeavingStrategy (회귀)")
    void genericWasMapsToGenericFilter(WasType wasType) {
        WeavingStrategy strategy = WeavingStrategyFactory.create(wasType, config, log);
        assertInstanceOf(GenericFilterWeavingStrategy.class, strategy,
                wasType + " → GenericFilterWeavingStrategy 기대");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 전략 name() 및 로그 검증
    // ────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(WasType.class)
    @DisplayName("모든 WasType에 대해 strategy.name()이 비어있지 않음")
    void strategyNameNotEmpty(WasType wasType) {
        WeavingStrategy strategy = WeavingStrategyFactory.create(wasType, config, log);
        assertNotNull(strategy.name(), wasType + " strategy.name() null");
        assertFalse(strategy.name().isEmpty(), wasType + " strategy.name() 비어 있음");
    }

    @Test
    @DisplayName("Factory 로그에 WAS 이름과 전략 이름 포함")
    void factoryLogsWasAndStrategyName() {
        WeavingStrategy strategy = WeavingStrategyFactory.create(
                WasType.JEUS_7, config, captureLog);
        String logged = logCapture.toString();
        assertTrue(logged.contains("JEUS 7"), "로그에 WAS 이름 포함 기대: " + logged);
        assertTrue(logged.contains("전략="), "로그에 '전략=' 포함 기대: " + logged);
    }

    @Test
    @DisplayName("JEUS_LEGACY 전략 name()에 'JEUS' 포함")
    void legacyStrategyNameContainsJeus() {
        WeavingStrategy strategy = WeavingStrategyFactory.create(
                WasType.JEUS_LEGACY, config, log);
        assertTrue(strategy.name().contains("Jeus") || strategy.name().contains("JEUS"),
                "JEUS_LEGACY 전략명에 JEUS 포함 기대: " + strategy.name());
    }
}

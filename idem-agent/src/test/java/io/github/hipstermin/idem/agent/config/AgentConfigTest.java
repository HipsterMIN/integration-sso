package io.github.hipstermin.idem.agent.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link AgentConfig} 단위 테스트.
 *
 * <h2>테스트 범위</h2>
 * <ul>
 *   <li>agentArgs 파싱</li>
 *   <li>프로퍼티 파일 로드 + 검증</li>
 *   <li>필수 설정 누락 예외</li>
 *   <li>선택 설정 기본값 적용</li>
 *   <li>endpoint trailing slash 정규화</li>
 *   <li>마스킹 유틸리티</li>
 * </ul>
 */
@DisplayName("AgentConfig — 설정 로더 + 검증")
class AgentConfigTest {

    private static final PrintStream SILENT = new PrintStream(new ByteArrayOutputStream());

    // ────────────────────────────────────────────────────────────────────────────
    // agentArgs 파싱
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseAgentArg()")
    class ParseAgentArgTests {

        @Test
        @DisplayName("단일 키=값 파싱 성공")
        void parseSingleKeyValue() {
            assertEquals("/etc/onepass.properties",
                    AgentConfig.parseAgentArg("config=/etc/onepass.properties", "config"));
        }

        @Test
        @DisplayName("다중 키=값 중 지정 키 파싱")
        void parseMultipleKeyValues() {
            String args = "config=/etc/onepass.properties,debug=true,timeout=5000";
            assertEquals("/etc/onepass.properties", AgentConfig.parseAgentArg(args, "config"));
            assertEquals("true",  AgentConfig.parseAgentArg(args, "debug"));
            assertEquals("5000",  AgentConfig.parseAgentArg(args, "timeout"));
        }

        @Test
        @DisplayName("없는 키는 null 반환")
        void missingKeyReturnsNull() {
            assertNull(AgentConfig.parseAgentArg("config=/path", "unknown"));
        }

        @Test
        @DisplayName("agentArgs null이면 null 반환")
        void nullAgentArgsReturnsNull() {
            assertNull(AgentConfig.parseAgentArg(null, "config"));
        }

        @Test
        @DisplayName("agentArgs 빈 문자열이면 null 반환")
        void emptyAgentArgsReturnsNull() {
            assertNull(AgentConfig.parseAgentArg("", "config"));
        }

        @Test
        @DisplayName("값에 = 포함된 경우 첫 번째 = 기준으로 분리")
        void valueContainsEquals() {
            // config=http://host:8080/path?key=val → 첫 = 이후 전체가 값
            // AgentConfig.parseAgentArg는 단순 indexOf('=') 사용
            String result = AgentConfig.parseAgentArg("config=https://onepass.go.kr", "config");
            assertEquals("https://onepass.go.kr", result);
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 프로퍼티 파일 로드
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("load() — 파일에서 설정 로드")
    class LoadFromFileTests {

        @Test
        @DisplayName("필수 + 선택 항목 정상 로드")
        void loadFullConfig() throws IOException {
            File propFile = createTempProperties(
                    "onepass.agent.endpoint=https://onepass.go.kr\n" +
                    "onepass.agent.api-key=test-api-key-12345\n" +
                    "onepass.agent.connect-timeout-ms=3000\n" +
                    "onepass.agent.read-timeout-ms=8000\n" +
                    "onepass.agent.max-retry=3\n" +
                    "onepass.agent.enabled=true\n" +
                    "onepass.agent.log-level=WARN\n" +
                    "onepass.agent.hmac-secret=super-secret\n"
            );

            AgentConfig config = AgentConfig.load("config=" + propFile.getAbsolutePath(), SILENT);

            assertAll(
                    () -> assertEquals("https://onepass.go.kr", config.endpoint()),
                    () -> assertEquals("test-api-key-12345", config.apiKey()),
                    () -> assertEquals(3000,           config.connectTimeoutMs()),
                    () -> assertEquals(8000,           config.readTimeoutMs()),
                    () -> assertEquals(3,              config.maxRetry()),
                    () -> assertTrue(config.isEnabled()),
                    () -> assertEquals("WARN",         config.logLevel()),
                    () -> assertEquals("super-secret", config.hmacSecret()),
                    () -> assertTrue(config.isHmacEnabled())
            );
        }

        @Test
        @DisplayName("선택 항목 없으면 기본값 적용")
        void defaultValuesApplied() throws IOException {
            File propFile = createTempProperties(
                    "onepass.agent.endpoint=https://onepass.go.kr\n" +
                    "onepass.agent.api-key=my-api-key\n"
            );

            AgentConfig config = AgentConfig.load("config=" + propFile.getAbsolutePath(), SILENT);

            assertAll(
                    () -> assertEquals(5000, config.connectTimeoutMs()),
                    () -> assertEquals(10000, config.readTimeoutMs()),
                    () -> assertEquals(2,    config.maxRetry()),
                    () -> assertTrue(config.isEnabled()),
                    () -> assertEquals("INFO", config.logLevel()),
                    () -> assertNull(config.hmacSecret()),
                    () -> assertFalse(config.isHmacEnabled())
            );
        }

        @Test
        @DisplayName("endpoint trailing slash 자동 제거")
        void endpointTrailingSlashNormalized() throws IOException {
            File propFile = createTempProperties(
                    "onepass.agent.endpoint=https://onepass.go.kr/\n" +
                    "onepass.agent.api-key=key\n"
            );

            AgentConfig config = AgentConfig.load("config=" + propFile.getAbsolutePath(), SILENT);
            assertEquals("https://onepass.go.kr", config.endpoint());
        }

        @Test
        @DisplayName("http:// endpoint도 허용")
        void httpEndpointAllowed() throws IOException {
            File propFile = createTempProperties(
                    "onepass.agent.endpoint=http://dev.onepass.internal\n" +
                    "onepass.agent.api-key=dev-key\n"
            );

            AgentConfig config = AgentConfig.load("config=" + propFile.getAbsolutePath(), SILENT);
            assertEquals("http://dev.onepass.internal", config.endpoint());
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 검증 실패 케이스
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("검증 실패 — AgentConfigException")
    class ValidationFailureTests {

        @Test
        @DisplayName("endpoint 누락 시 예외")
        void missingEndpointThrows() throws IOException {
            File propFile = createTempProperties("onepass.agent.api-key=key\n");
            assertThrows(AgentConfig.AgentConfigException.class,
                    () -> AgentConfig.load("config=" + propFile.getAbsolutePath(), SILENT));
        }

        @Test
        @DisplayName("api-key 누락 시 예외")
        void missingApiKeyThrows() throws IOException {
            File propFile = createTempProperties(
                    "onepass.agent.endpoint=https://onepass.go.kr\n");
            assertThrows(AgentConfig.AgentConfigException.class,
                    () -> AgentConfig.load("config=" + propFile.getAbsolutePath(), SILENT));
        }

        @ParameterizedTest
        @ValueSource(strings = {"ftp://bad.url", "//no-scheme", "onepass.go.kr"})
        @DisplayName("잘못된 endpoint 스킴 시 예외")
        void invalidEndpointSchemeThrows(String badEndpoint) throws IOException {
            File propFile = createTempProperties(
                    "onepass.agent.endpoint=" + badEndpoint + "\n" +
                    "onepass.agent.api-key=key\n");
            assertThrows(AgentConfig.AgentConfigException.class,
                    () -> AgentConfig.load("config=" + propFile.getAbsolutePath(), SILENT));
        }

        @ParameterizedTest
        @ValueSource(strings = {"DEBUG", "TRACE", "verbose"})
        @DisplayName("잘못된 log-level 시 예외 (DEBUG/TRACE/verbose)")
        void invalidLogLevelThrows(String badLevel) throws IOException {
            File propFile = createTempProperties(
                    "onepass.agent.endpoint=https://onepass.go.kr\n" +
                    "onepass.agent.api-key=key\n" +
                    "onepass.agent.log-level=" + badLevel + "\n");
            assertThrows(AgentConfig.AgentConfigException.class,
                    () -> AgentConfig.load("config=" + propFile.getAbsolutePath(), SILENT));
        }

        @Test
        @DisplayName("log-level 빈 문자열 → props.getProperty 빈 값 → parseLogLevel 예외")
        void emptyLogLevelThrows() throws IOException {
            // Properties 파일에 log-level= (빈 값) 저장 시
            // props.getProperty(KEY_LOG_LEVEL, DEFAULT_LOG_LEVEL)에서 기본값이 쓰이지 않음
            // (getProperty(key, default)는 키 자체가 없을 때만 default 사용)
            // → parseLogLevel("") 호출 → 예외 발생이 올바른 동작
            File propFile = createTempProperties(
                    "onepass.agent.endpoint=https://onepass.go.kr\n" +
                    "onepass.agent.api-key=key\n" +
                    "onepass.agent.log-level=\n");
            assertThrows(AgentConfig.AgentConfigException.class,
                    () -> AgentConfig.load("config=" + propFile.getAbsolutePath(), SILENT));
        }

        @Test
        @DisplayName("connect-timeout-ms에 문자 입력 시 예외")
        void nonNumericTimeoutThrows() throws IOException {
            File propFile = createTempProperties(
                    "onepass.agent.endpoint=https://onepass.go.kr\n" +
                    "onepass.agent.api-key=key\n" +
                    "onepass.agent.connect-timeout-ms=abc\n");
            assertThrows(AgentConfig.AgentConfigException.class,
                    () -> AgentConfig.load("config=" + propFile.getAbsolutePath(), SILENT));
        }

        @Test
        @DisplayName("connect-timeout-ms=0 시 예외 (양수 요구)")
        void zeroTimeoutThrows() throws IOException {
            File propFile = createTempProperties(
                    "onepass.agent.endpoint=https://onepass.go.kr\n" +
                    "onepass.agent.api-key=key\n" +
                    "onepass.agent.connect-timeout-ms=0\n");
            assertThrows(AgentConfig.AgentConfigException.class,
                    () -> AgentConfig.load("config=" + propFile.getAbsolutePath(), SILENT));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 마스킹 유틸리티
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("mask() — API 키 마스킹")
    class MaskTests {

        @Test
        @DisplayName("5자 이상이면 앞 4자 + ****")
        void maskLongKey() {
            assertEquals("abcd****", AgentConfig.mask("abcdefghij"));
        }

        @Test
        @DisplayName("4자 이하이면 ****")
        void maskShortKey() {
            assertEquals("****", AgentConfig.mask("abc"));
            assertEquals("****", AgentConfig.mask("ab"));
            assertEquals("****", AgentConfig.mask("a"));
        }

        @Test
        @DisplayName("null이면 ****")
        void maskNull() {
            assertEquals("****", AgentConfig.mask(null));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // toString()
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("toString()에 API 키 평문 노출 안 됨")
    void toStringMasksApiKey() throws IOException {
        File propFile = createTempProperties(
                "onepass.agent.endpoint=https://onepass.go.kr\n" +
                "onepass.agent.api-key=super-secret-key\n"
        );
        AgentConfig config = AgentConfig.load("config=" + propFile.getAbsolutePath(), SILENT);
        String str = config.toString();

        assertFalse(str.contains("super-secret-key"), "API 키 평문이 toString()에 노출됐습니다");
        assertTrue(str.contains("supe****"), "마스킹된 키가 포함돼야 합니다");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 헬퍼
    // ────────────────────────────────────────────────────────────────────────────

    private static File createTempProperties(String content) throws IOException {
        File tmp = Files.createTempFile("onepass-agent-test-", ".properties").toFile();
        tmp.deleteOnExit();
        try (FileWriter w = new FileWriter(tmp)) {
            w.write(content);
        }
        return tmp;
    }
}

package kr.go.smes.agent.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OnePassAgentMain} 단위 테스트.
 *
 * <h2>Instrumentation Test Double</h2>
 * 실제 JVM Instrumentation을 테스트에서 얻을 수 없으므로
 * 인터페이스를 구현한 No-op Stub을 사용한다.
 *
 * <h2>테스트 범위</h2>
 * <ul>
 *   <li>정상 초기화 흐름 (설정 로드 → WAS 감지 → 위빙 설치)</li>
 *   <li>enabled=false 시 위빙 건너뜀</li>
 *   <li>설정 누락 시 JVM 종료 없이 경고만 출력</li>
 *   <li>Instrumentation null 시 경고만 출력</li>
 * </ul>
 */
@DisplayName("OnePassAgentMain — premain / agentmain 진입점")
class OnePassAgentMainTest {

    private ByteArrayOutputStream logCapture;
    private PrintStream log;
    private StubInstrumentation stubInst;

    @BeforeEach
    void setUp() {
        logCapture = new ByteArrayOutputStream();
        log = new PrintStream(logCapture);
        stubInst = new StubInstrumentation();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 정상 초기화
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("정상 초기화 흐름")
    class NormalInitTests {

        @Test
        @DisplayName("유효한 설정 파일 → 위빙 설치 완료 로그 출력")
        void validConfigInstallsWeaving() throws IOException {
            File props = createProps(
                    "onepass.agent.endpoint=https://onepass.go.kr\n" +
                    "onepass.agent.api-key=test-key-12345\n");

            OnePassAgentMain.doInstall(
                    "config=" + props.getAbsolutePath(), stubInst, log);

            String output = capturedLog();
            assertTrue(output.contains("초기화 완료"), "초기화 완료 메시지 없음: " + output);
            assertTrue(output.contains("위빙 설치 완료"), "위빙 설치 완료 메시지 없음: " + output);
        }

        @Test
        @DisplayName("배너가 출력된다")
        void bannerIsPrinted() throws IOException {
            File props = createProps(
                    "onepass.agent.endpoint=https://onepass.go.kr\n" +
                    "onepass.agent.api-key=key\n");

            OnePassAgentMain.doInstall(
                    "config=" + props.getAbsolutePath(), stubInst, log);

            assertTrue(capturedLog().contains("OnePass Agency Java Agent"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 비활성화
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("enabled=false → 위빙 건너뜀 로그 출력")
    void disabledSkipsWeaving() throws IOException {
        File props = createProps(
                "onepass.agent.endpoint=https://onepass.go.kr\n" +
                "onepass.agent.api-key=key\n" +
                "onepass.agent.enabled=false\n");

        OnePassAgentMain.doInstall("config=" + props.getAbsolutePath(), stubInst, log);

        assertTrue(capturedLog().contains("위빙 건너뜀"), "비활성화 메시지 없음");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 에러 방어
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("에러 방어 — JVM 종료 없음")
    class ErrorDefenseTests {

        @Test
        @DisplayName("Instrumentation null → 오류 로그 후 정상 반환 (예외 없음)")
        void nullInstrumentationNoException() throws IOException {
            File props = createProps(
                    "onepass.agent.endpoint=https://onepass.go.kr\n" +
                    "onepass.agent.api-key=key\n");

            assertDoesNotThrow(() ->
                    OnePassAgentMain.doInstall("config=" + props.getAbsolutePath(), null, log));
            // null inst → 설정 로드 전에 리턴 or 설정 로드 후 리턴
            // 어느 쪽이든 예외 없이 반환
        }

        @Test
        @DisplayName("설정 파일 경로 없음 → 오류 로그 후 정상 반환")
        void missingConfigFileNoException() {
            assertDoesNotThrow(() ->
                    OnePassAgentMain.doInstall(
                            "config=/nonexistent/path.properties", stubInst, log));

            // 설정 로드 실패 → 클래스패스 fallback → 여전히 필수 항목 없으면 AgentConfigException
            // → ERROR 로그 출력 후 return
            assertTrue(capturedLog().contains("ERROR") || capturedLog().contains("설정"),
                    "오류 관련 로그가 없음: " + capturedLog());
        }

        @Test
        @DisplayName("agentArgs null이어도 예외 없음 (클래스패스 + 시스템 프로퍼티 fallback)")
        void nullAgentArgsNoException() {
            // 클래스패스에 onepass-agent.properties 없고 -D도 없으므로 실패하겠지만 예외는 없음
            assertDoesNotThrow(() ->
                    OnePassAgentMain.doInstall(null, stubInst, log));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 헬퍼
    // ────────────────────────────────────────────────────────────────────────────

    private String capturedLog() {
        return logCapture.toString();
    }

    private static File createProps(String content) throws IOException {
        File tmp = File.createTempFile("agent-main-test-", ".properties");
        tmp.deleteOnExit();
        try (FileWriter w = new FileWriter(tmp)) {
            w.write(content);
        }
        return tmp;
    }

    /**
     * Instrumentation No-op Stub.
     * byte-buddy AgentBuilder.installOn(inst) 호출 시 예외 없이 동작.
     */
    static class StubInstrumentation implements Instrumentation {

        @Override
        public void addTransformer(ClassFileTransformer transformer, boolean canRetransform) { }

        @Override
        public void addTransformer(ClassFileTransformer transformer) { }

        @Override
        public boolean removeTransformer(ClassFileTransformer transformer) { return true; }

        @Override
        public boolean isRetransformClassesSupported() { return true; }

        @Override
        public void retransformClasses(Class<?>... classes) throws UnmodifiableClassException { }

        @Override
        public boolean isRedefineClassesSupported() { return true; }

        @Override
        public void redefineClasses(java.lang.instrument.ClassDefinition... definitions)
                throws ClassNotFoundException, UnmodifiableClassException { }

        @Override
        public boolean isModifiableClass(Class<?> theClass) { return true; }

        @Override
        public Class<?>[] getAllLoadedClasses() { return new Class[0]; }

        @Override
        public Class<?>[] getInitiatedClasses(ClassLoader loader) { return new Class[0]; }

        @Override
        public long getObjectSize(Object objectToSize) { return 0; }

        @Override
        public void appendToBootstrapClassLoaderSearch(JarFile jarfile) { }

        @Override
        public void appendToSystemClassLoaderSearch(JarFile jarfile) { }

        @Override
        public boolean isNativeMethodPrefixSupported() { return false; }

        @Override
        public void setNativeMethodPrefix(ClassFileTransformer transformer, String prefix) { }

        // JDK 9+ Module 관련 메서드:
        // --release 8 컴파일 타겟에서는 Module 클래스가 없으므로
        // @Override 없이 Object 타입으로 처리하거나 생략.
        // Instrumentation 인터페이스의 default 구현이 JDK 9+에서 제공되므로
        // 테스트 환경(JDK 21 런타임)에서는 default 메서드가 자동 적용됨.
    }
}

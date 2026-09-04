package kr.go.smes.agent.http;

import kr.go.smes.agent.config.AgentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OnePassHttpClient} 단위 테스트.
 *
 * <h2>전략</h2>
 * {@link OnePassHttpClient#openConnection(String)}을 오버라이드한 익명 서브클래스로
 * 실제 네트워크 호출 없이 {@link HttpURLConnection} Mock을 주입한다.
 *
 * <p>JDK 8 호환 코드이므로 Mockito 대신 수동 Test Double 사용.
 */
@DisplayName("OnePassHttpClient — HTTP 요청/응답 + HMAC 서명")
class OnePassHttpClientTest {

    private static final PrintStream SILENT = new PrintStream(new ByteArrayOutputStream());

    private AgentConfig config;

    @BeforeEach
    void setUp() throws Exception {
        // 테스트용 최소 설정 파일 생성 없이 직접 AgentConfig 빌드
        config = buildConfig(
                "https://onepass.go.kr",
                "test-api-key",
                null, /* hmacSecret */
                3000, 10000, 1, true, "INFO");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // HMAC-SHA256 서명
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("hmacSign() — HMAC-SHA256 서명 생성")
    class HmacSignTests {

        @Test
        @DisplayName("동일 입력 → 동일 서명 (결정적)")
        void deterministicSignature() {
            String sig1 = OnePassHttpClient.hmacSign("apiKey:12345:body", "secret");
            String sig2 = OnePassHttpClient.hmacSign("apiKey:12345:body", "secret");
            assertEquals(sig1, sig2);
        }

        @Test
        @DisplayName("다른 입력 → 다른 서명")
        void differentInputDifferentSignature() {
            String sig1 = OnePassHttpClient.hmacSign("apiKey:12345:body1", "secret");
            String sig2 = OnePassHttpClient.hmacSign("apiKey:12345:body2", "secret");
            assertNotEquals(sig1, sig2);
        }

        @Test
        @DisplayName("서명은 소문자 16진수 64자 (SHA-256 출력)")
        void signatureIs64HexChars() {
            String sig = OnePassHttpClient.hmacSign("data", "secret");
            assertEquals(64, sig.length(), "HMAC-SHA256 서명은 64자 hex여야 합니다");
            assertTrue(sig.matches("[0-9a-f]+"), "소문자 16진수여야 합니다: " + sig);
        }

        @Test
        @DisplayName("빈 데이터로도 서명 생성 가능")
        void emptyDataSignable() {
            assertDoesNotThrow(() -> OnePassHttpClient.hmacSign("", "secret"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // HttpRequest / HttpResponse 값 타입
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("HttpRequest / HttpResponse 값 타입")
    class ValueTypeTests {

        @Test
        @DisplayName("HttpRequest.get() — 메서드=GET, 바디=null")
        void getRequest() {
            OnePassHttpClient.HttpRequest req = OnePassHttpClient.HttpRequest.get("/api/health");
            assertEquals("GET",          req.method());
            assertEquals("/api/health",  req.path());
            assertNull(req.body());
        }

        @Test
        @DisplayName("HttpRequest.post() — 메서드=POST, 바디 포함")
        void postRequest() {
            OnePassHttpClient.HttpRequest req =
                    OnePassHttpClient.HttpRequest.post("/api/token", "{\"token\":\"abc\"}");
            assertEquals("POST",           req.method());
            assertEquals("{\"token\":\"abc\"}", req.body());
        }

        @Test
        @DisplayName("HttpResponse.isSuccess() — 2xx true, 4xx/5xx false")
        void isSuccess() {
            assertTrue(new OnePassHttpClient.HttpResponse(200, "").isSuccess());
            assertTrue(new OnePassHttpClient.HttpResponse(201, "").isSuccess());
            assertTrue(new OnePassHttpClient.HttpResponse(204, "").isSuccess());
            assertFalse(new OnePassHttpClient.HttpResponse(400, "").isSuccess());
            assertFalse(new OnePassHttpClient.HttpResponse(401, "").isSuccess());
            assertFalse(new OnePassHttpClient.HttpResponse(500, "").isSuccess());
        }

        @Test
        @DisplayName("OnePassHttpException — httpStatus 보존")
        void httpExceptionPreservesStatus() {
            OnePassHttpClient.OnePassHttpException ex =
                    new OnePassHttpClient.OnePassHttpException("오류", 503);
            assertEquals(503, ex.httpStatus());
            assertFalse(ex.isNetworkError());
        }

        @Test
        @DisplayName("OnePassHttpException(Throwable) — httpStatus=-1")
        void httpExceptionNetworkError() {
            OnePassHttpClient.OnePassHttpException ex =
                    new OnePassHttpClient.OnePassHttpException("네트워크 오류",
                            new IOException("연결 거부"));
            assertEquals(-1, ex.httpStatus());
            assertTrue(ex.isNetworkError());
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // HTTP 요청 전송 (Mock HttpURLConnection)
    // ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("send() — HTTP 요청 전송 (Mock Connection)")
    class SendTests {

        @Test
        @DisplayName("200 응답 → HttpResponse(200) 반환")
        void success200() throws IOException {
            OnePassHttpClient client = buildClientWithMockConn(200, "{\"valid\":true}");

            OnePassHttpClient.HttpResponse resp = client.send(
                    OnePassHttpClient.HttpRequest.get("/api/v1/agency/health"));

            assertEquals(200, resp.statusCode());
            assertEquals("{\"valid\":true}", resp.body());
            assertTrue(resp.isSuccess());
        }

        @Test
        @DisplayName("401 응답 → 재시도 없이 즉시 HttpResponse(401) 반환")
        void clientError401NoRetry() throws Exception {
            // maxRetry=2이지만 401은 재시도 안 함
            AgentConfig cfg = buildConfig("https://onepass.go.kr", "key",
                    null, 3000, 10000, 2, true, "INFO");

            // 호출 횟수 추적
            int[] callCount = {0};
            OnePassHttpClient client = new OnePassHttpClient(cfg) {
                @Override
                HttpURLConnection openConnection(String url) throws IOException {
                    callCount[0]++;
                    return buildMockConnection(401, "{\"error\":\"unauthorized\"}");
                }
            };

            OnePassHttpClient.HttpResponse resp = client.send(
                    OnePassHttpClient.HttpRequest.post("/api/v1/agency/token/verify", "{}"));

            assertEquals(401, resp.statusCode());
            assertEquals(1, callCount[0], "401은 재시도 없이 1회만 호출해야 합니다");
        }

        @Test
        @DisplayName("IOException → maxRetry+1 회 시도 후 OnePassHttpException")
        void networkErrorRetries() throws Exception {
            AgentConfig cfg = buildConfig("https://onepass.go.kr", "key",
                    null, 100, 100, 2, true, "INFO"); // 빠른 타임아웃

            int[] callCount = {0};
            OnePassHttpClient client = new OnePassHttpClient(cfg) {
                @Override
                HttpURLConnection openConnection(String url) throws IOException {
                    callCount[0]++;
                    throw new IOException("연결 거부");
                }
            };

            OnePassHttpClient.OnePassHttpException ex = assertThrows(
                    OnePassHttpClient.OnePassHttpException.class,
                    () -> client.send(OnePassHttpClient.HttpRequest.get("/health")));

            assertEquals(3, callCount[0], "maxRetry=2이면 최대 3회 시도");
            assertTrue(ex.isNetworkError());
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 헬퍼 — Test Double
    // ────────────────────────────────────────────────────────────────────────────

    private OnePassHttpClient buildClientWithMockConn(int status, String body) throws IOException {
        return new OnePassHttpClient(config) {
            @Override
            HttpURLConnection openConnection(String url) throws IOException {
                return buildMockConnection(status, body);
            }
        };
    }

    /**
     * 최소 HttpURLConnection Test Double.
     * 실제 네트워크 연결 없이 지정된 statusCode와 body를 반환.
     */
    private static HttpURLConnection buildMockConnection(int statusCode, String body)
            throws IOException {
        // HttpURLConnection은 abstract이므로 익명 서브클래스로 생성
        return new HttpURLConnection(new java.net.URL("http://localhost")) {

            @Override public void connect() { /* no-op */ }
            @Override public void disconnect() { /* no-op */ }
            @Override public boolean usingProxy() { return false; }

            @Override
            public int getResponseCode() { return statusCode; }

            @Override
            public InputStream getInputStream() {
                return statusCode < 400
                        ? new ByteArrayInputStream(body.getBytes())
                        : null;
            }

            @Override
            public InputStream getErrorStream() {
                return statusCode >= 400
                        ? new ByteArrayInputStream(body.getBytes())
                        : null;
            }

            @Override
            public OutputStream getOutputStream() {
                return new ByteArrayOutputStream();
            }

            @Override
            public void setRequestMethod(String method) {
                // byte-buddy AgentBuilder가 요구하는 void 메서드 — 저장만
            }

            @Override
            public void setRequestProperty(String key, String value) {
                // 헤더 설정 — no-op
            }
        };
    }

    /** Reflection을 통한 AgentConfig 직접 생성 (테스트 전용) */
    private static AgentConfig buildConfig(
            String endpoint, String apiKey, String hmacSecret,
            int connectMs, int readMs, int maxRetry,
            boolean enabled, String logLevel) throws Exception {
        // AgentConfig는 생성자가 package-private이므로 임시 파일로 생성
        java.io.File tmp = java.nio.file.Files.createTempFile("agent-test-", ".properties").toFile();
        tmp.deleteOnExit();
        StringBuilder sb = new StringBuilder();
        sb.append("onepass.agent.endpoint=").append(endpoint).append("\n");
        sb.append("onepass.agent.api-key=").append(apiKey).append("\n");
        if (hmacSecret != null) sb.append("onepass.agent.hmac-secret=").append(hmacSecret).append("\n");
        sb.append("onepass.agent.connect-timeout-ms=").append(connectMs).append("\n");
        sb.append("onepass.agent.read-timeout-ms=").append(readMs).append("\n");
        sb.append("onepass.agent.max-retry=").append(maxRetry).append("\n");
        sb.append("onepass.agent.enabled=").append(enabled).append("\n");
        sb.append("onepass.agent.log-level=").append(logLevel).append("\n");
        try (java.io.FileWriter w = new java.io.FileWriter(tmp)) {
            w.write(sb.toString());
        }
        return AgentConfig.load("config=" + tmp.getAbsolutePath(), SILENT);
    }
}

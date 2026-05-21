package kr.go.smes.mock;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * OnePass Agent 테스트용 Mock SSO 검증 서버.
 *
 * <h2>역할</h2>
 * <p>유관기관 개발서버에서 onepass-agent를 테스트할 때,
 * 실제 OnePass 서버 대신 이 Mock 서버를 사용한다.
 * 설정된 유효 토큰 목록으로 검증 요청에 응답한다.
 *
 * <h2>API 엔드포인트</h2>
 * <ul>
 *   <li>{@code GET /health} — 헬스체크 (Agent HealthCheck 데몬용)</li>
 *   <li>{@code POST /api/agent/verify} — 토큰 검증 (Agent가 호출)</li>
 *   <li>{@code POST /api/v1/token/verify} — 토큰 검증 (대체 경로)</li>
 *   <li>{@code GET /admin/tokens} — 현재 유효 토큰 목록 조회 (관리용)</li>
 *   <li>{@code POST /admin/tokens} — 유효 토큰 추가 (동적 추가)</li>
 *   <li>{@code DELETE /admin/tokens/{token}} — 유효 토큰 제거 (동적 제거)</li>
 *   <li>{@code GET /admin/stats} — 요청 통계 조회</li>
 *   <li>{@code POST /admin/simulate/delay} — 응답 지연 시뮬레이션</li>
 *   <li>{@code POST /admin/simulate/error} — 오류 응답 시뮬레이션</li>
 * </ul>
 *
 * <h2>토큰 검증 요청 형식</h2>
 * <pre>
 *   POST /api/agent/verify
 *   Content-Type: application/json
 *   X-API-Key: {api-key}
 *
 *   {"token": "Bearer_토큰값"}
 * </pre>
 *
 * <h2>응답</h2>
 * <ul>
 *   <li>200: 유효한 토큰 ({"valid":true,"sub":"user-001","roles":["USER"]})</li>
 *   <li>401: 유효하지 않은 토큰 ({"valid":false,"error":"INVALID_TOKEN"})</li>
 *   <li>503: 시뮬레이션 오류 모드 ({"error":"SERVICE_UNAVAILABLE"})</li>
 * </ul>
 *
 * <h2>환경 변수</h2>
 * <ul>
 *   <li>{@code SERVER_PORT}: 서버 포트 (기본: 8080)</li>
 *   <li>{@code VALID_TOKENS}: 유효한 토큰 목록, 콤마 구분 (기본: test-token-001)</li>
 *   <li>{@code RESPONSE_DELAY_MS}: 응답 지연 (기본: 0)</li>
 *   <li>{@code API_KEY}: 예상 API 키 (기본: test-api-key)</li>
 * </ul>
 */
public class MockOnePassServer {

    private static final Set<String> validTokens = Collections.synchronizedSet(new LinkedHashSet<String>());
    private static volatile long responseDelayMs = 0;
    private static volatile boolean simulateError = false;
    private static volatile String expectedApiKey = "test-api-key";

    // 통계 카운터
    private static volatile long totalRequests = 0;
    private static volatile long validTokenCount = 0;
    private static volatile long invalidTokenCount = 0;
    private static volatile long healthCheckCount = 0;

    public static void main(String[] args) throws Exception {
        // 환경 변수 로드
        int port = Integer.parseInt(getEnv("SERVER_PORT", "8080"));
        expectedApiKey = getEnv("API_KEY", "test-api-key");
        responseDelayMs = Long.parseLong(getEnv("RESPONSE_DELAY_MS", "0"));

        // 기본 유효 토큰 로드
        String validTokensEnv = getEnv("VALID_TOKENS", "test-token-001,test-token-002,admin-token");
        for (String token : validTokensEnv.split(",")) {
            String t = token.trim();
            if (!t.isEmpty()) validTokens.add(t);
        }

        System.out.println("[MockOnePassServer] ===================================================");
        System.out.println("[MockOnePassServer] OnePass Agent 테스트용 Mock SSO 검증 서버");
        System.out.println("[MockOnePassServer] 포트: " + port);
        System.out.println("[MockOnePassServer] 유효 토큰: " + validTokens);
        System.out.println("[MockOnePassServer] API 키: " + expectedApiKey);
        System.out.println("[MockOnePassServer] 응답 지연: " + responseDelayMs + "ms");
        System.out.println("[MockOnePassServer] ===================================================");

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(10));

        // API 엔드포인트 등록
        server.createContext("/health",           new HealthHandler());
        server.createContext("/api/agent/verify", new VerifyHandler());
        server.createContext("/api/v1/token/verify", new VerifyHandler());
        server.createContext("/admin/tokens",     new AdminTokensHandler());
        server.createContext("/admin/stats",      new AdminStatsHandler());
        server.createContext("/admin/simulate",   new AdminSimulateHandler());

        server.start();
        System.out.println("[MockOnePassServer] 서버 시작 완료: http://0.0.0.0:" + port);

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                System.out.println("[MockOnePassServer] 서버 종료");
                server.stop(1);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 헬스체크
    // ─────────────────────────────────────────────────────────────────────────────

    static class HealthHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            healthCheckCount++;
            String body = "{\"status\":\"UP\",\"validTokenCount\":" + validTokens.size()
                    + ",\"totalRequests\":" + totalRequests + "}";
            sendJson(ex, 200, body);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 토큰 검증
    // ─────────────────────────────────────────────────────────────────────────────

    static class VerifyHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            totalRequests++;

            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405, "{\"error\":\"METHOD_NOT_ALLOWED\"}");
                return;
            }

            // API 키 검증
            String apiKey = ex.getRequestHeaders().getFirst("X-API-Key");
            if (apiKey == null || apiKey.isEmpty()) {
                apiKey = ex.getRequestHeaders().getFirst("Authorization");
                if (apiKey != null && apiKey.startsWith("Bearer ")) {
                    apiKey = apiKey.substring(7).trim();
                }
            }

            if (apiKey == null || !expectedApiKey.equals(apiKey)) {
                System.out.println("[MockOnePassServer] API 키 불일치: " + apiKey);
                sendJson(ex, 401, "{\"valid\":false,\"error\":\"INVALID_API_KEY\"}");
                return;
            }

            // 오류 시뮬레이션
            if (simulateError) {
                sendJson(ex, 503, "{\"error\":\"SERVICE_UNAVAILABLE\",\"message\":\"시뮬레이션 오류\"}");
                return;
            }

            // 응답 지연 시뮬레이션
            if (responseDelayMs > 0) {
                try { Thread.sleep(responseDelayMs); } catch (InterruptedException ignored) {}
            }

            // 요청 본문 읽기
            String body = readBody(ex);
            System.out.println("[MockOnePassServer] 검증 요청: " + body);

            // 토큰 추출 (단순 파싱)
            String token = extractToken(body);
            System.out.println("[MockOnePassServer] 추출된 토큰: " + token);

            if (token != null && validTokens.contains(token)) {
                validTokenCount++;
                System.out.println("[MockOnePassServer] ✅ 유효한 토큰: " + token);
                String resp = "{\"valid\":true,\"sub\":\"user-" + Math.abs(token.hashCode() % 1000)
                        + "\",\"roles\":[\"USER\"],\"exp\":" + (System.currentTimeMillis() / 1000 + 3600) + "}";
                sendJson(ex, 200, resp);
            } else {
                invalidTokenCount++;
                System.out.println("[MockOnePassServer] ❌ 유효하지 않은 토큰: " + token);
                sendJson(ex, 401, "{\"valid\":false,\"error\":\"INVALID_TOKEN\",\"token\":\"" + safeStr(token) + "\"}");
            }
        }

        private String extractToken(String body) {
            if (body == null || body.isEmpty()) return null;
            // JSON에서 "token":"VALUE" 추출 (단순 구현, Jackson 미사용)
            int idx = body.indexOf("\"token\"");
            if (idx < 0) return null;
            int colon = body.indexOf(':', idx);
            if (colon < 0) return null;
            int start = body.indexOf('"', colon + 1);
            if (start < 0) return null;
            int end = body.indexOf('"', start + 1);
            if (end < 0) return null;
            return body.substring(start + 1, end);
        }

        private String readBody(HttpExchange ex) throws IOException {
            InputStream is = ex.getRequestBody();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }

        private String safeStr(String s) {
            return s == null ? "null" : s.replace("\"", "\\\"");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 관리 API — 토큰 동적 추가/제거
    // ─────────────────────────────────────────────────────────────────────────────

    static class AdminTokensHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod().toUpperCase();
            String path = ex.getRequestURI().getPath();

            if ("GET".equals(method)) {
                // 토큰 목록 조회
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                synchronized (validTokens) {
                    for (String t : validTokens) {
                        if (!first) sb.append(",");
                        sb.append("\"").append(t).append("\"");
                        first = false;
                    }
                }
                sb.append("]");
                sendJson(ex, 200, "{\"tokens\":" + sb + ",\"count\":" + validTokens.size() + "}");

            } else if ("POST".equals(method)) {
                // 토큰 추가
                String body = readBody(ex);
                String token = extractValue(body, "token");
                if (token != null && !token.isEmpty()) {
                    validTokens.add(token);
                    System.out.println("[MockOnePassServer] 토큰 추가: " + token);
                    sendJson(ex, 200, "{\"added\":true,\"token\":\"" + token + "\"}");
                } else {
                    sendJson(ex, 400, "{\"error\":\"TOKEN_REQUIRED\"}");
                }

            } else if ("DELETE".equals(method)) {
                // 경로에서 토큰 추출 (/admin/tokens/{token})
                String[] parts = path.split("/");
                if (parts.length >= 4) {
                    String token = parts[parts.length - 1];
                    boolean removed = validTokens.remove(token);
                    System.out.println("[MockOnePassServer] 토큰 제거: " + token + " → " + removed);
                    sendJson(ex, 200, "{\"removed\":" + removed + ",\"token\":\"" + token + "\"}");
                } else {
                    sendJson(ex, 400, "{\"error\":\"TOKEN_IN_PATH_REQUIRED\"}");
                }
            }
        }

        private String readBody(HttpExchange ex) throws IOException {
            InputStream is = ex.getRequestBody();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }

        private String extractValue(String json, String key) {
            if (json == null) return null;
            int idx = json.indexOf("\"" + key + "\"");
            if (idx < 0) return null;
            int colon = json.indexOf(':', idx);
            if (colon < 0) return null;
            int start = json.indexOf('"', colon + 1);
            if (start < 0) return null;
            int end = json.indexOf('"', start + 1);
            if (end < 0) return null;
            return json.substring(start + 1, end);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 통계 API
    // ─────────────────────────────────────────────────────────────────────────────

    static class AdminStatsHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String body = "{" +
                "\"totalRequests\":" + totalRequests + "," +
                "\"validTokenRequests\":" + validTokenCount + "," +
                "\"invalidTokenRequests\":" + invalidTokenCount + "," +
                "\"healthChecks\":" + healthCheckCount + "," +
                "\"validTokenCount\":" + validTokens.size() + "," +
                "\"responseDelayMs\":" + responseDelayMs + "," +
                "\"simulateError\":" + simulateError +
                "}";
            sendJson(ex, 200, body);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 시뮬레이션 API — 지연/오류 시나리오
    // ─────────────────────────────────────────────────────────────────────────────

    static class AdminSimulateHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();

            if (path.contains("/delay")) {
                // 응답 지연 설정 (POST body: {"delayMs": 500})
                String body = readBody(ex);
                String delayStr = extractValue(body, "delayMs");
                if (delayStr != null) {
                    try {
                        responseDelayMs = Long.parseLong(delayStr);
                        System.out.println("[MockOnePassServer] 응답 지연 설정: " + responseDelayMs + "ms");
                        sendJson(ex, 200, "{\"delayMs\":" + responseDelayMs + "}");
                    } catch (NumberFormatException e) {
                        sendJson(ex, 400, "{\"error\":\"INVALID_DELAY\"}");
                    }
                }

            } else if (path.contains("/error")) {
                // 오류 시뮬레이션 토글
                String body = readBody(ex);
                String enableStr = extractValue(body, "enable");
                simulateError = !"false".equalsIgnoreCase(enableStr);
                System.out.println("[MockOnePassServer] 오류 시뮬레이션: " + simulateError);
                sendJson(ex, 200, "{\"simulateError\":" + simulateError + "}");

            } else if (path.contains("/reset")) {
                // 통계 리셋
                totalRequests = 0;
                validTokenCount = 0;
                invalidTokenCount = 0;
                healthCheckCount = 0;
                responseDelayMs = 0;
                simulateError = false;
                System.out.println("[MockOnePassServer] 통계 리셋");
                sendJson(ex, 200, "{\"reset\":true}");
            }
        }

        private String readBody(HttpExchange ex) throws IOException {
            InputStream is = ex.getRequestBody();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }

        private String extractValue(String json, String key) {
            if (json == null) return null;
            int idx = json.indexOf("\"" + key + "\"");
            if (idx < 0) return null;
            int colon = json.indexOf(':', idx);
            if (colon < 0) return null;
            // 숫자 또는 문자열 값
            int start = colon + 1;
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
            if (start >= json.length()) return null;
            if (json.charAt(start) == '"') {
                int end = json.indexOf('"', start + 1);
                return end < 0 ? null : json.substring(start + 1, end);
            } else {
                int end = start;
                while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
                return json.substring(start, end);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 공통 헬퍼
    // ─────────────────────────────────────────────────────────────────────────────

    static void sendJson(HttpExchange ex, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().set("X-OnePass-Mock", "true");
        ex.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String getEnv(String name, String defaultValue) {
        String val = System.getenv(name);
        return (val != null && !val.isEmpty()) ? val : defaultValue;
    }
}

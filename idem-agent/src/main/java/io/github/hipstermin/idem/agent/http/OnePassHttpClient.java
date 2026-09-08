package io.github.hipstermin.idem.agent.http;

import io.github.hipstermin.idem.agent.config.AgentConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * OnePass 인증 서버와 통신하는 순수 Java HTTP 클라이언트.
 *
 * <h2>설계 원칙</h2>
 * <ul>
 *   <li><b>의존성 0</b>: {@link java.net.HttpURLConnection} 전용 — 외부 라이브러리 없음</li>
 *   <li><b>JDK 8 호환</b>: lambda/var/text-block 미사용</li>
 *   <li><b>재시도</b>: 5xx 서버 오류 + IOException에 대해 지수 백오프 재시도</li>
 *   <li><b>HMAC-SHA256 서명</b>: {@code onepass.agent.hmac-secret} 설정 시 자동 서명 헤더 추가</li>
 *   <li><b>스레드 안전</b>: 불변 AgentConfig 참조, 커넥션별 독립 생성</li>
 * </ul>
 *
 * <h2>요청 흐름</h2>
 * <pre>
 *  send(request)
 *    ├─ buildConnection()        ← URL 연결, 헤더 설정, HMAC 서명
 *    ├─ writeBody()              ← POST/PUT 바디 전송
 *    ├─ readResponse()           ← 응답 코드 + 바디 읽기
 *    └─ retry loop (maxRetry)    ← 5xx / IOException 시 재시도
 * </pre>
 *
 * <h2>인증 헤더 구조</h2>
 * <pre>
 *   X-OnePass-Api-Key: {apiKey}
 *   X-OnePass-Timestamp: {epochSeconds}
 *   X-OnePass-Signature: HMAC-SHA256({apiKey}:{timestamp}:{requestBody})
 *   Content-Type: application/json;charset=UTF-8
 * </pre>
 */
public class OnePassHttpClient {

    private static final String CHARSET      = "UTF-8";
    private static final String CONTENT_TYPE = "application/json;charset=UTF-8";
    private static final String HMAC_ALGO    = "HmacSHA256";

    // ── 요청/응답 헤더 이름 ─────────────────────────────────────────────────────
    private static final String HDR_CONTENT_TYPE  = "Content-Type";
    private static final String HDR_ACCEPT        = "Accept";
    private static final String HDR_API_KEY       = "X-OnePass-Api-Key";
    private static final String HDR_TIMESTAMP     = "X-OnePass-Timestamp";
    private static final String HDR_SIGNATURE     = "X-OnePass-Signature";
    private static final String HDR_AGENT_VERSION = "X-OnePass-Agent-Version";

    private static final String AGENT_VERSION = "1.0.0";

    // ── 재시도 지수 백오프 기본 지연 (ms) ───────────────────────────────────────
    private static final long RETRY_BASE_DELAY_MS = 200L;

    private final AgentConfig config;

    public OnePassHttpClient(AgentConfig config) {
        this.config = config;
    }

    // ── 공개 API ─────────────────────────────────────────────────────────────────

    /**
     * HTTP 요청을 전송하고 응답을 반환한다.
     *
     * <p>설정된 {@code maxRetry}만큼 재시도하며, 모든 재시도가 실패하면
     * 마지막 예외를 {@link OnePassHttpException}으로 래핑해 던진다.
     *
     * @param request 요청 객체
     * @return 응답 객체 (4xx는 재시도하지 않고 즉시 반환)
     * @throws OnePassHttpException 네트워크 오류 또는 5xx 최종 실패
     */
    public HttpResponse send(HttpRequest request) {
        Throwable lastError = null;
        int attempts = config.maxRetry() + 1; // 초기 시도 + 재시도 횟수

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse response = doSend(request);

                // 4xx: 클라이언트 오류 — 재시도 불필요, 즉시 반환
                if (response.statusCode() >= 400 && response.statusCode() < 500) {
                    return response;
                }
                // 2xx / 3xx: 성공
                if (response.statusCode() < 500) {
                    return response;
                }
                // 5xx: 서버 오류 — 재시도 대상
                lastError = new OnePassHttpException(
                        "서버 오류 응답: HTTP " + response.statusCode()
                                + " [" + request.method() + " " + request.path() + "]",
                        response.statusCode());
            } catch (IOException e) {
                lastError = e;
            }

            // 마지막 시도가 아니면 지수 백오프 대기
            if (attempt < attempts) {
                sleepBackoff(attempt);
            }
        }

        // 모든 재시도 실패
        if (lastError instanceof OnePassHttpException) {
            throw (OnePassHttpException) lastError;
        }
        throw new OnePassHttpException(
                "HTTP 요청 실패 (재시도 " + config.maxRetry() + "회 후): "
                        + request.method() + " " + request.path(),
                lastError);
    }

    /**
     * OnePass 토큰 검증 API 호출 단축 메서드.
     *
     * <p>POST /api/v1/agency/token/verify
     *
     * @param tokenJson {@code {"token":"...", "agencyCode":"..."}} 형식 JSON
     * @return HTTP 응답 (200: 유효, 401: 만료/위조, 403: 기관 코드 불일치)
     */
    public HttpResponse verifyToken(String tokenJson) {
        return send(HttpRequest.post("/api/v1/agency/token/verify", tokenJson));
    }

    /**
     * Agent 헬스체크 — OnePass 서버 연결 확인.
     *
     * <p>GET /api/v1/agency/health
     *
     * @return HTTP 응답 (200: 정상, 503: 서버 점검 중)
     */
    public HttpResponse healthCheck() {
        return send(HttpRequest.get("/api/v1/agency/health"));
    }

    // ── 내부 구현 ────────────────────────────────────────────────────────────────

    private HttpResponse doSend(HttpRequest request) throws IOException {
        String fullUrl = config.endpoint() + request.path();
        HttpURLConnection conn = openConnection(fullUrl);
        try {
            // ── 메서드·타임아웃 ───────────────────────────────────────────────
            conn.setRequestMethod(request.method());
            conn.setConnectTimeout(config.connectTimeoutMs());
            conn.setReadTimeout(config.readTimeoutMs());
            conn.setInstanceFollowRedirects(false);

            // ── 공통 헤더 ─────────────────────────────────────────────────────
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
            conn.setRequestProperty(HDR_CONTENT_TYPE, CONTENT_TYPE);
            conn.setRequestProperty(HDR_ACCEPT, CONTENT_TYPE);
            conn.setRequestProperty(HDR_API_KEY, config.apiKey());
            conn.setRequestProperty(HDR_TIMESTAMP, timestamp);
            conn.setRequestProperty(HDR_AGENT_VERSION, AGENT_VERSION);

            // ── HMAC-SHA256 서명 (설정 시) ────────────────────────────────────
            if (config.isHmacEnabled() && request.body() != null) {
                String signature = hmacSign(
                        config.apiKey() + ":" + timestamp + ":" + request.body(),
                        config.hmacSecret());
                conn.setRequestProperty(HDR_SIGNATURE, signature);
            }

            // ── 요청 바디 전송 ────────────────────────────────────────────────
            if (request.body() != null && !request.body().isEmpty()) {
                conn.setDoOutput(true);
                byte[] bodyBytes = request.body().getBytes(Charset.forName(CHARSET));
                conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(bodyBytes);
                    os.flush();
                } finally {
                    os.close();
                }
            }

            // ── 응답 읽기 ─────────────────────────────────────────────────────
            int statusCode = conn.getResponseCode();
            String body = readBody(conn, statusCode);
            return new HttpResponse(statusCode, body);

        } finally {
            conn.disconnect();
        }
    }

    /**
     * HttpURLConnection을 열고 반환한다.
     * 테스트 시 오버라이드 가능하도록 보호 접근자로 분리.
     */
    HttpURLConnection openConnection(String urlString) throws IOException {
        URL url = new URL(urlString);
        return (HttpURLConnection) url.openConnection();
    }

    /** 응답 스트림을 UTF-8 문자열로 읽는다. 4xx/5xx는 errorStream 사용. */
    private static String readBody(HttpURLConnection conn, int statusCode) throws IOException {
        InputStream is = (statusCode >= 400) ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) return "";
        try {
            return readFully(is);
        } finally {
            is.close();
        }
    }

    private static String readFully(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toString(CHARSET);
    }

    /**
     * HMAC-SHA256 서명 생성.
     *
     * @param data    서명 대상 문자열 ({@code apiKey:timestamp:body})
     * @param secret  HMAC 시크릿 키
     * @return 소문자 16진수 서명 문자열
     */
    static String hmacSign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(Charset.forName(CHARSET)), HMAC_ALGO);
            mac.init(keySpec);
            byte[] raw = mac.doFinal(data.getBytes(Charset.forName(CHARSET)));
            return toHex(raw);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HmacSHA256 알고리즘 지원 안 됨 (JVM 환경 이상)", e);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("HMAC 키 형식 오류", e);
        }
    }

    /** byte[] → 소문자 16진수 문자열 (JDK 8 호환, HexFormat 미사용) */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /** 지수 백오프 대기 (InterruptedException 발생 시 스레드 인터럽트 상태 복원) */
    private static void sleepBackoff(int attempt) {
        long delay = RETRY_BASE_DELAY_MS * (1L << (attempt - 1)); // 200ms, 400ms, 800ms...
        delay = Math.min(delay, 5_000L); // 최대 5초
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── 요청/응답 값 타입 ─────────────────────────────────────────────────────────

    /**
     * 불변 HTTP 요청 객체.
     */
    public static final class HttpRequest {
        private final String method;
        private final String path;
        private final String body;

        private HttpRequest(String method, String path, String body) {
            this.method = method;
            this.path   = path;
            this.body   = body;
        }

        public static HttpRequest get(String path) {
            return new HttpRequest("GET", path, null);
        }

        public static HttpRequest post(String path, String body) {
            return new HttpRequest("POST", path, body);
        }

        public static HttpRequest put(String path, String body) {
            return new HttpRequest("PUT", path, body);
        }

        public String method() { return method; }
        public String path()   { return path; }
        public String body()   { return body; }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    /**
     * 불변 HTTP 응답 객체.
     */
    public static final class HttpResponse {
        private final int    statusCode;
        private final String body;

        public HttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body       = body;
        }

        public int    statusCode() { return statusCode; }
        public String body()       { return body; }
        public boolean isSuccess() { return statusCode >= 200 && statusCode < 300; }

        @Override
        public String toString() {
            return "HttpResponse{status=" + statusCode + ", body.length="
                    + (body == null ? 0 : body.length()) + '}';
        }
    }

    /**
     * OnePass HTTP 통신 예외.
     *
     * <p>재시도 후에도 실패하거나 4xx 처리가 필요할 때 사용.
     */
    public static final class OnePassHttpException extends RuntimeException {
        private final int httpStatus; // -1 이면 네트워크 수준 오류

        public OnePassHttpException(String message, int httpStatus) {
            super(message);
            this.httpStatus = httpStatus;
        }

        public OnePassHttpException(String message, Throwable cause) {
            super(message, cause);
            this.httpStatus = -1;
        }

        public int httpStatus() { return httpStatus; }

        public boolean isNetworkError() { return httpStatus == -1; }
    }
}

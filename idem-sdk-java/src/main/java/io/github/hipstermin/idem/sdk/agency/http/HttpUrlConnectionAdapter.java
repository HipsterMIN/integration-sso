package io.github.hipstermin.idem.sdk.agency.http;

import io.github.hipstermin.idem.sdk.agency.exception.AgencyHttpException;
import io.github.hipstermin.idem.sdk.agency.model.GatewayResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * {@link AgencyHttpAdapter} 기본 구현체 — {@code HttpURLConnection} 사용
 *
 * <p><b>JDK 1.1+, 외부 의존성 없음.</b>
 * Android 2.2+, Java SE 8~21 모든 환경에서 동작한다.
 *
 * <h3>특성</h3>
 * <ul>
 *   <li>연결 타임아웃 / 읽기 타임아웃 설정 가능</li>
 *   <li>리다이렉트 비활성화 (보안 정책상 OnePass 서버는 리다이렉트 없음)</li>
 *   <li>응답 헤더에서 {@code X-Correlation-Id}, {@code X-Request-Id} 자동 추출</li>
 *   <li>4xx 응답 시 {@link AgencyHttpException} 발생 (재시도 없음)</li>
 *   <li>5xx 응답 또는 {@code IOException} 발생 시 지수 백오프 자동 재시도 (GAP-3)</li>
 * </ul>
 *
 * <h3>재시도 정책 (GAP-3)</h3>
 * <ul>
 *   <li>재시도 대상: 5xx 서버 오류, {@code IOException} (네트워크 오류)</li>
 *   <li>재시도 제외: 4xx 클라이언트 오류 (요청 자체가 잘못된 경우)</li>
 *   <li>최대 재시도 횟수: 기본 {@value #DEFAULT_MAX_RETRIES}회 (총 {@code 1 + maxRetries}번 시도)</li>
 *   <li>대기 전략: 지수 백오프 — {@code baseDelayMs × 2^(시도횟수-1)} (최대 {@value #MAX_DELAY_MS}ms)</li>
 *   <li>기본 대기: 200ms → 400ms → 800ms</li>
 * </ul>
 *
 * <p><b>JDK 버전 호환: Java 8+</b> (JDK 1.1+ 실제 호환이나, SDK 빌드 타겟은 8)
 */
public class HttpUrlConnectionAdapter implements AgencyHttpAdapter {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int     BUFFER_SIZE   = 8192;

    // ── 재시도 상수 (GAP-3) ────────────────────────────────────────────────
    static final int  DEFAULT_MAX_RETRIES = 3;
    static final long BASE_DELAY_MS       = 200L;
    static final long MAX_DELAY_MS        = 5_000L;

    private final int  connectTimeoutMs;
    private final int  readTimeoutMs;
    private final int  maxRetries;
    private final long baseDelayMs;

    /**
     * 기본 타임아웃 (연결 5초, 읽기 30초), 재시도 3회
     */
    public HttpUrlConnectionAdapter() {
        this(5_000, 30_000);
    }

    /**
     * 커스텀 타임아웃, 재시도 기본 3회
     *
     * @param connectTimeoutMs 연결 타임아웃 (밀리초)
     * @param readTimeoutMs    읽기 타임아웃 (밀리초)
     */
    public HttpUrlConnectionAdapter(int connectTimeoutMs, int readTimeoutMs) {
        this(connectTimeoutMs, readTimeoutMs, DEFAULT_MAX_RETRIES, BASE_DELAY_MS);
    }

    /**
     * 완전 커스텀 설정 (타임아웃 + 재시도 횟수 + 백오프 기준 대기시간)
     *
     * @param connectTimeoutMs 연결 타임아웃 (밀리초, &gt; 0)
     * @param readTimeoutMs    읽기 타임아웃 (밀리초, &gt; 0)
     * @param maxRetries       최대 재시도 횟수 (0이면 재시도 없음, 최대 10)
     * @param baseDelayMs      지수 백오프 기준 대기시간 (밀리초, &gt; 0)
     */
    public HttpUrlConnectionAdapter(int connectTimeoutMs, int readTimeoutMs,
                                     int maxRetries, long baseDelayMs) {
        if (connectTimeoutMs <= 0) throw new IllegalArgumentException("connectTimeoutMs must be > 0");
        if (readTimeoutMs <= 0)    throw new IllegalArgumentException("readTimeoutMs must be > 0");
        if (maxRetries < 0 || maxRetries > 10)
            throw new IllegalArgumentException("maxRetries must be 0..10");
        if (baseDelayMs <= 0)      throw new IllegalArgumentException("baseDelayMs must be > 0");
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs    = readTimeoutMs;
        this.maxRetries       = maxRetries;
        this.baseDelayMs      = baseDelayMs;
    }

    @Override
    public GatewayResponse execute(String method, String url,
                                    Map<String, String> headers, String body) {
        AgencyHttpException lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            // 재시도 전 지수 백오프 대기 (첫 시도는 대기 없음)
            if (attempt > 0) {
                sleepBackoff(attempt);
            }

            HttpURLConnection conn = null;
            try {
                conn = openConnection(url);
                conn.setRequestMethod(method);
                conn.setConnectTimeout(connectTimeoutMs);
                conn.setReadTimeout(readTimeoutMs);
                conn.setInstanceFollowRedirects(false);  // 리다이렉트 비활성화

                // 요청 헤더 설정
                if (headers != null) {
                    for (Map.Entry<String, String> entry : headers.entrySet()) {
                        conn.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }

                // 요청 본문 전송 (POST, PATCH, PUT)
                if (body != null && !body.isEmpty()) {
                    conn.setDoOutput(true);
                    byte[] bodyBytes = body.getBytes(UTF8);
                    conn.setFixedLengthStreamingMode(bodyBytes.length);
                    OutputStream out = conn.getOutputStream();
                    try {
                        out.write(bodyBytes);
                        out.flush();
                    } finally {
                        out.close();
                    }
                }

                // 응답 읽기
                int httpStatus = conn.getResponseCode();
                String responseBody = readStream(
                        httpStatus >= 400 ? conn.getErrorStream() : conn.getInputStream()
                );

                // 응답 헤더 추출
                String correlationId = conn.getHeaderField("X-Correlation-Id");
                String requestId     = conn.getHeaderField("X-Request-Id");

                // 4xx → 즉시 예외 (재시도 없음 — 요청 자체가 잘못된 경우)
                if (httpStatus >= 400 && httpStatus < 500) {
                    throw new AgencyHttpException(httpStatus, responseBody);
                }

                // 5xx → 재시도 대상: 마지막 시도이면 예외, 아니면 루프 계속
                if (httpStatus >= 500) {
                    lastException = new AgencyHttpException(httpStatus, responseBody);
                    continue;  // 재시도
                }

                return GatewayResponse.of(httpStatus, responseBody, correlationId, requestId);

            } catch (AgencyHttpException e) {
                // 4xx는 바로 위에서 throw되어 여기 도달, 그대로 재전파
                throw e;
            } catch (IOException e) {
                // 네트워크 오류 → 재시도 대상
                lastException = new AgencyHttpException(
                        "네트워크 오류 [" + method + " " + url + "] (시도 " + (attempt + 1)
                        + "/" + (maxRetries + 1) + "): " + e.getMessage(), e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        // 모든 재시도 소진 후에도 실패
        if (lastException != null) {
            throw lastException;
        }
        // 여기 도달하는 경우는 없지만 컴파일러 만족용
        throw new AgencyHttpException("알 수 없는 오류 [" + method + " " + url + "]", null);
    }

    /**
     * 지수 백오프 대기
     * <p>대기 시간: {@code min(baseDelayMs × 2^(attempt-1), MAX_DELAY_MS)}
     *
     * @param attempt 현재 재시도 번호 (1부터 시작)
     */
    private void sleepBackoff(int attempt) {
        long delayMs = Math.min(baseDelayMs * (1L << (attempt - 1)), MAX_DELAY_MS);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AgencyHttpException("재시도 대기 중 인터럽트 발생", ie);
        }
    }

    // ── 내부 헬퍼 ───────────────────────────────────────────────────────────

    /** 테스트에서 오버라이드 가능 (MockURLConnection 주입) */
    protected HttpURLConnection openConnection(String urlStr) throws IOException {
        return (HttpURLConnection) new URL(urlStr).openConnection();
    }

    /**
     * InputStream → String 변환 (null-safe, JDK 8 호환).
     * Java 9+ {@code InputStream.readAllBytes()} 미사용.
     */
    private static String readStream(InputStream stream) throws IOException {
        if (stream == null) return "";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[BUFFER_SIZE];
        int bytesRead;
        try {
            while ((bytesRead = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, bytesRead);
            }
        } finally {
            stream.close();
        }
        return buffer.toString(UTF8.name());
    }
}

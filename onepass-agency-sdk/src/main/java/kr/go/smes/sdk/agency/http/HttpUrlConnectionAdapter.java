package kr.go.smes.sdk.agency.http;

import kr.go.smes.sdk.agency.exception.AgencyHttpException;
import kr.go.smes.sdk.agency.model.GatewayResponse;

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
 *   <li>4xx/5xx 응답 시 {@link AgencyHttpException} 발생</li>
 * </ul>
 *
 * <p><b>JDK 버전 호환: Java 8+</b> (JDK 1.1+ 실제 호환이나, SDK 빌드 타겟은 8)
 */
public class HttpUrlConnectionAdapter implements AgencyHttpAdapter {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int     BUFFER_SIZE = 8192;

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    /**
     * 기본 타임아웃 (연결 5초, 읽기 30초)
     */
    public HttpUrlConnectionAdapter() {
        this(5_000, 30_000);
    }

    /**
     * 커스텀 타임아웃
     *
     * @param connectTimeoutMs 연결 타임아웃 (밀리초)
     * @param readTimeoutMs    읽기 타임아웃 (밀리초)
     */
    public HttpUrlConnectionAdapter(int connectTimeoutMs, int readTimeoutMs) {
        if (connectTimeoutMs <= 0) throw new IllegalArgumentException("connectTimeoutMs must be > 0");
        if (readTimeoutMs <= 0)    throw new IllegalArgumentException("readTimeoutMs must be > 0");
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs    = readTimeoutMs;
    }

    @Override
    public GatewayResponse execute(String method, String url,
                                    Map<String, String> headers, String body) {
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

            // 4xx / 5xx → 예외
            if (httpStatus >= 400) {
                throw new AgencyHttpException(httpStatus, responseBody);
            }

            return GatewayResponse.of(httpStatus, responseBody, correlationId, requestId);

        } catch (AgencyHttpException e) {
            throw e;  // 재포장 없이 그대로 전파
        } catch (IOException e) {
            throw new AgencyHttpException("네트워크 오류 [" + method + " " + url + "]: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
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

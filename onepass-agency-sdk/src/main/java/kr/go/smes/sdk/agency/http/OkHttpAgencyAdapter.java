package kr.go.smes.sdk.agency.http;

import kr.go.smes.sdk.agency.exception.AgencyHttpException;
import kr.go.smes.sdk.agency.model.GatewayResponse;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@link AgencyHttpAdapter} OkHttp3 구현체
 *
 * <p><b>사용 전제: OkHttp3 4.x가 클래스패스에 있어야 한다.</b>
 *
 * <pre>{@code
 * // Gradle (사용자 프로젝트)
 * implementation("com.squareup.okhttp3:okhttp:4.12.0")
 *
 * // SDK 초기화
 * OkHttpClient okClient = new OkHttpClient.Builder()
 *     .connectTimeout(5, TimeUnit.SECONDS)
 *     .readTimeout(30, TimeUnit.SECONDS)
 *     .build();
 *
 * AgencyGatewayClient client = AgencyGatewayClient.builder()
 *     .baseUrl("https://onepass.go.kr")
 *     .apiKey("your-api-key")
 *     .httpAdapter(new OkHttpAgencyAdapter(okClient))
 *     .build();
 * }</pre>
 *
 * <h3>OkHttp3 장점</h3>
 * <ul>
 *   <li>HTTP/2 지원 (서버가 지원하면 자동 사용)</li>
 *   <li>커넥션 풀링으로 성능 우수</li>
 *   <li>재시도 정책 커스터마이징 용이</li>
 * </ul>
 *
 * <h3>재시도 정책</h3>
 * <ul>
 *   <li>재시도 대상: 5xx 서버 오류, 일시적 네트워크 오류
 *       ({@code ConnectException}, {@code UnknownHostException})</li>
 *   <li>재시도 제외: 4xx 클라이언트 오류, 타임아웃
 *       ({@code SocketTimeoutException}, {@code InterruptedIOException})</li>
 *   <li>최대 재시도 횟수: 기본 {@value #DEFAULT_MAX_RETRIES}회 (총 {@code 1 + maxRetries}번 시도)</li>
 *   <li>대기 전략: 지수 백오프 — {@code baseDelayMs × 2^(시도횟수-1)} (최대 {@value #MAX_DELAY_MS}ms)</li>
 *   <li>기본 대기: 200ms → 400ms → 800ms</li>
 * </ul>
 *
 * <p><b>JDK 버전 호환: Java 8+</b> (OkHttp4.x 요구사항 충족)
 */
public class OkHttpAgencyAdapter implements AgencyHttpAdapter {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // ── 재시도 상수 ─────────────────────────────────────────────────────────
    static final int  DEFAULT_MAX_RETRIES = 3;
    static final long BASE_DELAY_MS       = 200L;
    static final long MAX_DELAY_MS        = 5_000L;

    private final OkHttpClient client;
    private final int          maxRetries;
    private final long         baseDelayMs;

    /**
     * 기본 OkHttpClient (연결 5초, 읽기 30초), 재시도 3회
     */
    public OkHttpAgencyAdapter() {
        this(new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false)
                .build());
    }

    /**
     * 커스텀 OkHttpClient 주입, 재시도 기본 3회
     *
     * @param client 외부에서 구성한 OkHttpClient (커넥션 풀 공유 시 사용)
     */
    public OkHttpAgencyAdapter(OkHttpClient client) {
        this(client, DEFAULT_MAX_RETRIES, BASE_DELAY_MS);
    }

    /**
     * 완전 커스텀 설정 (OkHttpClient + 재시도 횟수 + 백오프 기준 대기시간)
     *
     * @param client      외부에서 구성한 OkHttpClient
     * @param maxRetries  최대 재시도 횟수 (0이면 재시도 없음, 최대 10)
     * @param baseDelayMs 지수 백오프 기준 대기시간 (밀리초, &gt; 0)
     */
    public OkHttpAgencyAdapter(OkHttpClient client, int maxRetries, long baseDelayMs) {
        if (client == null)    throw new IllegalArgumentException("OkHttpClient must not be null");
        if (maxRetries < 0 || maxRetries > 10)
            throw new IllegalArgumentException("maxRetries must be 0..10");
        if (baseDelayMs <= 0)  throw new IllegalArgumentException("baseDelayMs must be > 0");
        this.client      = client;
        this.maxRetries  = maxRetries;
        this.baseDelayMs = baseDelayMs;
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

            // 매 시도마다 요청 객체를 새로 빌드 (OkHttp Request는 immutable)
            Request request = buildRequest(method, url, headers, body);

            try (Response response = client.newCall(request).execute()) {
                int httpStatus       = response.code();
                ResponseBody respBody = response.body();
                String responseBodyStr = respBody != null ? respBody.string() : "";
                String correlationId   = response.header("X-Correlation-Id");
                String requestId       = response.header("X-Request-Id");

                // 4xx → 즉시 예외 (재시도 없음)
                if (httpStatus >= 400 && httpStatus < 500) {
                    throw new AgencyHttpException(httpStatus, responseBodyStr);
                }

                // 5xx → 재시도 대상
                if (httpStatus >= 500) {
                    lastException = new AgencyHttpException(httpStatus, responseBodyStr);
                    continue;
                }

                return GatewayResponse.of(httpStatus, responseBodyStr, correlationId, requestId);

            } catch (AgencyHttpException e) {
                // 4xx: 즉시 재전파
                throw e;
            } catch (IOException e) {
                // 타임아웃 및 인터럽트 → 재시도하지 않고 즉시 예외
                if (!shouldRetryOn(e)) {
                    throw new AgencyHttpException(
                            "OkHttp 네트워크 오류 [" + method + " " + url + "]: " + e.getMessage(), e);
                }
                // 일시적 네트워크 오류 → 재시도 대상
                lastException = new AgencyHttpException(
                        "OkHttp 네트워크 오류 [" + method + " " + url + "] (시도 " + (attempt + 1)
                        + "/" + (maxRetries + 1) + "): " + e.getMessage(), e);
            }
        }

        // 모든 재시도 소진 후에도 실패
        if (lastException != null) {
            throw lastException;
        }
        throw new AgencyHttpException("알 수 없는 오류 [" + method + " " + url + "]", null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 내부 헬퍼
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 매 시도마다 새 {@link Request} 객체를 생성한다.
     *
     * <p>OkHttp {@link Request}는 immutable이므로 루프 외부에서 한 번만 빌드해도 되지만,
     * 명시적으로 루프 내부에서 빌드하여 재시도 시 상태 공유 문제를 차단한다.
     */
    private Request buildRequest(String method, String url,
                                  Map<String, String> headers, String body) {
        RequestBody requestBody;
        String upperMethod = method.toUpperCase();

        if ("GET".equals(upperMethod) || "DELETE".equals(upperMethod)) {
            requestBody = null;
        } else {
            requestBody = (body != null && !body.isEmpty())
                    ? RequestBody.create(body, JSON)
                    : RequestBody.create("", JSON);
        }

        Request.Builder reqBuilder = new Request.Builder().url(url);

        // 헤더 설정
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                reqBuilder.header(entry.getKey(), entry.getValue());
            }
        }

        // HTTP 메서드 설정
        switch (upperMethod) {
            case "GET":    reqBuilder.get();                                           break;
            case "POST":   reqBuilder.post(requestBody);                               break;
            case "PATCH":  reqBuilder.patch(requestBody);                              break;
            case "PUT":    reqBuilder.put(requestBody);                                break;
            case "DELETE": reqBuilder.delete();                                        break;
            default:       reqBuilder.method(method, requestBody);                     break;
        }

        return reqBuilder.build();
    }

    /**
     * 해당 {@link IOException}이 재시도할 만한 일시적 오류인지 판단한다.
     *
     * <h3>재시도 제외 (false 반환)</h3>
     * <ul>
     *   <li>{@link SocketTimeoutException} — 읽기/연결 타임아웃: 서버가 이미 요청을
     *       처리했을 수 있으므로 멱등성 보장 없이 재시도하면 중복 처리 위험</li>
     *   <li>{@link InterruptedIOException} — 스레드 인터럽트 신호: 재시도해선 안 됨</li>
     * </ul>
     *
     * <h3>재시도 대상 (true 반환)</h3>
     * <ul>
     *   <li>{@code ConnectException} — 서버 미기동, 네트워크 단절 등 일시적 연결 실패</li>
     *   <li>{@code UnknownHostException} — DNS 일시 장애</li>
     *   <li>기타 {@code IOException} — 연결 리셋 등</li>
     * </ul>
     *
     * @param e 발생한 예외
     * @return 재시도 가능하면 {@code true}, 타임아웃·인터럽트면 {@code false}
     */
    public static boolean shouldRetryOn(IOException e) {
        // SocketTimeoutException은 InterruptedIOException의 서브클래스이므로
        // 상위 타입 검사만으로 두 가지를 모두 배제할 수 있다.
        if (e instanceof InterruptedIOException) {
            return false;  // SocketTimeoutException 포함
        }
        return true;
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
}

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
 * <p><b>JDK 버전 호환: Java 8+</b> (OkHttp4.x 요구사항 충족)
 */
public class OkHttpAgencyAdapter implements AgencyHttpAdapter {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;

    /**
     * 기본 OkHttpClient (연결 5초, 읽기 30초) 사용
     */
    public OkHttpAgencyAdapter() {
        this(new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false)
                .build());
    }

    /**
     * 커스텀 OkHttpClient 주입
     *
     * @param client 외부에서 구성한 OkHttpClient (커넥션 풀 공유 시 사용)
     */
    public OkHttpAgencyAdapter(OkHttpClient client) {
        if (client == null) throw new IllegalArgumentException("OkHttpClient must not be null");
        this.client = client;
    }

    @Override
    public GatewayResponse execute(String method, String url,
                                    Map<String, String> headers, String body) {
        // 요청 본문
        RequestBody requestBody = (body != null && !body.isEmpty())
                ? RequestBody.create(body, JSON)
                : null;

        // GET/DELETE는 본문 없음
        if ("GET".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)) {
            requestBody = null;
        }

        Request.Builder reqBuilder = new Request.Builder().url(url);

        // 헤더 설정
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                reqBuilder.header(entry.getKey(), entry.getValue());
            }
        }

        // HTTP 메서드 설정
        switch (method.toUpperCase()) {
            case "GET":    reqBuilder.get(); break;
            case "POST":   reqBuilder.post(requestBody != null ? requestBody : RequestBody.create("", JSON)); break;
            case "PATCH":  reqBuilder.patch(requestBody != null ? requestBody : RequestBody.create("", JSON)); break;
            case "PUT":    reqBuilder.put(requestBody != null ? requestBody : RequestBody.create("", JSON)); break;
            case "DELETE": reqBuilder.delete(); break;
            default:       reqBuilder.method(method, requestBody); break;
        }

        try (Response response = client.newCall(reqBuilder.build()).execute()) {
            int httpStatus = response.code();
            ResponseBody respBody = response.body();
            String responseBodyStr = respBody != null ? respBody.string() : "";
            String correlationId  = response.header("X-Correlation-Id");
            String requestId      = response.header("X-Request-Id");

            if (httpStatus >= 400) {
                throw new AgencyHttpException(httpStatus, responseBodyStr);
            }

            return GatewayResponse.of(httpStatus, responseBodyStr, correlationId, requestId);

        } catch (AgencyHttpException e) {
            throw e;
        } catch (IOException e) {
            throw new AgencyHttpException("OkHttp 네트워크 오류 [" + method + " " + url + "]: " + e.getMessage(), e);
        }
    }
}

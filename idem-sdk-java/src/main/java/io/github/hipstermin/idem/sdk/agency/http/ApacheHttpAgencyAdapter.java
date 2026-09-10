package io.github.hipstermin.idem.sdk.agency.http;

import io.github.hipstermin.idem.sdk.agency.exception.AgencyHttpException;
import io.github.hipstermin.idem.sdk.agency.model.GatewayResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPatch;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

/**
 * {@link AgencyHttpAdapter} Apache HttpClient 5.x 구현체
 *
 * <p><b>사용 전제: Apache HttpClient 5.x가 클래스패스에 있어야 한다.</b>
 *
 * <pre>{@code
 * // Gradle (사용자 프로젝트)
 * implementation("org.apache.httpcomponents.client5:httpclient5:5.3.1")
 *
 * // SDK 초기화
 * CloseableHttpClient apacheClient = HttpClients.createDefault();
 *
 * AgencyGatewayClient client = AgencyGatewayClient.builder()
 *     .baseUrl("https://onepass.go.kr")
 *     .apiKey("your-api-key")
 *     .httpAdapter(new ApacheHttpAgencyAdapter(apacheClient))
 *     .build();
 * }</pre>
 *
 * <h3>Apache HttpClient 5.x 장점</h3>
 * <ul>
 *   <li>엔터프라이즈 환경 (인트라넷, 프록시, mTLS) 지원 풍부</li>
 *   <li>연결 풀 세밀한 제어 가능</li>
 *   <li>Spring Boot 내부 기본 HTTP 클라이언트와 동일 라이브러리</li>
 * </ul>
 *
 * <p><b>JDK 버전 호환: Java 8+</b>
 */
public class ApacheHttpAgencyAdapter implements AgencyHttpAdapter {

    private final CloseableHttpClient httpClient;
    private final boolean             ownsClient;  // close 책임 여부

    /**
     * 기본 CloseableHttpClient 자동 생성
     */
    public ApacheHttpAgencyAdapter() {
        this(HttpClients.createDefault(), true);
    }

    /**
     * 외부에서 생성한 클라이언트 주입 (커넥션 풀 공유 시 사용)
     *
     * @param httpClient 공유 CloseableHttpClient
     */
    public ApacheHttpAgencyAdapter(CloseableHttpClient httpClient) {
        this(httpClient, false);
    }

    private ApacheHttpAgencyAdapter(CloseableHttpClient httpClient, boolean ownsClient) {
        if (httpClient == null) throw new IllegalArgumentException("CloseableHttpClient must not be null");
        this.httpClient = httpClient;
        this.ownsClient = ownsClient;
    }

    @Override
    public GatewayResponse execute(String method, String url,
                                    Map<String, String> headers, String body) {
        HttpUriRequestBase request = buildRequest(method, url, body);

        // 헤더 설정
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                request.setHeader(entry.getKey(), entry.getValue());
            }
        }

        // ResponseHandler 패턴: response entity + headers를 안전하게 소비하고 연결 반환
        // httpStatus를 final 배열에 캡처하여 catch (IOException) 블록에서도 보존
        final int[] capturedStatus = {-1};
        try {
            return httpClient.execute(request, new HttpClientResponseHandler<GatewayResponse>() {
                @Override
                public GatewayResponse handleResponse(ClassicHttpResponse response) throws IOException {
                    int httpStatus = response.getCode();
                    capturedStatus[0] = httpStatus;   // IOException 발생 시 보존용

                    String responseBody;
                    HttpEntity entity = response.getEntity();
                    try {
                        responseBody = entity != null
                                ? EntityUtils.toString(entity, StandardCharsets.UTF_8)
                                : "";
                    } catch (ParseException e) {
                        responseBody = "";
                    } finally {
                        EntityUtils.consume(entity);  // 반드시 소비 → 연결 풀 반환 보장
                    }

                    // 응답 헤더 추출
                    Header correlationHeader = response.getFirstHeader("X-Correlation-Id");
                    Header requestIdHeader   = response.getFirstHeader("X-Request-Id");
                    String correlationId     = correlationHeader != null ? correlationHeader.getValue() : null;
                    String requestId         = requestIdHeader   != null ? requestIdHeader.getValue()   : null;

                    if (httpStatus >= 400) {
                        throw new ApacheStatusException(httpStatus, responseBody);
                    }
                    return GatewayResponse.of(httpStatus, responseBody, correlationId, requestId);
                }
            });
        } catch (ApacheStatusException e) {
            throw new AgencyHttpException(e.status, e.body);
        } catch (AgencyHttpException e) {
            throw e;
        } catch (IOException e) {
            // executeOpen 자체 실패 또는 handleResponse 내 IO 오류
            // capturedStatus[0] >= 400이면 HTTP 오류 응답에서 발생한 IO 오류
            if (capturedStatus[0] >= 400) {
                throw new AgencyHttpException(capturedStatus[0], e.getMessage());
            }
            throw new AgencyHttpException(
                    "Apache HttpClient 네트워크 오류 [" + method + " " + url + "]: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new AgencyHttpException(
                    "Apache HttpClient 예외 [" + method + " " + url + "]: " + e.getMessage(), e);
        }
    }

    /**
     * HTTP 메서드 문자열 → Apache 요청 객체 변환
     */
    private HttpUriRequestBase buildRequest(String method, String url, String body) {
        StringEntity entity = (body != null && !body.isEmpty())
                ? new StringEntity(body, ContentType.APPLICATION_JSON)
                : null;

        switch (method.toUpperCase()) {
            case "GET": {
                return new HttpGet(url);
            }
            case "POST": {
                HttpPost post = new HttpPost(url);
                if (entity != null) post.setEntity(entity);
                return post;
            }
            case "PATCH": {
                HttpPatch patch = new HttpPatch(url);
                if (entity != null) patch.setEntity(entity);
                return patch;
            }
            case "PUT": {
                HttpPut put = new HttpPut(url);
                if (entity != null) put.setEntity(entity);
                return put;
            }
            case "DELETE": {
                return new HttpDelete(url);
            }
            default:
                throw new IllegalArgumentException("지원하지 않는 HTTP 메서드: " + method);
        }
    }

    /**
     * SDK가 직접 생성한 클라이언트인 경우 닫기.
     * 외부 주입 클라이언트는 호출자가 관리.
     */
    public void close() throws IOException {
        if (ownsClient) {
            httpClient.close();
        }
    }

    /**
     * ResponseHandler 내부에서 4xx/5xx 상태를 IOException이 아닌 checked exception으로 전파.
     * Apache HC5의 execute()는 IOException만 checked이므로, RuntimeException 서브클래스 사용.
     */
    private static final class ApacheStatusException extends RuntimeException {
        final int    status;
        final String body;

        ApacheStatusException(int status, String body) {
            super("HTTP " + status);
            this.status = status;
            this.body   = body;
        }
    }
}

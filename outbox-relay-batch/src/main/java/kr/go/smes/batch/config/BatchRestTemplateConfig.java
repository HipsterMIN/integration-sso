package kr.go.smes.batch.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 배치 서비스 HTTP 클라이언트 설정
 *
 * <h2>RestTemplate 목록</h2>
 * <pre>
 * provisioningRestTemplate → 기관 프로비저닝 HTTP POST (ProvisioningRelayJob)
 * webhookRestTemplate      → 기관 Webhook HTTPS POST (WebhookRelayJob)
 * </pre>
 *
 * <h2>설계 선택 — Apache HttpClient 5 (HC5)</h2>
 * Spring Boot 3.x 기본 SimpleClientHttpRequestFactory는:
 * - 커넥션 풀 없음 → 기관별 동시 연결 수 폭증 위험
 * - Keep-Alive 미지원 → 기관 서버 연결 오버헤드
 * HC5 PoolingHttpClientConnectionManager로 기관별 최대 연결 수 제어.
 *
 * <h2>타임아웃 설정</h2>
 * <pre>
 * 연결 타임아웃: 3s (기관 서버 연결 거부/방화벽 차단 빠른 감지)
 * 읽기 타임아웃: 8s (기관 서버 처리 응답 대기 — SLA 기준 5s + 여유)
 * </pre>
 *
 * <h2>mTLS TODO</h2>
 * MTLS 기관을 위한 클라이언트 인증서 장착 RestTemplate 추가 필요.
 * ido 서비스의 {@code ProvisioningRestTemplateConfig.mtlsProvisioningRestTemplate()} 참조.
 */
@Slf4j
@Configuration
public class BatchRestTemplateConfig {

    @Value("${batch.http.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${batch.http.read-timeout-ms:8000}")
    private int readTimeoutMs;

    @Value("${batch.http.max-connections-total:100}")
    private int maxConnectionsTotal;

    @Value("${batch.http.max-connections-per-route:20}")
    private int maxConnectionsPerRoute;

    /**
     * 프로비저닝 전용 RestTemplate
     *
     * <p>기관 프로비저닝 엔드포인트는 HTTPS + 기관별 인증헤더.
     * 커넥션 풀: 최대 100개 (기관 수 최대 68개 × 여유분).
     */
    @Bean(name = "provisioningRestTemplate")
    public RestTemplate provisioningRestTemplate() {
        log.info("[BatchRestTemplate] provisioningRestTemplate 초기화 (connectTimeout={}ms readTimeout={}ms)",
                connectTimeoutMs, readTimeoutMs);
        return new RestTemplate(httpRequestFactory("provisioning"));
    }

    /**
     * Webhook 전용 RestTemplate
     *
     * <p>기관 Webhook 엔드포인트 HTTPS POST.
     * 동일한 HC5 풀 설정 — 향후 용도별 분리 가능.
     */
    @Bean(name = "webhookRestTemplate")
    public RestTemplate webhookRestTemplate() {
        log.info("[BatchRestTemplate] webhookRestTemplate 초기화 (connectTimeout={}ms readTimeout={}ms)",
                connectTimeoutMs, readTimeoutMs);
        return new RestTemplate(httpRequestFactory("webhook"));
    }

    private HttpComponentsClientHttpRequestFactory httpRequestFactory(String name) {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(maxConnectionsTotal);
        cm.setDefaultMaxPerRoute(maxConnectionsPerRoute);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(requestConfig)
                .build();

        log.debug("[BatchRestTemplate] {} HttpClient 초기화 (maxTotal={} maxPerRoute={})",
                name, maxConnectionsTotal, maxConnectionsPerRoute);
        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }
}

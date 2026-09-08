package io.github.hipstermin.idem.relay.config;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.ssl.SSLContextBuilder;
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
 * provisioningRestTemplate     → 기관 프로비저닝 HTTP POST — API_KEY/HMAC/NONE 기관 (일반 TLS)
 * mtlsProvisioningRestTemplate → 기관 프로비저닝 HTTP POST — mTLS 클라이언트 인증서 기관
 * webhookRestTemplate          → 기관 Webhook HTTPS POST (WebhookRelayJob, HMAC-SHA256 인증)
 * </pre>
 *
 * <h2>설계 선택 — Apache HttpClient 5 (HC5)</h2>
 * Spring Boot 3.x 기본 SimpleClientHttpRequestFactory는:
 * <ul>
 *   <li>커넥션 풀 없음 → 기관별 동시 연결 수 폭증 위험</li>
 *   <li>Keep-Alive 미지원 → 기관 서버 연결 오버헤드</li>
 * </ul>
 * HC5 {@link PoolingHttpClientConnectionManager}로 기관별 최대 연결 수 제어.
 *
 * <h2>타임아웃 설정</h2>
 * <pre>
 * 연결 타임아웃: 3s — 기관 서버 연결 거부/방화벽 차단 빠른 감지
 * 읽기 타임아웃: 8s — 기관 서버 처리 응답 대기 (SLA 기준 5s + 여유)
 * </pre>
 *
 * <h2>mTLS 구현 원리</h2>
 * PKCS12 KeyStore를 Base64로 인코딩하여 K8s Secret에 저장.
 * {@link SSLContextBuilder#loadKeyMaterial}로 클라이언트 인증서를 SSLContext에 등록.
 * HC5 5.x 권장 API인 {@link ClientTlsStrategyBuilder}로 TLS 전략을 구성하고
 * {@link PoolingHttpClientConnectionManagerBuilder#setTlsSocketStrategy}에 적용.
 *
 * <h2>K8s Secret 등록 (운영 필수)</h2>
 * <pre>
 * kubectl create secret generic batch-mtls-cert \
 *   --from-literal=BATCH_MTLS_KEYSTORE_BASE64=$(base64 -w0 /path/to/client.p12) \
 *   --from-literal=BATCH_MTLS_KEYSTORE_PASS=&lt;keystore-password&gt; \
 *   -n production
 *
 * # deployment.yml envFrom 추가
 * envFrom:
 *   - secretRef:
 *       name: batch-mtls-cert
 *       optional: true
 * </pre>
 *
 * <h2>mTLS Fallback 정책</h2>
 * KeyStore 환경변수 미설정 시 {@code provisioningRestTemplate}(일반 TLS)로 Fallback.
 * WARN 로그 발생 — MTLS 기관 서버가 클라이언트 인증서 요구 시 TLS 핸드셰이크 실패.
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

    // mTLS 설정 — K8s Secret envFrom으로 주입 (application.yml → 환경변수 바인딩)
    @Value("${batch.http.mtls.keystore-base64:}")
    private String mtlsKeystoreBase64;

    @Value("${batch.http.mtls.keystore-password:}")
    private String mtlsKeystorePassword;

    @Value("${batch.http.mtls.keystore-type:PKCS12}")
    private String mtlsKeystoreType;

    // ─────────────────────────────────────────────────────────────────────────
    // Bean 선언
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 프로비저닝 전용 RestTemplate (API_KEY / HMAC / NONE 기관, 일반 TLS)
     *
     * <p>기관 프로비저닝 엔드포인트 HTTPS POST.
     * 커넥션 풀: 최대 100개 (기관 수 최대 68개 × 여유분).
     */
    @Bean(name = "provisioningRestTemplate")
    public RestTemplate provisioningRestTemplate() {
        log.info("[BatchRestTemplate] provisioningRestTemplate 초기화 " +
                 "(connectTimeout={}ms readTimeout={}ms maxTotal={} maxPerRoute={})",
                connectTimeoutMs, readTimeoutMs, maxConnectionsTotal, maxConnectionsPerRoute);
        return createRestTemplate("provisioning", buildPlainConnectionManager());
    }

    /**
     * mTLS 프로비저닝 전용 RestTemplate (MTLS 기관 — 클라이언트 인증서 TLS)
     *
     * <p>PKCS12 KeyStore로 초기화된 SSLContext를 HC5 {@link ClientTlsStrategyBuilder}에 적용.
     * TLS 핸드셰이크 중 서버가 클라이언트 인증서를 요청하면 KeyStore의 인증서로 자동 응답.
     *
     * <p><b>운영 필수 체크리스트:</b>
     * <ol>
     *   <li>K8s Secret {@code batch-mtls-cert} 생성 및 deployment envFrom 마운트 확인</li>
     *   <li>PKCS12 KeyStore에 클라이언트 인증서 + 개인키 포함 여부 확인</li>
     *   <li>기관 서버 Root CA가 private CA인 경우 JVM trustStore 또는 별도 trustStore 추가</li>
     *   <li>인증서 만료일 Prometheus AlertManager 알림 등록 (만료 30일 전 WARN)</li>
     * </ol>
     *
     * <p><b>Fallback 동작:</b>
     * {@code BATCH_MTLS_KEYSTORE_BASE64} 환경변수 미설정 시 일반 TLS Fallback.
     * MTLS 기관 요청 시 서버가 클라이언트 인증서를 요구하면 핸드셰이크 실패 가능.
     */
    @Bean(name = "mtlsProvisioningRestTemplate")
    public RestTemplate mtlsProvisioningRestTemplate() {
        if (mtlsKeystoreBase64 == null || mtlsKeystoreBase64.isBlank()) {
            log.warn("[BatchRestTemplate] ⚠️  MTLS KeyStore 미설정 " +
                     "(BATCH_MTLS_KEYSTORE_BASE64 환경변수 없음) " +
                     "→ 일반 TLS Fallback. MTLS 기관 있으면 운영 배포 전 K8s Secret 등록 필요.");
            return createRestTemplate("mtls-fallback", buildPlainConnectionManager());
        }

        try {
            SSLContext sslContext = buildMtlsSslContext();
            PoolingHttpClientConnectionManager cm = buildMtlsConnectionManager(sslContext);
            log.info("[BatchRestTemplate] mtlsProvisioningRestTemplate 초기화 완료 " +
                     "(keystoreType={} connectTimeout={}ms readTimeout={}ms)",
                    mtlsKeystoreType, connectTimeoutMs, readTimeoutMs);
            return createRestTemplate("mtls", cm);

        } catch (Exception e) {
            log.error("[BatchRestTemplate] ❌ mTLS SSLContext 초기화 실패 " +
                      "[{}]: {} → 일반 TLS Fallback.",
                    e.getClass().getSimpleName(), e.getMessage(), e);
            return createRestTemplate("mtls-fallback-error", buildPlainConnectionManager());
        }
    }

    /**
     * Webhook 전용 RestTemplate
     *
     * <p>기관 Webhook 엔드포인트 HTTPS POST.
     * Webhook 인증은 HMAC-SHA256 서명 헤더로 처리 — mTLS 미사용.
     */
    @Bean(name = "webhookRestTemplate")
    public RestTemplate webhookRestTemplate() {
        log.info("[BatchRestTemplate] webhookRestTemplate 초기화 " +
                 "(connectTimeout={}ms readTimeout={}ms)",
                connectTimeoutMs, readTimeoutMs);
        return createRestTemplate("webhook", buildPlainConnectionManager());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 내부 구현 — mTLS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * mTLS SSLContext 빌드 — Base64 PKCS12 → SSLContext
     *
     * <p>KeyStore 처리 순서:
     * <ol>
     *   <li>Base64 디코딩 → PKCS12 바이트 배열</li>
     *   <li>{@link KeyStore#load(java.io.InputStream, char[])} → KeyStore 로드</li>
     *   <li>{@link SSLContextBuilder#loadKeyMaterial} → 클라이언트 인증서 등록</li>
     *   <li>{@link SSLContextBuilder#build()} → SSLContext 완성</li>
     * </ol>
     *
     * @return 클라이언트 인증서가 등록된 {@link SSLContext}
     * @throws Exception KeyStore 파싱 실패, 인증서 오류, 비밀번호 불일치 등
     */
    private SSLContext buildMtlsSslContext() throws Exception {
        byte[] keystoreBytes = Base64.getDecoder().decode(mtlsKeystoreBase64.trim());
        char[] keystorePass  = mtlsKeystorePassword.toCharArray();

        KeyStore keyStore = KeyStore.getInstance(mtlsKeystoreType);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(keystoreBytes)) {
            keyStore.load(bais, keystorePass);
        }

        log.debug("[BatchRestTemplate] mTLS KeyStore 로드 완료: aliases={}",
                Collections.list(keyStore.aliases()));

        return SSLContextBuilder.create()
                .loadKeyMaterial(keyStore, keystorePass)
                .build();
    }

    /**
     * mTLS 전용 커넥션 매니저 — HC5 5.x 권장 API ({@link ClientTlsStrategyBuilder})
     *
     * <p>HC5 5.x부터 {@code SSLConnectionSocketFactory}는 deprecated.
     * {@link ClientTlsStrategyBuilder#buildClassic()}으로 Blocking I/O 전용
     * {@code TlsSocketStrategy}를 빌드하고 {@link PoolingHttpClientConnectionManagerBuilder}에 주입.
     *
     * <p>{@code build()}는 NIO(Async)용 {@code TlsStrategy}를 반환하므로
     * classic(Blocking) HttpClient에는 {@code buildClassic()}을 사용해야 함.
     */
    private PoolingHttpClientConnectionManager buildMtlsConnectionManager(SSLContext sslContext) {
        // buildClassic() → TlsSocketStrategy (Blocking I/O용, PoolingHttpClientConnectionManager 호환)
        var tlsSocketStrategy = ClientTlsStrategyBuilder.create()
                .setSslContext(sslContext)
                .buildClassic();

        PoolingHttpClientConnectionManager cm = PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy(tlsSocketStrategy)
                .setMaxConnTotal(maxConnectionsTotal)
                .setMaxConnPerRoute(maxConnectionsPerRoute)
                .build();

        log.debug("[BatchRestTemplate] mTLS ConnectionManager 빌드 완료 " +
                  "(maxTotal={} maxPerRoute={})", maxConnectionsTotal, maxConnectionsPerRoute);
        return cm;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 내부 구현 — 공통
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 일반 TLS 커넥션 매니저 (API_KEY / HMAC / NONE / Webhook)
     */
    private PoolingHttpClientConnectionManager buildPlainConnectionManager() {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(maxConnectionsTotal);
        cm.setDefaultMaxPerRoute(maxConnectionsPerRoute);
        return cm;
    }

    /**
     * HC5 기반 RestTemplate 생성 공통 메서드
     *
     * <p>공통 설정:
     * <ul>
     *   <li>만료 커넥션 자동 제거 ({@code evictExpiredConnections})</li>
     *   <li>60초 이상 유휴 커넥션 제거 ({@code evictIdleConnections})</li>
     * </ul>
     *
     * @param name 로그 식별자
     * @param cm   커넥션 매니저 (일반 TLS 또는 mTLS)
     * @return 설정 완료된 {@link RestTemplate}
     */
    private RestTemplate createRestTemplate(String name, PoolingHttpClientConnectionManager cm) {
        // HC5 5.5.x: setConnectTimeout(Timeout)은 deprecated → ConnectionConfig 사용
        // ConnectionConfig: 연결 타임아웃(connectTimeout) + 소켓 읽기 타임아웃(socketTimeout)
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .setSocketTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .build();
        cm.setDefaultConnectionConfig(connectionConfig);

        // RequestConfig: 커넥션 풀 대기 타임아웃 (setConnectionRequestTimeout은 non-deprecated)
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .setResponseTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(requestConfig)
                .evictExpiredConnections()
                .evictIdleConnections(Timeout.ofSeconds(60))
                .build();

        log.debug("[BatchRestTemplate] {} HttpClient 초기화 완료 " +
                  "(maxTotal={} maxPerRoute={})", name, maxConnectionsTotal, maxConnectionsPerRoute);

        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }
}

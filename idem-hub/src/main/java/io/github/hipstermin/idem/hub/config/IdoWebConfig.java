package io.github.hipstermin.idem.hub.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hipstermin.idem.hub.provision.AgencyCredentialStore;
import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;
import javax.net.ssl.SSLContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

/**
 * IdO Web / HTTP 클라이언트 + 캐시 설정 (문서 §7.4)
 *
 * <p>Keycloak JWKS 공개키 캐시(keycloakJwks)를 포함한
 * RestTemplate, ObjectMapper Bean 등록.
 *
 * <p><b>RestTemplate 목록</b>:
 * <ul>
 *   <li>{@code restTemplate}        — Q-Sign / Keycloak 내부 HTTP 통신</li>
 *   <li>{@code qimRestTemplate}     — Q-IM 조회 전용 (3s/5s 타임아웃)</li>
 *   <li>{@code webhookRestTemplate} — 기관 외부 HTTPS webhook 발송 전용 (3s/8s 타임아웃)</li>
 * </ul>
 *
 * <p><b>webhookRestTemplate 설계</b>:
 * 기관 외부 서버는 응답이 느릴 수 있어 read-timeout을 8s로 설정.
 * 60,000명 급증 시 webhook 발송 지연이 생겨도 내부 서비스 영향 최소화.
 * WebhookDispatchOutboxRelay가 이 빈을 {@code @Qualifier("webhookRestTemplate")}로 주입받아 사용.
 */
@Slf4j
@Configuration
@EnableScheduling
public class IdoWebConfig {

    @Value("${ido.qsign.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${ido.qsign.read-timeout-ms:5000}")
    private int readTimeoutMs;

    @Value("${ido.qim.connect-timeout-ms:3000}")
    private int qimConnectTimeoutMs;

    @Value("${ido.qim.read-timeout-ms:5000}")
    private int qimReadTimeoutMs;

    @Value("${ido.webhook.connect-timeout-ms:3000}")
    private int webhookConnectTimeoutMs;

    @Value("${ido.webhook.read-timeout-ms:8000}")
    private int webhookReadTimeoutMs;

    @Value("${ido.q-authz.connect-timeout-ms:3000}")
    private int qAuthzConnectTimeoutMs;

    @Value("${ido.q-authz.read-timeout-ms:5000}")
    private int qAuthzReadTimeoutMs;

    // mTLS 프로비저닝 RestTemplate 설정
    @Value("${ido.provisioning.mtls.connect-timeout-ms:5000}")
    private int mtlsConnectTimeoutMs;

    @Value("${ido.provisioning.mtls.read-timeout-ms:10000}")
    private int mtlsReadTimeoutMs;

    /**
     * mTLS 기관 authCredentialRef 접두사.
     * 기관 엔드포인트의 {@code authCredentialRef} 시작 패턴으로 KeyStore 조회.
     * 기본값: {@code "secrets/agency"} — 모든 기관 mTLS 인증서를 공통 ClientKeyStore에서 관리하는 경우.
     *
     * <p>기관별 독립 인증서 지원이 필요한 경우 {@code selectRestTemplate()} 패턴을
     * 기관코드별로 확장하여 복수 mTLS 빈을 등록하면 된다.
     */
    @Value("${ido.provisioning.mtls.keystore-credential-ref:secrets/agency/common/mtls-keystore}")
    private String mtlsKeystoreCredentialRef;

    // ── HTTP 클라이언트 ────────────────────────────────────────────────────

    /**
     * 공통 RestTemplate — Q-Sign, Keycloak 내부 HTTP 통신
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return new RestTemplate(factory);
    }

    /**
     * Q-IM 전용 RestTemplate (§11.5.4 QimClientImpl 의존)
     */
    @Bean
    public RestTemplate qimRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(qimConnectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(qimReadTimeoutMs));
        return new RestTemplate(factory);
    }

    /**
     * Q-Authz 전용 RestTemplate (연합 인가 역할 조회 — QAuthzClient 의존)
     *
     * <p>토큰 발급 핫패스에서 호출되므로 짧은 타임아웃(3s/5s). 장애 시 QAuthzClient가
     * fail-open(빈 역할)으로 처리하여 SSO/Handoff 발급을 막지 않는다.
     */
    @Bean("qAuthzRestTemplate")
    public RestTemplate qAuthzRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(qAuthzConnectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(qAuthzReadTimeoutMs));
        return new RestTemplate(factory);
    }

    /**
     * 기관 webhook 발송 전용 RestTemplate
     *
     * <p><b>설계 근거</b>:
     * <ul>
     *   <li>connect-timeout 3s: 기관 서버 응답 여부 빠르게 판단</li>
     *   <li>read-timeout 8s: 기관 내부 처리 시간 여유 (내부 서비스보다 길게)</li>
     *   <li>별도 빈으로 분리: 기관 외부 호출이 내부 서비스 타임아웃에 영향 없도록</li>
     * </ul>
     *
     * <p>WebhookDispatchOutboxRelay가 {@code @Qualifier("webhookRestTemplate")}으로 주입받음.
     */
    @Bean("webhookRestTemplate")
    public RestTemplate webhookRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(webhookConnectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(webhookReadTimeoutMs));
        return new RestTemplate(factory);
    }

    /**
     * mTLS 프로비저닝 전용 RestTemplate (Sprint 17)
     *
     * <p>MTLS authType 기관에 HTTP POST 시 이 빈을 사용하여 TLS 핸드셰이크에서
     * 클라이언트 인증서를 제시한다.
     *
     * <h3>동작 원리</h3>
     * <ol>
     *   <li>{@link AgencyCredentialStore#findMtlsKeystoreBase64(String)}로 Base64 PKCS12 KeyStore 조회</li>
     *   <li>KeyStore 파싱 → {@link SSLContext} 생성 (Apache HttpClient 5)</li>
     *   <li>KeyStore 미등록 시 클라이언트 인증서 없는 일반 TLS RestTemplate 반환 (운영 중 오류 방지)</li>
     * </ol>
     *
     * <h3>K8s Secret 등록 예시</h3>
     * <pre>
     * kubectl create secret generic ido-agency-credentials \
     *   --from-literal=SECRETS_AGENCY_COMMON_MTLS_KEYSTORE_BASE64=$(base64 -w0 client.p12) \
     *   --from-literal=SECRETS_AGENCY_COMMON_MTLS_KEYSTORE_PASS=changeit \
     *   -n production
     * </pre>
     *
     * <h3>의존 라이브러리</h3>
     * <pre>
     * // build.gradle에 추가 필요 (Apache HttpClient 5 — Spring Boot 3.x 기본 미포함)
     * implementation 'org.apache.httpcomponents.client5:httpclient5'
     * </pre>
     *
     * @param credentialStore 기관 자격증명 저장소 (K8s Secret 환경변수 조회)
     * @return mTLS 클라이언트 인증서 장착 RestTemplate
     */
    @Bean("mtlsProvisioningRestTemplate")
    public RestTemplate mtlsProvisioningRestTemplate(AgencyCredentialStore credentialStore) {
        String keystoreBase64 = credentialStore.findMtlsKeystoreBase64(mtlsKeystoreCredentialRef);
        String keystorePass   = credentialStore.findMtlsKeystorePassword(mtlsKeystoreCredentialRef);

        if (keystoreBase64 == null || keystoreBase64.isBlank()) {
            // ── 개발 환경 / KeyStore 미등록 ───────────────────────────────────
            // 클라이언트 인증서 없이 기본 TLS 연결 (MTLS 기관은 실제 연결 시 서버가 거부)
            // F-20=false이므로 운영 전에 등록하면 되며, 기동 자체는 허용
            log.warn("[IdoWebConfig] mTLS KeyStore 미등록 (authCredentialRef={}). " +
                     "클라이언트 인증서 없이 기본 TLS RestTemplate 사용. " +
                     "F-20 활성화 전에 K8s Secret 'ido-agency-credentials'에 " +
                     "SECRETS_AGENCY_COMMON_MTLS_KEYSTORE_BASE64 를 등록하세요.",
                     mtlsKeystoreCredentialRef);
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofMillis(mtlsConnectTimeoutMs));
            factory.setReadTimeout(Duration.ofMillis(mtlsReadTimeoutMs));
            return new RestTemplate(factory);
        }

        try {
            // ── PKCS12 KeyStore 로드 ──────────────────────────────────────────
            byte[]    keystoreBytes = Base64.getDecoder().decode(keystoreBase64.trim());
            KeyStore  keyStore      = KeyStore.getInstance("PKCS12");
            char[]    passChars     = keystorePass.toCharArray();
            keyStore.load(new ByteArrayInputStream(keystoreBytes), passChars);

            // ── SSLContext 생성 (클라이언트 인증서 + 기본 TrustStore) ──────────
            SSLContext sslContext = SSLContextBuilder.create()
                    .loadKeyMaterial(keyStore, passChars)
                    .build();

            // ── Apache HttpClient 5 + PoolingConnectionManager ───────────────
            // timeout은 HttpClient RequestConfig에서 직접 설정
            // (HttpComponentsClientHttpRequestFactory.setConnectTimeout(Duration)은
            //  Spring 6.2에서 deprecated(forRemoval=true) 처리됨)
            var requestConfig = RequestConfig.custom()
                    .setConnectTimeout(Timeout.ofMilliseconds(mtlsConnectTimeoutMs))
                    .setConnectionRequestTimeout(Timeout.ofMilliseconds(mtlsConnectTimeoutMs))
                    .setResponseTimeout(Timeout.ofMilliseconds(mtlsReadTimeoutMs))
                    .build();
            var sslSocketFactory = SSLConnectionSocketFactoryBuilder.create()
                    .setSslContext(sslContext)
                    .build();
            var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                    .setSSLSocketFactory(sslSocketFactory)
                    .build();
            var httpClient = HttpClients.custom()
                    .setConnectionManager(connectionManager)
                    .setDefaultRequestConfig(requestConfig)
                    .build();

            HttpComponentsClientHttpRequestFactory factory =
                    new HttpComponentsClientHttpRequestFactory(httpClient);

            log.info("[IdoWebConfig] mTLS RestTemplate 초기화 완료 — 클라이언트 인증서 장착. " +
                     "connectTimeout={}ms readTimeout={}ms",
                     mtlsConnectTimeoutMs, mtlsReadTimeoutMs);
            return new RestTemplate(factory);

        } catch (Exception e) {
            // KeyStore 파싱 실패 시 기본 TLS 폴백 (운영 기동 차단 방지)
            log.error("[IdoWebConfig] mTLS KeyStore 파싱 실패 — 기본 TLS RestTemplate 폴백. error={}. " +
                      "PKCS12 형식과 Base64 인코딩을 확인하세요.", e.getMessage());
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofMillis(mtlsConnectTimeoutMs));
            factory.setReadTimeout(Duration.ofMillis(mtlsReadTimeoutMs));
            return new RestTemplate(factory);
        }
    }

    // ── JSON 직렬화 ───────────────────────────────────────────────────────

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

}

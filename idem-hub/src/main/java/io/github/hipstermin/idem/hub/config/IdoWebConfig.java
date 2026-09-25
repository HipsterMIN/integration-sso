package io.github.hipstermin.idem.hub.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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

    @Value("${idem.hub.gate.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${idem.hub.gate.read-timeout-ms:5000}")
    private int readTimeoutMs;

    @Value("${idem.hub.registry.connect-timeout-ms:3000}")
    private int qimConnectTimeoutMs;

    @Value("${idem.hub.registry.read-timeout-ms:5000}")
    private int qimReadTimeoutMs;

    @Value("${idem.hub.webhook.connect-timeout-ms:3000}")
    private int webhookConnectTimeoutMs;

    @Value("${idem.hub.webhook.read-timeout-ms:8000}")
    private int webhookReadTimeoutMs;

    @Value("${idem.hub.authz.connect-timeout-ms:3000}")
    private int qAuthzConnectTimeoutMs;

    @Value("${idem.hub.authz.read-timeout-ms:5000}")
    private int qAuthzReadTimeoutMs;

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

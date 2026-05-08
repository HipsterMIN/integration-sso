package kr.go.smes.ido.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
 * RestTemplate, ObjectMapper, CacheManager Bean 등록.
 *
 * <p>캐시 전략:
 * <ul>
 *   <li>{@code keycloakJwks} : Keycloak JWKS 공개키 — kid 기반 캐시 (TTL 1시간)</li>
 *   <li>ConcurrentMapCacheManager 사용 — TTL 지원을 위해 Caffeine 권장 (실운영 시 교체)</li>
 * </ul>
 */
@Configuration
@EnableScheduling
public class IdoWebConfig {

    @Value("${ido.qsign.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${ido.qsign.read-timeout-ms:5000}")
    private int readTimeoutMs;

    // ── HTTP 클라이언트 ────────────────────────────────────────────────────

    @Value("${ido.qim.connect-timeout-ms:3000}")
    private int qimConnectTimeoutMs;

    @Value("${ido.qim.read-timeout-ms:5000}")
    private int qimReadTimeoutMs;

    /**
     * 공통 RestTemplate — q-sign, Keycloak 내부 HTTP 통신
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    /**
     * Q-IM 전용 RestTemplate (§11.5.4 QimClientImpl 의존)
     * - 별도 타임아웃 설정 (connect 3s / read 5s)
     */
    @Bean
    public RestTemplate qimRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(qimConnectTimeoutMs);
        factory.setReadTimeout(qimReadTimeoutMs);
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

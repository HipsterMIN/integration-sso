package kr.go.smes.qsign.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Q-Sign Web / RestTemplate / Cache 설정
 *
 * <p>Keycloak 어댑터 연동에 필요한:
 * <ul>
 *   <li>RestTemplate — Keycloak token endpoint, JWKS 호출</li>
 *   <li>CacheManager (Redis) — Keycloak JWKS 공개키 캐싱 (keycloakJwks, TTL 3600초)</li>
 *   <li>ObjectMapper — JSON 직렬화</li>
 * </ul>
 *
 * <p><b>설계 원칙</b>: q-sign 은 Keycloak 과만 통신한다.
 * 카카오·네이버 등 외부 IdP 직접 연결은 금지.
 */
@Configuration
@EnableCaching
public class QSignWebConfig implements WebMvcConfigurer {

    @Value("${qsign.keycloak.http.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${qsign.keycloak.http.read-timeout-ms:5000}")
    private int readTimeoutMs;

    @Value("${qsign.keycloak.jwks-cache-ttl-seconds:3600}")
    private long jwksCacheTtlSeconds;

    // ── RestTemplate ────────────────────────────────────────────────────

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    // ── ObjectMapper ────────────────────────────────────────────────────

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    // ── Redis CacheManager (JWKS 캐시) ──────────────────────────────────

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(jwksCacheTtlSeconds))
                .disableCachingNullValues()
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new GenericJackson2JsonRedisSerializer()));

        // keycloakJwks 캐시: Keycloak JWKS 공개키를 1시간 캐싱
        // KeycloakJwksVerifier.fetchPublicKey() 의 @Cacheable(value="keycloakJwks") 와 일치해야 함
        RedisCacheConfiguration jwksConfig = defaultConfig
                .entryTtl(Duration.ofSeconds(jwksCacheTtlSeconds));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withCacheConfiguration("keycloakJwks", jwksConfig)
                .build();
    }
}

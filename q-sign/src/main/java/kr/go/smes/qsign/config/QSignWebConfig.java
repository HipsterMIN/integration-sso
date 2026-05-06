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
 * <p>카카오 OIDC 연동에 필요한:
 * <ul>
 *   <li>RestTemplate — token endpoint, JWKS 호출</li>
 *   <li>CacheManager (Redis) — JWKS 공개키 캐싱 (kakaoJwks)</li>
 *   <li>ObjectMapper — JSON 직렬화</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class QSignWebConfig implements WebMvcConfigurer {

    @Value("${qsign.oidc.http.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${qsign.oidc.http.read-timeout-ms:5000}")
    private int readTimeoutMs;

    @Value("${qsign.oidc.jwks-cache-ttl-seconds:3600}")
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

        // kakaoJwks 캐시: JWKS 공개키를 1시간 캐싱 (카카오 JWKS 는 변경 빈도 낮음)
        RedisCacheConfiguration jwksConfig = defaultConfig
                .entryTtl(Duration.ofSeconds(jwksCacheTtlSeconds));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withCacheConfiguration("kakaoJwks", jwksConfig)
                .build();
    }
}

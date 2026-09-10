package io.github.hipstermin.idem.registry.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Q-IM Redis 설정
 * 설계서 §11.3 캐시 TTL — 사용자 캐시 ≤5분 (300초)
 *
 * <p>캐시 키 네임스페이스: qim:user:{qimUserId}
 * <p>TTL: 300초 (IdO pull 기준 최신성 보장)
 *
 * [참고] Q-IM DB는 NHN Cloud RDS for MariaDB
 *        Redis는 공용 인프라 (onepass-redis) 사용
 */
@EnableCaching
@Configuration
public class RedisConfig {

    /**
     * Q-IM 전용 RedisTemplate
     * — key: String, value: JSON (ObjectMapper with JavaTimeModule)
     */
    @Bean("qimRedisTemplate")
    public RedisTemplate<String, Object> qimRedisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(qimObjectMapper());
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Spring Cache 추상화 — @Cacheable, @CacheEvict 지원
     * 기본 TTL: 300초 (설계서 §11.3)
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(300))
                .disableCachingNullValues()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer(qimObjectMapper())));

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaultConfig)
                .build();
    }

    private ObjectMapper qimObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}

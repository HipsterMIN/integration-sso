package io.github.hipstermin.idem.hub.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hipstermin.idem.hub.broker.provider.ProviderConfig;
import java.time.Duration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * IdO Redis 설정
 * 설계서 §11.5 IdO 캐시 설계
 *  - Q-IM 사용자 상태 캐시 TTL ≤ 5분
 *  - 기관 메타·정책 캐시 TTL ≤ 60분
 *  - Handoff Ticket 저장 TTL 60초 (1회성)
 *  - keycloakJwks : Keycloak JWKS RSA 공개키 TTL 60분 (§7.4)
 */
@EnableCaching
@Configuration
public class RedisConfig {

    /**
     * 범용 RedisTemplate (String key, Object value)
     * UserStatusCache / LastEventVersionStore / TicketRepository 에서 사용
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(om);

        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jsonSerializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * CacheManager — Spring @Cacheable 지원
     *  - qimUserStatus : TTL 5분 (§11.5.1)
     *  - agencyMeta    : TTL 60분 (§11.5.1)
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        RedisCacheConfiguration defaultCfg = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new GenericJackson2JsonRedisSerializer(om)))
                .disableCachingNullValues()
                .entryTtl(Duration.ofMinutes(5));

        RedisCacheConfiguration agencyMetaCfg = defaultCfg
                .entryTtl(Duration.ofMinutes(60));

        RedisCacheConfiguration keycloakJwksCfg = defaultCfg
                .entryTtl(Duration.ofMinutes(60));

        // provider_config 캐시 TTL 60분 (ProviderConfigRepository @Cacheable)
        // 1.1.4 G2-10: 값 타입을 지정해 복원한다 — 위 범용 직렬화기는 타입 정보(@class) 없이 쓰므로 적중 시 LinkedHashMap 이 돌아와
        // ProviderConfig 로 캐스트할 수 없었다(브로커 인증 시작 500). 이 캐시의 값은 ProviderConfig 뿐이다(빈 결과는 unless 로 제외)
        RedisCacheConfiguration providerConfigCfg = defaultCfg
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new Jackson2JsonRedisSerializer<>(om, ProviderConfig.class)))
                .entryTtl(Duration.ofMinutes(60));

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaultCfg)
                .withCacheConfiguration("qimUserStatus",  defaultCfg)
                .withCacheConfiguration("agencyMeta",     agencyMetaCfg)
                .withCacheConfiguration("keycloakJwks",   keycloakJwksCfg)
                .withCacheConfiguration("provider-config", providerConfigCfg)
                .build();
    }
}

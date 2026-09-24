package io.github.hipstermin.idem.gate.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * gate 의 {@code RedisTemplate<String, Object>} (S6 점검에서 발견한 기동 결함 수정).
 *
 * <p>{@code PkceService} 가 이 타입을 요구하지만 Boot 기본 빈은 {@code RedisTemplate<Object, Object>} 라 제네릭이
 * 맞지 않아 컨텍스트가 뜨지 않았다(설치본의 gate 는 기동하지 못했다). 키는 문자열, 값은 JSON 으로 둔다.
 */
@Configuration
public class GateRedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        StringRedisSerializer keys = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer values = new GenericJackson2JsonRedisSerializer();
        template.setKeySerializer(keys);
        template.setHashKeySerializer(keys);
        template.setValueSerializer(values);
        template.setHashValueSerializer(values);
        template.afterPropertiesSet();
        return template;
    }
}

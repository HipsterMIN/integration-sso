package io.github.hipstermin.idem.hub.infrastructure;

import io.github.hipstermin.idem.common.domain.UserStatus;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Q-IM 사용자 상태 Redis 캐시 구현체
 * 설계서 §11.5 / §11.5.1 참조
 *
 * <p>TTL ≤ 5분 (성능 최적화 목적, 정본 아님)
 * <p>Q-IM 변경 이벤트(UserEvent) 수신 시 즉시 무효화
 * <p>캐시 키: "ido:user_status:{qimUserId}"
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserStatusCacheImpl implements UserStatusCache {

    private static final String KEY_PREFIX = "ido:user_status:";

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${ido.qim.cache-ttl-seconds:300}")
    private long cacheTtlSeconds;

    @Override
    public Optional<UserStatus> get(String qimUserId) {
        String key = KEY_PREFIX + qimUserId;
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                log.debug("[UserStatusCache] MISS: qimUserId={}", qimUserId);
                return Optional.empty();
            }
            UserStatus status = UserStatus.valueOf(value.toString());
            log.debug("[UserStatusCache] HIT: qimUserId={} status={}", qimUserId, status);
            return Optional.of(status);
        } catch (Exception e) {
            log.warn("[UserStatusCache] Redis 조회 실패 — cache miss 처리: qimUserId={} error={}",
                    qimUserId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String qimUserId, UserStatus status) {
        String key = KEY_PREFIX + qimUserId;
        try {
            redisTemplate.opsForValue().set(key, status.name(), Duration.ofSeconds(cacheTtlSeconds));
            log.debug("[UserStatusCache] PUT: qimUserId={} status={} ttl={}s",
                    qimUserId, status, cacheTtlSeconds);
        } catch (Exception e) {
            // 캐시 쓰기 실패는 무시 (정본 아님)
            log.warn("[UserStatusCache] Redis 저장 실패 — 무시: qimUserId={} error={}",
                    qimUserId, e.getMessage());
        }
    }

    @Override
    public void invalidate(String qimUserId) {
        String key = KEY_PREFIX + qimUserId;
        try {
            Boolean deleted = redisTemplate.delete(key);
            log.debug("[UserStatusCache] INVALIDATE: qimUserId={} deleted={}", qimUserId, deleted);
        } catch (Exception e) {
            log.warn("[UserStatusCache] Redis 삭제 실패: qimUserId={} error={}",
                    qimUserId, e.getMessage());
        }
    }
}

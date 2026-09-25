package io.github.hipstermin.idem.hub.infrastructure;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 사용자별 마지막 처리된 이벤트 버전 Redis 저장소 구현체
 * 설계서 §11.5.3 — optimistic-lock 기반 순서 역전 방지
 *
 * <p>캐시 키: 호출부에서 "CONSUMER_GROUP:qimUserId" 형태로 전달
 * <p>TTL 30일 (이벤트 버전 추적 목적)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LastEventVersionStoreImpl implements LastEventVersionStore {

    private static final String KEY_PREFIX = "idem:event_version:";
    private static final Duration VERSION_TTL = Duration.ofDays(30);

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public Long get(String key) {
        String redisKey = KEY_PREFIX + key;
        try {
            Object value = redisTemplate.opsForValue().get(redisKey);
            if (value == null) {
                return null;
            }
            return Long.parseLong(value.toString());
        } catch (Exception e) {
            log.warn("[LastEventVersionStore] Redis 조회 실패: key={} error={}", key, e.getMessage());
            return null;
        }
    }

    @Override
    public void put(String key, Long version) {
        String redisKey = KEY_PREFIX + key;
        try {
            redisTemplate.opsForValue().set(redisKey, version.toString(), VERSION_TTL);
            log.debug("[LastEventVersionStore] 버전 갱신: key={} version={}", key, version);
        } catch (Exception e) {
            log.warn("[LastEventVersionStore] Redis 저장 실패: key={} error={}", key, e.getMessage());
        }
    }
}

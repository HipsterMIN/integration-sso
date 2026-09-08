package io.github.hipstermin.idem.hub.gateway;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 게이트웨이 멱등성 키 저장소 — Redis SET NX 24시간
 *
 * <p>인바운드/아웃바운드 요청의 X-Idempotency-Key 중복 수신을 방어한다.
 *
 * <p>이중 방어 전략:
 * <ol>
 *   <li>1차: Redis SET NX (24h TTL) — 빠른 인메모리 중복 차단</li>
 *   <li>2차: DB UNIQUE 제약 (gateway_inbound_audit.idempotency_key) — Redis 장애 시 최종 방어</li>
 * </ol>
 *
 * <p>Redis 키 네임스페이스:
 * <pre>
 *   인바운드:   gateway:inbound:idempotent:{idempotencyKey}
 *   아웃바운드: gateway:outbound:idempotent:{idempotencyKey}:{agencyCode}
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayIdempotencyStore {

    private static final String INBOUND_KEY_PREFIX  = "gateway:inbound:idempotent:";
    private static final String OUTBOUND_KEY_PREFIX = "gateway:outbound:idempotent:";
    private static final String MARKER_VALUE        = "1";
    /** 24시간 TTL (X-Idempotency-Key 유효 기간 — 설계서 §15.4.4) */
    private static final Duration TTL_24H = Duration.ofHours(24);

    private final RedisTemplate<String, String> redisTemplate;

    // ─────────────────────────────────────────────────────────────────────
    // 인바운드
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 인바운드 멱등성 키 점유 시도
     *
     * @param idempotencyKey X-Idempotency-Key 헤더 값
     * @return true → 최초 수신 (처리 진행), false → 중복 (이미 처리됨)
     */
    public boolean tryAcquireInbound(String idempotencyKey) {
        String key = INBOUND_KEY_PREFIX + idempotencyKey;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, MARKER_VALUE, TTL_24H);
        boolean result = Boolean.TRUE.equals(acquired);
        if (!result) {
            log.debug("[GatewayIdempotency] 인바운드 중복 키 차단: idempotencyKey={}", idempotencyKey);
        }
        return result;
    }

    /**
     * 인바운드 멱등성 키 존재 여부 확인 (점유하지 않음)
     *
     * @param idempotencyKey 확인할 키
     * @return true → 이미 처리된 키
     */
    public boolean isInboundDuplicate(String idempotencyKey) {
        String key = INBOUND_KEY_PREFIX + idempotencyKey;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 인바운드 멱등성 키 수동 해제 (처리 실패 롤백용)
     * 정상 흐름에서는 사용하지 않음 — 예외 복구 전용
     *
     * @param idempotencyKey 해제할 키
     */
    public void releaseInbound(String idempotencyKey) {
        String key = INBOUND_KEY_PREFIX + idempotencyKey;
        redisTemplate.delete(key);
        log.warn("[GatewayIdempotency] 인바운드 키 수동 해제 (롤백): idempotencyKey={}", idempotencyKey);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 아웃바운드
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 아웃바운드 멱등성 키 점유 시도
     *
     * @param idempotencyKey 발송 멱등성 키 (UUID v7)
     * @param agencyCode     대상 기관 코드 (키 네임스페이스 분리)
     * @return true → 최초 발송 (진행), false → 중복 발송 방지
     */
    public boolean tryAcquireOutbound(String idempotencyKey, String agencyCode) {
        String key = OUTBOUND_KEY_PREFIX + idempotencyKey + ":" + agencyCode;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, MARKER_VALUE, TTL_24H);
        boolean result = Boolean.TRUE.equals(acquired);
        if (!result) {
            log.debug("[GatewayIdempotency] 아웃바운드 중복 차단: idempotencyKey={} agencyCode={}",
                    idempotencyKey, agencyCode);
        }
        return result;
    }
}

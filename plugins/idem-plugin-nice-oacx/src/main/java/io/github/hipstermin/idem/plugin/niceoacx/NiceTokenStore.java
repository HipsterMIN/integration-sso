package io.github.hipstermin.idem.plugin.niceoacx;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * NICE Access Token Redis 캐시 저장소
 *
 * <p>onepass-be의 인메모리 {@code AtomicReference} 방식을 Redis로 교체.
 * 다중 인스턴스(K8s Pod 복수) 환경에서 토큰을 공유하여 NICE API 불필요한 재발급을 방지.
 *
 * <p><b>교체 이유:</b>
 * onepass-be는 단일 인스턴스를 가정한 {@code AtomicReference<NiceTokenSnapshot>} 사용.
 * ido는 K8s 환경에서 여러 Pod가 동작하므로 각 Pod가 독립적으로 토큰을 발급하면
 * NICE 서버의 토큰 발급 제한에 걸릴 수 있음 → Redis 공유 캐시로 해결.
 *
 * <p><b>저장 전략:</b>
 * <ul>
 *   <li>Redis key: {@code nice:token} (단일 키, 항상 최신 토큰 유지)</li>
 *   <li>Redis TTL: NICE 토큰 만료 시각 기준 안전 마진(60초) 차감 후 설정</li>
 *   <li>토큰 유효성 검사: Redis TTL이 존재하면 유효 (만료 60초 전 재발급)</li>
 * </ul>
 *
 * <p><b>Redis 저장 형태:</b>
 * <pre>
 * Key:   nice:token:snapshot       → JSON 직렬화된 NiceTokenSnapshot
 * TTL:   NICE 만료시각 - 현재시각 - 60초 (음수면 즉시 만료)
 * </pre>
 *
 * <p><b>스레드 안전성:</b>
 * Redis 연산 자체는 원자적이나, {@code ensureAccessToken()}의 동기화는
 * {@code NiceAuthService}에서 {@code synchronized} 처리.
 *
 * @see NiceAuthService
 * @see NiceAuthSessionStore
 */
@Slf4j
@RequiredArgsConstructor
public class NiceTokenStore {

    /** Redis Key 프리픽스 */
    private static final String TOKEN_KEY = "nice:token:snapshot";

    /** Access Token 필드 키 */
    private static final String FIELD_ACCESS_TOKEN = "accessToken";

    /** 만료 시각 필드 키 (epoch millis) */
    private static final String FIELD_EXPIRES_IN = "expiresIn";

    /** ticket 필드 키 */
    private static final String FIELD_TICKET = "ticket";

    /** iterators 필드 키 */
    private static final String FIELD_ITERATORS = "iterators";

    /**
     * 만료 안전 마진 (밀리초)
     *
     * <p>NICE 토큰 실제 만료 60초 전에 재발급하여 만료 시점 경계에서의 API 오류 방지.
     */
    private static final long EXPIRY_SAFETY_MARGIN_MILLIS = 60_000L;

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * NICE Access Token이 현재 유효한지 확인
     *
     * <p>Redis에 저장된 토큰의 만료 시각을 확인.
     * 만료 60초 전부터 invalid로 판단하여 재발급 유도.
     *
     * @return true: 유효한 토큰 존재, false: 만료됐거나 없음
     */
    public boolean isValid() {
        String expiresInStr = redisTemplate.opsForHash()
                .get(TOKEN_KEY, FIELD_EXPIRES_IN) instanceof String s ? s : null;

        if (expiresInStr == null) {
            return false;
        }

        try {
            long expiresInEpochMillis = Long.parseLong(expiresInStr);
            return System.currentTimeMillis() < (expiresInEpochMillis - EXPIRY_SAFETY_MARGIN_MILLIS);
        } catch (NumberFormatException e) {
            log.warn("[NiceTokenStore] expiresIn 파싱 실패: {}", expiresInStr);
            return false;
        }
    }

    /**
     * 현재 저장된 NICE Token 스냅샷 조회
     *
     * <p>Redis에 저장된 토큰 정보를 {@link NiceTokenSnapshot}으로 변환하여 반환.
     * 저장된 토큰이 없거나 파싱 오류 시 null 반환.
     *
     * @return 현재 토큰 스냅샷 (없으면 null)
     */
    public NiceTokenSnapshot get() {
        Object accessToken = redisTemplate.opsForHash().get(TOKEN_KEY, FIELD_ACCESS_TOKEN);
        Object expiresIn = redisTemplate.opsForHash().get(TOKEN_KEY, FIELD_EXPIRES_IN);
        Object ticket = redisTemplate.opsForHash().get(TOKEN_KEY, FIELD_TICKET);
        Object iterators = redisTemplate.opsForHash().get(TOKEN_KEY, FIELD_ITERATORS);

        if (accessToken == null || expiresIn == null || ticket == null || iterators == null) {
            return null;
        }

        try {
            return new NiceTokenSnapshot(
                    accessToken.toString(),
                    Long.parseLong(expiresIn.toString()),
                    ticket.toString(),
                    Integer.parseInt(iterators.toString())
            );
        } catch (NumberFormatException e) {
            log.warn("[NiceTokenStore] 토큰 스냅샷 파싱 실패 — Redis 데이터 손상 의심", e);
            return null;
        }
    }

    /**
     * NICE Access Token 정보를 Redis에 저장
     *
     * <p>NICE Access Token 발급 성공 후 호출.
     * Redis TTL은 NICE 만료 시각에서 현재 시각을 빼고 안전 마진(60초)을 추가로 차감하여 설정.
     * TTL이 0 이하이면 저장하지 않음 (즉시 만료 토큰은 저장 의미 없음).
     *
     * @param accessToken          NICE Access Token 문자열
     * @param expiresInEpochMillis NICE 토큰 만료 시각 (epoch milliseconds)
     * @param ticket               PBKDF2 키 파생용 ticket (민감 정보)
     * @param iterators            PBKDF2 반복 횟수
     */
    public void save(String accessToken, long expiresInEpochMillis, String ticket, int iterators) {
        long now = System.currentTimeMillis();
        long ttlMillis = expiresInEpochMillis - now - EXPIRY_SAFETY_MARGIN_MILLIS;

        if (ttlMillis <= 0) {
            log.warn("[NiceTokenStore] 이미 만료된 토큰 — 저장 스킵. expiresIn={}", expiresInEpochMillis);
            return;
        }

        redisTemplate.opsForHash().put(TOKEN_KEY, FIELD_ACCESS_TOKEN, accessToken);
        redisTemplate.opsForHash().put(TOKEN_KEY, FIELD_EXPIRES_IN, String.valueOf(expiresInEpochMillis));
        redisTemplate.opsForHash().put(TOKEN_KEY, FIELD_TICKET, ticket);
        redisTemplate.opsForHash().put(TOKEN_KEY, FIELD_ITERATORS, String.valueOf(iterators));
        redisTemplate.expire(TOKEN_KEY, Duration.ofMillis(ttlMillis));

        log.info("[NiceTokenStore] NICE Access Token 저장 완료. TTL={}초", ttlMillis / 1000);
    }

    /**
     * 저장된 NICE Token 삭제 (테스트/강제 재발급용)
     */
    public void invalidate() {
        redisTemplate.delete(TOKEN_KEY);
        log.info("[NiceTokenStore] NICE Access Token 강제 무효화");
    }

    /**
     * NICE Access Token 불변 스냅샷
     *
     * <p>서비스 레이어에서 스냅샷을 한 번 조회한 후 일관된 값으로 사용.
     * Record 타입으로 불변 보장.
     *
     * @param accessToken          NICE Access Token
     * @param expiresInEpochMillis 만료 시각 (epoch millis)
     * @param ticket               PBKDF2 키 파생용 ticket
     * @param iterators            PBKDF2 반복 횟수
     */
    public record NiceTokenSnapshot(
            String accessToken,
            long expiresInEpochMillis,
            String ticket,
            int iterators
    ) {}
}

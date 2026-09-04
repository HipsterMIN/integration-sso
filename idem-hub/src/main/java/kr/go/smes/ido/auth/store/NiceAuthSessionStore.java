package kr.go.smes.ido.auth.store;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * NICE 인증 세션 Redis 저장소
 *
 * <p>onepass-be의 인메모리 {@code ConcurrentHashMap} 방식을 Redis로 교체.
 * URL 발급(requestNo → transactionId 저장)과 결과 조회(requestNo로 transactionId 검색) 사이의
 * 상태를 보관하는 단기 세션 저장소.
 *
 * <p><b>교체 이유:</b>
 * onepass-be는 단일 인스턴스 대상의 {@code ConcurrentHashMap} 사용.
 * ido는 K8s 환경에서 여러 Pod가 동작하므로, URL 발급 Pod와 콜백 처리 Pod가
 * 다를 수 있음 → Redis를 통해 Pod 간 세션 공유.
 *
 * <p><b>저장 전략:</b>
 * <ul>
 *   <li>Redis key: {@code nice:session:{requestNo}}</li>
 *   <li>Redis TTL: {@code SESSION_TTL_MINUTES}(기본 10분) — 인증 세션 유효 시간</li>
 *   <li>세션 조회 후 삭제: 결과 조회 성공 시 {@link #remove(String)} 호출</li>
 * </ul>
 *
 * <p><b>NICE 인증 플로우에서의 역할:</b>
 * <pre>
 * 1. GET /nice/phone/url 호출
 *    → NICE 서버에서 transactionId 발급
 *    → save(requestNo, transactionId) 호출
 *    → Redis에 {"requestNo": requestNo, "transactionId": transactionId} 저장 (TTL 10분)
 *
 * 2. (NICE 팝업에서 사용자 인증 완료)
 *    → 팝업이 FE에 web_transaction_id, request_no 전달
 *
 * 3. POST /nice/phone/result 호출
 *    → find(requestNo) 로 transactionId 조회
 *    → NICE API에 transactionId 전달하여 결과 조회
 *    → remove(requestNo) 호출 (일회성 세션)
 * </pre>
 *
 * @see NiceTokenStore
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NiceAuthSessionStore {

    /** Redis Key 프리픽스 */
    private static final String SESSION_KEY_PREFIX = "nice:session:";

    /** NICE 인증 세션 TTL (분) — NICE 인증 팝업의 유효 시간보다 충분히 크게 설정 */
    private static final long SESSION_TTL_MINUTES = 10L;

    /** Redis Hash 필드: requestNo */
    private static final String FIELD_REQUEST_NO = "requestNo";

    /** Redis Hash 필드: transactionId */
    private static final String FIELD_TRANSACTION_ID = "transactionId";

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * NICE 인증 세션 저장
     *
     * <p>URL 발급 성공 후 호출. requestNo를 키로 transactionId를 10분간 보관.
     * 10분 초과 시 사용자가 인증을 완료하지 않은 것으로 간주하여 자동 삭제.
     *
     * @param requestNo     ido가 생성한 요청 번호 (세션 키)
     * @param transactionId NICE 서버가 발급한 트랜잭션 ID
     */
    public void save(String requestNo, String transactionId) {
        String key = SESSION_KEY_PREFIX + requestNo;
        redisTemplate.opsForHash().put(key, FIELD_REQUEST_NO, requestNo);
        redisTemplate.opsForHash().put(key, FIELD_TRANSACTION_ID, transactionId);
        redisTemplate.expire(key, Duration.ofMinutes(SESSION_TTL_MINUTES));
        log.debug("[NiceAuthSessionStore] 세션 저장: requestNo={}, TTL={}분", requestNo, SESSION_TTL_MINUTES);
    }

    /**
     * NICE 인증 세션 조회
     *
     * <p>결과 조회 시 requestNo로 세션을 찾아 transactionId를 반환.
     * 세션이 없거나 TTL이 만료된 경우 null 반환.
     *
     * @param requestNo 조회할 요청 번호
     * @return 인증 세션 정보 (없으면 null)
     */
    public NiceAuthSession find(String requestNo) {
        String key = SESSION_KEY_PREFIX + requestNo;
        Object storedRequestNo = redisTemplate.opsForHash().get(key, FIELD_REQUEST_NO);
        Object transactionId = redisTemplate.opsForHash().get(key, FIELD_TRANSACTION_ID);

        if (storedRequestNo == null || transactionId == null) {
            log.debug("[NiceAuthSessionStore] 세션 없음 또는 만료: requestNo={}", requestNo);
            return null;
        }

        return new NiceAuthSession(storedRequestNo.toString(), transactionId.toString());
    }

    /**
     * NICE 인증 세션 삭제 (일회성 처리)
     *
     * <p>결과 조회 성공 후 호출하여 세션 데이터 제거.
     * 사용된 세션을 재사용할 수 없도록 즉시 삭제하여 보안 강화.
     *
     * @param requestNo 삭제할 요청 번호
     */
    public void remove(String requestNo) {
        String key = SESSION_KEY_PREFIX + requestNo;
        redisTemplate.delete(key);
        log.debug("[NiceAuthSessionStore] 세션 삭제: requestNo={}", requestNo);
    }

    /**
     * NICE 인증 세션 불변 레코드
     *
     * <p>URL 발급 시 저장한 requestNo와 transactionId 쌍.
     * 결과 조회 API에서 NICE 서버로 전달하는 식별자들.
     *
     * @param requestNo     ido가 생성한 요청 번호 (NICE API 요청 시 필요)
     * @param transactionId NICE 서버가 발급한 트랜잭션 ID (NICE API 결과 조회 시 필요)
     */
    public record NiceAuthSession(
            String requestNo,
            String transactionId
    ) {}
}

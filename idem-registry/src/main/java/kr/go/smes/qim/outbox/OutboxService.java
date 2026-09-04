package kr.go.smes.qim.outbox;

import kr.go.smes.common.event.DomainEvent;

/**
 * Transactional Outbox 서비스 인터페이스
 * 설계서 10.5.2절 참조
 *
 * DB 트랜잭션 내에서 OUTBOX 레코드를 적재하고,
 * 별도 Relay(스케줄러)가 Kafka로 발행하여 최종 일관성을 확보한다.
 * 'DB 커밋은 됐지만 이벤트 유실' 문제를 방지.
 *
 * <p>GAP-QIM-04: FAILED 레코드 재시도 흐름
 * <pre>
 *   relayPendingEvents()  → PENDING 레코드 발행 시도
 *       └ 실패 → markFailed(eventId, realExceptionMessage) (retryCount+1, 실제 오류 메시지)
 *
 *   relayFailedEvents()   → retryCount < maxRetry 인 FAILED 레코드 재시도
 *       └ 성공 → markPublished()
 *       └ 실패 → retryCount 누적 (maxRetry 도달 시 영구 FAILED)
 * </pre>
 */
public interface OutboxService {

    /**
     * 현재 트랜잭션 내에서 Outbox 레코드 적재 (Kafka 발행은 Relay가 수행)
     */
    void publishInTx(DomainEvent event);

    /**
     * Relay: 미발행(PENDING) Outbox 레코드를 Kafka에 발행
     * {@code @EnableScheduling + @Scheduled} 로 주기적 실행
     */
    void relayPendingEvents();

    /**
     * GAP-QIM-04: FAILED 레코드 재시도 Relay
     * retryCount &lt; maxRetry 인 레코드를 재발행하고,
     * maxRetry 도달 시 영구 FAILED 유지 (수동 개입 필요).
     */
    void relayFailedEvents();
}

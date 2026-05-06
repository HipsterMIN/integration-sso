package kr.go.smes.qim.outbox;

import kr.go.smes.common.event.DomainEvent;

/**
 * Transactional Outbox 서비스 인터페이스
 * 설계서 10.5.2절 참조
 *
 * DB 트랜잭션 내에서 OUTBOX 레코드를 적재하고,
 * 별도 Relay(스케줄러)가 Kafka로 발행하여 최종 일관성을 확보한다.
 * 'DB 커밋은 됐지만 이벤트 유실' 문제를 방지.
 */
public interface OutboxService {

    /**
     * 현재 트랜잭션 내에서 Outbox 레코드 적재 (Kafka 발행은 Relay가 수행)
     */
    void publishInTx(DomainEvent event);

    /**
     * Relay: 미발행 Outbox 레코드를 Kafka에 발행
     * @EnableScheduling + @Scheduled 로 주기적 실행
     */
    void relayPendingEvents();
}

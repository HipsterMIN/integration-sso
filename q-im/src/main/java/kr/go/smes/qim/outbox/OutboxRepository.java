package kr.go.smes.qim.outbox;

import java.util.List;

/**
 * Outbox 저장소 인터페이스
 * GAP-QIM-04: FAILED 레코드 재시도 지원 메서드 추가
 */
public interface OutboxRepository {
    void save(OutboxRecord record);
    List<OutboxRecord> findPending(int limit);
    void markPublished(String eventId);
    void markFailed(String eventId);

    /**
     * GAP-QIM-04: retryCount &lt; maxRetry 인 FAILED 레코드 조회 (재시도 대상)
     */
    List<OutboxRecord> findRetryable(short maxRetry, int limit);

    /**
     * GAP-QIM-04: FAILED → PENDING 상태 복구 (재시도 준비)
     */
    void markPending(String eventId);
}

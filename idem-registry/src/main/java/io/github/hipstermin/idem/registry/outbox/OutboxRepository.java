package io.github.hipstermin.idem.registry.outbox;

import java.util.List;

/**
 * Outbox 저장소 인터페이스
 * GAP-QIM-04: FAILED 레코드 재시도 지원 메서드 추가
 */
public interface OutboxRepository {
    void save(OutboxRecord record);
    List<OutboxRecord> findPending(int limit);
    void markPublished(String eventId);

    /**
     * 발행 실패 처리 — status=FAILED, retryCount+1, errorMessage 저장
     *
     * <p>GAP-QIM-04 개선: 실제 예외 메시지를 errorMessage로 전달.
     * DB outbox.error_message 컬럼에 기록되어 운영 디버깅 용이.
     *
     * @param eventId      실패한 이벤트 ID
     * @param errorMessage 실패 원인 메시지 (최대 500자 권장)
     */
    void markFailed(String eventId, String errorMessage);

    /**
     * GAP-QIM-04: retryCount &lt; maxRetry 인 FAILED 레코드 조회 (재시도 대상)
     */
    List<OutboxRecord> findRetryable(short maxRetry, int limit);

    /**
     * GAP-QIM-04: FAILED → PENDING 상태 복구 (재시도 준비)
     */
    void markPending(String eventId);
}

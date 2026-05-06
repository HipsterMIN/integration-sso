package kr.go.smes.qim.outbox;

import java.util.List;

/**
 * Outbox 저장소 인터페이스
 */
public interface OutboxRepository {
    void save(OutboxRecord record);
    List<OutboxRecord> findPending(int limit);
    void markPublished(String eventId);
    void markFailed(String eventId);
}

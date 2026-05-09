package kr.go.smes.qim.outbox;

import kr.go.smes.qim.infrastructure.jpa.entity.OutboxJpaEntity;
import kr.go.smes.qim.infrastructure.jpa.repository.OutboxJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Transactional Outbox Repository JPA 구현체
 * 설계서 §10.5.2
 *
 * [DB] NHN Cloud RDS for MariaDB (PoC: Docker MariaDB 11.x)
 *      테이블: outbox (idx_qim_outbox_status 인덱스 활용)
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class OutboxRepositoryImpl implements OutboxRepository {

    private final OutboxJpaRepository jpaRepository;

    @Override
    public void save(OutboxRecord record) {
        OutboxJpaEntity entity = toEntity(record);
        jpaRepository.save(entity);
    }

    @Override
    public List<OutboxRecord> findPending(int limit) {
        return jpaRepository.findPendingNative(limit)
                .stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public void markPublished(String eventId) {
        jpaRepository.markPublished(eventId, Instant.now());
    }

    @Override
    public void markFailed(String eventId) {
        jpaRepository.markFailed(eventId, "발행 실패 — Relay 재시도 대기");
    }

    @Override
    public List<OutboxRecord> findRetryable(short maxRetry, int limit) {
        return jpaRepository.findRetryable(maxRetry).stream()
                .limit(limit)
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public void markPending(String eventId) {
        jpaRepository.markPending(eventId);
    }

    // ── 매핑 ──────────────────────────────────────────────────────────────────

    private OutboxRecord toDomain(OutboxJpaEntity e) {
        return OutboxRecord.builder()
                .eventId(e.getEventId())
                .eventType(e.getEventType())
                .partitionKey(e.getPartitionKey())
                .eventVersion(e.getEventVersion())
                .payload(e.getPayload())
                .status(OutboxRecord.OutboxStatus.valueOf(e.getStatus()))
                // GAP-QIM-04: retryCount / errorMessage 도메인 레코드에 반영
                .retryCount(e.getRetryCount())
                .errorMessage(e.getErrorMessage())
                .createdAt(e.getCreatedAt())
                .publishedAt(e.getPublishedAt())
                .build();
    }

    private OutboxJpaEntity toEntity(OutboxRecord domain) {
        return OutboxJpaEntity.builder()
                .eventId(domain.getEventId())
                .eventType(domain.getEventType())
                .partitionKey(domain.getPartitionKey())
                .aggregateId(domain.getPartitionKey())   // partitionKey = qimUserId = aggregateId
                .eventVersion(domain.getEventVersion())
                .payload(domain.getPayload())
                .topic("qim.user.events")                // 기본 토픽 (Relay 확장 시 변경)
                .status(domain.getStatus() != null
                        ? domain.getStatus().name()
                        : OutboxRecord.OutboxStatus.PENDING.name())
                .retryCount((short) 0)
                .createdAt(domain.getCreatedAt() != null ? domain.getCreatedAt() : Instant.now())
                .build();
    }
}

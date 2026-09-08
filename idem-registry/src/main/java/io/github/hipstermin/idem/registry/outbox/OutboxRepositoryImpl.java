package io.github.hipstermin.idem.registry.outbox;

import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.OutboxJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.OutboxJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

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
    private final jakarta.persistence.EntityManager entityManager;

    @Override
    public void save(OutboxRecord record) {
        OutboxJpaEntity entity = toEntity(record);
        // persist() 를 사용해야 중복 eventId 에 대해 PK 위반 예외를 발생시킨다.
        // Spring Data JPA의 save()는 non-null ID 엔티티에 merge()를 호출하여
        // 중복 INSERT 없이 UPDATE만 수행하므로 멱등성 보장이 되지 않는다.
        entityManager.persist(entity);
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
    public void markFailed(String eventId, String errorMessage) {
        // GAP-QIM-04: 실제 예외 메시지를 DB에 기록 (이전: 고정 문자열)
        String msg = (errorMessage != null && !errorMessage.isBlank())
                ? errorMessage
                : "발행 실패 — Relay 재시도 대기";
        jpaRepository.markFailed(eventId, msg);
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

package io.github.hipstermin.idem.authz.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 인가 아웃박스 리포지토리. q-authz는 적재(INSERT)만 담당하고,
 * 상태 전이(PUBLISHED/FAILED)는 outbox-relay-batch가 JDBC로 수행한다.
 */
public interface AuthzOutboxRepository extends JpaRepository<AuthzOutboxEntity, String> {

    /**
     * 1.1: hub 가 폴링하는 읽기 전용 피드 — {@code (created_at, event_id)} 키셋. 상태(PENDING/PUBLISHED)는 보지 않는다:
     * Kafka 릴레이가 켜진 설치와 폴링 설치가 같은 표를 다른 방식으로 소비해도 간섭하지 않는다.
     */
    @org.springframework.data.jpa.repository.Query("""
        SELECT o FROM AuthzOutboxEntity o
        WHERE o.topic = :topic
          AND (o.createdAt > :afterCreatedAt OR (o.createdAt = :afterCreatedAt AND o.eventId > :afterEventId))
        ORDER BY o.createdAt ASC, o.eventId ASC
        """)
    java.util.List<AuthzOutboxEntity> findAfter(@org.springframework.data.repository.query.Param("topic") String topic,
                                                @org.springframework.data.repository.query.Param("afterCreatedAt") java.time.Instant afterCreatedAt,
                                                @org.springframework.data.repository.query.Param("afterEventId") String afterEventId,
                                                org.springframework.data.domain.Pageable pageable);
}

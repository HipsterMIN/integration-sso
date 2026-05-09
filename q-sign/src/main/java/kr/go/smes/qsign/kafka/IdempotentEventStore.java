package kr.go.smes.qsign.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Q-Sign 멱등 컨슈머 이벤트 저장소
 * 설계서 §16.3 at-least-once 중복 처리 방지 / GAP-QS-03
 *
 * <p>Q-Sign은 PostgreSQL({@code qsign} 스키마)을 사용한다.
 * Flyway V4 마이그레이션({@code V4__add_processed_event.sql})으로
 * 이미 {@code qsign.processed_event} 및 {@code qsign.last_event_version}
 * 테이블이 생성되어 있다.
 *
 * <p><b>중복 처리 방지 전략</b>:
 * <ul>
 *   <li>{@code processed_event}: {@code INSERT ON CONFLICT DO NOTHING} —
 *       동일 (eventId, consumerGroup) 조합의 이벤트는 원자적으로 차단</li>
 *   <li>{@code last_event_version}: {@code INSERT ... ON CONFLICT DO UPDATE} —
 *       동일 aggregate(qimUserId)의 마지막 처리 버전 추적, 역전 방지</li>
 * </ul>
 *
 * <p><b>버전 역전 방지 (§11.5.5 Ordered Consumer 패턴)</b>:
 * {@code isVersionOutdated()} 로 현재 이벤트 버전이 저장된 최신 버전보다
 * 낮거나 같으면 스킵하여 역전·중복을 방지한다.
 *
 * @see QimUserEventConsumer
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotentEventStore {

    /** Q-Sign 컨슈머 그룹 ID (application.yml: qsign.kafka.consumer-group-qim) */
    private static final String CONSUMER_GROUP = "q-sign-qim-consumer";

    private final JdbcTemplate jdbcTemplate;

    // ── processed_event ──────────────────────────────────────────────────────

    /**
     * 이미 처리된 이벤트인지 확인
     *
     * @param eventId       DomainEvent.eventId (UUID)
     * @param consumerGroup Kafka 컨슈머 그룹 ID
     * @return true = 중복 이벤트 → 스킵 대상
     */
    public boolean isAlreadyProcessed(String eventId, String consumerGroup) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM qsign.processed_event " +
                "WHERE event_id = ? AND consumer_group = ?",
                Integer.class, eventId, consumerGroup
        );
        return count != null && count > 0;
    }

    /**
     * 처리 완료 기록
     *
     * <p>ON CONFLICT DO NOTHING: 동시에 동일 이벤트가 두 번 처리될 경우 두 번째 INSERT 무시.
     *
     * @param eventId       DomainEvent.eventId (UUID)
     * @param consumerGroup Kafka 컨슈머 그룹 ID
     * @param eventType     이벤트 유형 (USER_UPDATED 등)
     * @param resultCode    처리 결과 코드 (OK / SKIPPED / ERROR)
     */
    public void markProcessed(String eventId, String consumerGroup,
                               String eventType, String resultCode) {
        jdbcTemplate.update(
                """
                INSERT INTO qsign.processed_event
                    (event_id, consumer_group, event_type, result_code, processed_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (event_id, consumer_group) DO NOTHING
                """,
                eventId, consumerGroup, eventType, resultCode, Instant.now()
        );
        log.debug("[QSign-IdempotentStore] 처리 완료 기록: eventId={} group={} result={}",
                eventId, consumerGroup, resultCode);
    }

    // ── last_event_version ───────────────────────────────────────────────────

    /**
     * 현재 이벤트의 버전이 이미 처리된 버전보다 낮거나 같은지 확인 (역전 방지)
     *
     * <p>저장된 버전이 없으면 false(처음 처리)를 반환한다.
     *
     * @param aggregateId  qimUserId (파티션 키)
     * @param eventVersion 현재 이벤트 버전
     * @return true = 구 버전(역전) → 스킵 대상
     */
    public boolean isVersionOutdated(String aggregateId, long eventVersion) {
        Long stored = jdbcTemplate.query(
                "SELECT last_version FROM qsign.last_event_version " +
                "WHERE consumer_group = ? AND aggregate_id = ?",
                rs -> rs.next() ? rs.getLong("last_version") : null,
                CONSUMER_GROUP, aggregateId
        );
        if (stored == null) {
            return false; // 첫 처리 → 역전 아님
        }
        boolean outdated = eventVersion <= stored;
        if (outdated) {
            log.debug("[QSign-IdempotentStore] 버전 역전 감지 — 스킵: " +
                      "aggregateId={} storedVersion={} incomingVersion={}",
                    aggregateId, stored, eventVersion);
        }
        return outdated;
    }

    /**
     * 마지막 처리 버전 갱신
     *
     * <p>ON CONFLICT DO UPDATE: 이미 레코드가 있으면 버전이 더 클 경우에만 업데이트.
     * 동시 처리 안전성을 위해 {@code WHERE last_version < EXCLUDED.last_version} 조건 사용.
     *
     * @param aggregateId  qimUserId
     * @param eventId      DomainEvent.eventId (UUID)
     * @param eventVersion 처리 완료된 이벤트 버전
     */
    public void updateLastVersion(String aggregateId, String eventId, long eventVersion) {
        jdbcTemplate.update(
                """
                INSERT INTO qsign.last_event_version
                    (consumer_group, aggregate_id, last_version, last_event_id, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (consumer_group, aggregate_id) DO UPDATE
                    SET last_version  = EXCLUDED.last_version,
                        last_event_id = EXCLUDED.last_event_id,
                        updated_at    = EXCLUDED.updated_at
                    WHERE qsign.last_event_version.last_version < EXCLUDED.last_version
                """,
                CONSUMER_GROUP, aggregateId, eventVersion, eventId, Instant.now()
        );
        log.debug("[QSign-IdempotentStore] 버전 갱신: aggregateId={} version={}",
                aggregateId, eventVersion);
    }
}

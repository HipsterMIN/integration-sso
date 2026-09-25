package io.github.hipstermin.idem.hub.kafka;

import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * IdO 멱등 컨슈머 이벤트 저장소
 * 설계서 §16.3 at-least-once 중복 처리 방지
 *
 * <p>DB INSERT ON CONFLICT DO NOTHING 으로 원자적 중복 방지.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotentEventStore {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 이미 처리된 이벤트인지 확인
     * @return true = 중복 (스킵 대상)
     */
    public boolean isAlreadyProcessed(String eventId, String consumerGroup) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idem_hub.processed_event " +
                "WHERE event_id = ? AND consumer_group = ?",
                Integer.class, eventId, consumerGroup
        );
        return count != null && count > 0;
    }

    /**
     * 처리 완료 기록
     * ON CONFLICT DO NOTHING: 동시 처리 시 중복 삽입 무시
     */
    /** D1-b: processed_at 은 DB 시각(NOW()). 종전에는 {@code Instant} 를 JDBC 파라미터로 넘겨 pgjdbc 가 타입을 추론하지 못해("Can't infer the SQL type") 모든 컨슈머의 완료 마킹이 항상 실패했다. */
    public void markProcessed(String eventId, String consumerGroup,
                              String eventType, String resultCode) {
        jdbcTemplate.update(
                """
                INSERT INTO idem_hub.processed_event
                    (event_id, consumer_group, event_type, result_code, processed_at)
                VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT (event_id, consumer_group) DO NOTHING
                """,
                eventId, consumerGroup, eventType, resultCode
        );
    }
}

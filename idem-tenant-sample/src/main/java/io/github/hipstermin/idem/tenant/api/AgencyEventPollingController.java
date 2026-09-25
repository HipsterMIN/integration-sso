package io.github.hipstermin.idem.tenant.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * 기관 이벤트 폴링 API
 *
 * <p><b>경로</b>: {@code GET /api/v1/events/poll}
 *
 * <p><b>역할</b>:
 * Webhook 수신이 어려운 환경의 기관을 위한 Pull 방식 이벤트 제공.
 * {@code agency_event_queue} 에서 미배달(delivered=FALSE) 이벤트를
 * 우선순위(priority ASC) → 생성순(created_at ASC) 으로 정렬하여 반환.
 * 반환 후 delivered=TRUE 로 마킹.
 *
 * <p><b>사용 시나리오</b>:
 * <pre>
 * [Option A - Push(권장)]: IdO → Webhook POST → 기관 엔드포인트
 * [Option B - Pull(대안)]: 기관 → GET /api/v1/events/poll → 이벤트 수신
 *
 * 두 옵션은 병행 가능.
 * webhook_inbound 처리 → agency_event_queue 적재 → 폴링 API 제공
 * </pre>
 *
 * <p><b>쿼리 파라미터</b>:
 * <ul>
 *   <li>{@code limit}     — 최대 반환 건수 (기본 20, 최대 100)</li>
 *   <li>{@code eventType} — 이벤트 타입 필터 (선택)</li>
 *   <li>{@code since}     — ISO-8601 이후 생성된 이벤트만 (선택)</li>
 * </ul>
 *
 * <p><b>응답</b>:
 * <pre>
 * {
 *   "events": [
 *     {
 *       "eventQueueId": "...",
 *       "eventType":    "HANDOFF_ISSUED",
 *       "correlationId":"...",
 *       "payload":      { ... },
 *       "priority":     3,
 *       "createdAt":    "2026-05-08T12:00:00Z"
 *     }
 *   ],
 *   "count":      5,
 *   "polledAt":   "2026-05-08T12:00:01Z"
 * }
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class AgencyEventPollingController {

    private static final int MAX_LIMIT = 100;

    private final JdbcTemplate jdbcTemplate;

    @Value("${idem.sample.code:AGENCY_STUB_001}")
    private String agencyCode;

    // ══════════════════════════════════════════════════════════════════════
    // 폴링 API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 미배달 이벤트 폴링 (Mark-as-delivered 방식)
     *
     * <p>반환과 동시에 delivered=TRUE 마킹하여 중복 배달 방지.
     * 기관 측에서 처리 실패 시 delivered_at IS NULL 재쿼리 또는 재폴링으로 재처리.
     */
    @GetMapping("/poll")
    public ResponseEntity<Map<String, Object>> poll(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String since) {

        int safeLimit = Math.min(Math.max(1, limit), MAX_LIMIT);
        Instant polledAt = Instant.now();

        log.debug("[EventPolling] 폴링 요청: agencyCode={} limit={} eventType={} since={}",
                agencyCode, safeLimit, eventType, since);

        // ① 미배달 이벤트 조회
        List<Map<String, Object>> events = fetchPendingEvents(safeLimit, eventType, since);

        if (events.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "events",   List.of(),
                    "count",    0,
                    "polledAt", polledAt.toString()
            ));
        }

        // ② delivered=TRUE 마킹 (조회한 ID들)
        List<String> ids = events.stream()
                .map(e -> (String) e.get("event_queue_id"))
                .toList();
        markDelivered(ids);

        log.info("[EventPolling] 폴링 완료: agencyCode={} count={}", agencyCode, events.size());

        return ResponseEntity.ok(Map.of(
                "events",   events.stream().map(this::toDto).toList(),
                "count",    events.size(),
                "polledAt", polledAt.toString()
        ));
    }

    /**
     * 특정 이벤트 타입 미배달 건수 확인 (헬스체크용)
     */
    @GetMapping("/pending-count")
    public ResponseEntity<Map<String, Object>> pendingCount(
            @RequestParam(required = false) String eventType) {

        String sql = eventType != null
                ? "SELECT COUNT(1) FROM agency_stub.agency_event_queue WHERE agency_code=? AND delivered=FALSE AND event_type=?"
                : "SELECT COUNT(1) FROM agency_stub.agency_event_queue WHERE agency_code=? AND delivered=FALSE";

        Integer count = eventType != null
                ? jdbcTemplate.queryForObject(sql, Integer.class, agencyCode, eventType)
                : jdbcTemplate.queryForObject(sql, Integer.class, agencyCode);

        return ResponseEntity.ok(Map.of(
                "pendingCount", count != null ? count : 0,
                "agencyCode",   agencyCode,
                "eventType",    eventType != null ? eventType : "ALL",
                "checkedAt",    Instant.now().toString()
        ));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 내부 헬퍼
    // ══════════════════════════════════════════════════════════════════════

    private List<Map<String, Object>> fetchPendingEvents(int limit, String eventType, String since) {
        try {
            if (eventType != null && since != null) {
                return jdbcTemplate.queryForList("""
                        SELECT event_queue_id, event_type, correlation_id,
                               event_payload::text AS payload,
                               priority, created_at
                        FROM   agency_stub.agency_event_queue
                        WHERE  agency_code = ?
                          AND  delivered   = FALSE
                          AND  event_type  = ?
                          AND  created_at  >= ?::timestamptz
                          AND  (deliver_before IS NULL OR deliver_before > NOW())
                        ORDER BY priority ASC, created_at ASC
                        LIMIT  ?
                        FOR UPDATE SKIP LOCKED
                        """, agencyCode, eventType, since, limit);

            } else if (eventType != null) {
                return jdbcTemplate.queryForList("""
                        SELECT event_queue_id, event_type, correlation_id,
                               event_payload::text AS payload,
                               priority, created_at
                        FROM   agency_stub.agency_event_queue
                        WHERE  agency_code = ?
                          AND  delivered   = FALSE
                          AND  event_type  = ?
                          AND  (deliver_before IS NULL OR deliver_before > NOW())
                        ORDER BY priority ASC, created_at ASC
                        LIMIT  ?
                        FOR UPDATE SKIP LOCKED
                        """, agencyCode, eventType, limit);

            } else if (since != null) {
                return jdbcTemplate.queryForList("""
                        SELECT event_queue_id, event_type, correlation_id,
                               event_payload::text AS payload,
                               priority, created_at
                        FROM   agency_stub.agency_event_queue
                        WHERE  agency_code = ?
                          AND  delivered   = FALSE
                          AND  created_at  >= ?::timestamptz
                          AND  (deliver_before IS NULL OR deliver_before > NOW())
                        ORDER BY priority ASC, created_at ASC
                        LIMIT  ?
                        FOR UPDATE SKIP LOCKED
                        """, agencyCode, since, limit);

            } else {
                return jdbcTemplate.queryForList("""
                        SELECT event_queue_id, event_type, correlation_id,
                               event_payload::text AS payload,
                               priority, created_at
                        FROM   agency_stub.agency_event_queue
                        WHERE  agency_code = ?
                          AND  delivered   = FALSE
                          AND  (deliver_before IS NULL OR deliver_before > NOW())
                        ORDER BY priority ASC, created_at ASC
                        LIMIT  ?
                        FOR UPDATE SKIP LOCKED
                        """, agencyCode, limit);
            }
        } catch (Exception e) {
            log.warn("[EventPolling] 이벤트 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    private void markDelivered(List<String> ids) {
        if (ids.isEmpty()) return;
        try {
            // Spring JdbcTemplate IN절 처리
            String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
            Object[] params = new Object[ids.size() + 1];
            params[0] = Instant.now();
            for (int i = 0; i < ids.size(); i++) params[i + 1] = ids.get(i);

            jdbcTemplate.update(
                    "UPDATE agency_stub.agency_event_queue SET delivered=TRUE, delivered_at=? " +
                    "WHERE event_queue_id IN (" + placeholders + ")",
                    params
            );
        } catch (Exception e) {
            log.warn("[EventPolling] delivered 마킹 실패: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toDto(Map<String, Object> row) {
        return Map.of(
                "eventQueueId", orEmpty(row.get("event_queue_id")),
                "eventType",    orEmpty(row.get("event_type")),
                "correlationId",orEmpty(row.get("correlation_id")),
                "payload",      orEmpty(row.get("payload")),
                "priority",     row.getOrDefault("priority", 5),
                "createdAt",    row.getOrDefault("created_at", "").toString()
        );
    }

    private String orEmpty(Object o) {
        return o != null ? o.toString() : "";
    }
}

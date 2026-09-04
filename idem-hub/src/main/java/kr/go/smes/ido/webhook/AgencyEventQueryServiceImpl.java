package kr.go.smes.ido.webhook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.ido.api.dto.AgencyEventListResponse;
import kr.go.smes.ido.api.dto.AgencyEventResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 기관 이벤트 폴링 조회 서비스 구현체
 *
 * <p>설계서 §P1-06 — {@code ido.webhook_dispatch_outbox} 테이블에서
 * agency_code 기준으로 이벤트를 조회하여 기관에 반환.
 *
 * <p><b>조회 설계</b>:
 * <ul>
 *   <li>status IN ('PENDING', 'DISPATCHED'): FAILED/SKIPPED는 기관에 노출 불필요</li>
 *   <li>created_at ASC: 오래된 이벤트 먼저 처리 (FIFO 보장)</li>
 *   <li>since 커서: 연속 폴링 시 중복 없이 다음 배치 조회</li>
 *   <li>FOR UPDATE SKIP LOCKED: 다중 인스턴스 동시 폴링 시 중복 반환 방지</li>
 * </ul>
 *
 * <p><b>payload 처리</b>:
 * DB에 저장된 JSONB payload를 {@code Object}로 역직렬화하여 반환.
 * 기관은 응답 JSON을 그대로 파싱 가능.
 *
 * <p><b>mark-as-read 전략</b>:
 * PENDING → DISPATCHED 갱신. dispatched_at은 최초 갱신 시에만 설정.
 * 멱등: 이미 DISPATCHED이면 아무 변경 없음.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgencyEventQueryServiceImpl implements AgencyEventQueryService {

    private static final int MIN_LIMIT     =   1;
    private static final int MAX_LIMIT     = 100;
    private static final int DEFAULT_LIMIT =  20;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    // ══════════════════════════════════════════════════════════════════════
    // 공개 API
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public AgencyEventListResponse queryEvents(String agencyCode, int limit,
                                               String eventType, Instant since) {
        // limit 범위 보정
        int safeLimit = Math.max(MIN_LIMIT, Math.min(limit, MAX_LIMIT));

        log.debug("[AgencyEventQuery] 이벤트 조회: agencyCode={} limit={} eventType={} since={}",
                agencyCode, safeLimit, eventType, since);

        List<AgencyEventResponse> events = fetchEvents(agencyCode, safeLimit, eventType, since);

        boolean hasMore = events.size() == safeLimit;
        Instant polledAt = Instant.now();

        log.info("[AgencyEventQuery] 조회 완료: agencyCode={} count={} hasMore={} eventType={} since={}",
                agencyCode, events.size(), hasMore, eventType, since);

        return AgencyEventListResponse.builder()
                .events(events)
                .count(events.size())
                .hasMore(hasMore)
                .polledAt(polledAt)
                .queryInfo(AgencyEventListResponse.QueryInfo.builder()
                        .limit(safeLimit)
                        .eventType(eventType)
                        .since(since != null ? since.toString() : null)
                        .agencyCode(agencyCode)
                        .build())
                .build();
    }

    @Override
    @Transactional
    public boolean markAsRead(String dispatchId, String agencyCode) {
        log.debug("[AgencyEventQuery] mark-as-read: dispatchId={} agencyCode={}", dispatchId, agencyCode);

        try {
            // ① agencyCode 소유권 확인 + PENDING 상태인 레코드만 변경
            //    DISPATCHED는 이미 처리됨 → 멱등 처리
            int updated = jdbcTemplate.update("""
                    UPDATE ido.webhook_dispatch_outbox
                       SET status        = 'DISPATCHED',
                           dispatched_at = COALESCE(dispatched_at, NOW())
                     WHERE dispatch_id  = ?
                       AND agency_code  = ?
                       AND status       = 'PENDING'
                    """,
                    dispatchId, agencyCode
            );

            if (updated > 0) {
                log.info("[AgencyEventQuery] mark-as-read 성공: dispatchId={} agencyCode={}",
                        dispatchId, agencyCode);
                return true;
            } else {
                // 이미 DISPATCHED이거나 해당 기관의 레코드가 아님
                log.debug("[AgencyEventQuery] mark-as-read 스킵 (이미 처리 or 권한 없음): " +
                        "dispatchId={} agencyCode={}", dispatchId, agencyCode);
                return false;
            }
        } catch (Exception e) {
            log.error("[AgencyEventQuery] mark-as-read 실패: dispatchId={} agencyCode={} error={}",
                    dispatchId, agencyCode, e.getMessage(), e);
            throw new RuntimeException("이벤트 읽음 처리 실패: " + dispatchId, e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 내부 구현
    // ══════════════════════════════════════════════════════════════════════

    /**
     * webhook_dispatch_outbox 이벤트 조회
     *
     * <p>동적 SQL 구성: eventType / since 필터 조건이 null인 경우 해당 절 제외.
     * PreparedStatement 파라미터는 순서대로 바인딩.
     */
    private List<AgencyEventResponse> fetchEvents(String agencyCode, int limit,
                                                   String eventType, Instant since) {
        // ① SQL 동적 구성
        StringBuilder sql = new StringBuilder("""
                SELECT dispatch_id,
                       source_event_type,
                       source_event_id,
                       agency_code,
                       status,
                       payload,
                       correlation_id,
                       retry_count,
                       created_at,
                       dispatched_at
                  FROM ido.webhook_dispatch_outbox
                 WHERE agency_code = ?
                   AND status IN ('PENDING', 'DISPATCHED')
                """);

        List<Object> params = new ArrayList<>();
        params.add(agencyCode);

        // ② 이벤트 타입 필터 (선택)
        if (eventType != null && !eventType.isBlank()) {
            sql.append("   AND source_event_type = ?\n");
            params.add(eventType);
        }

        // ③ since 커서 (연속 폴링: created_at > since)
        if (since != null) {
            sql.append("   AND created_at > ?\n");
            params.add(Timestamp.from(since));
        }

        // ④ 정렬 + limit
        sql.append(" ORDER BY created_at ASC\n");
        sql.append(" LIMIT ?");
        params.add(limit);

        try {
            return jdbcTemplate.query(
                    sql.toString(),
                    params.toArray(),
                    this::mapRow
            );
        } catch (Exception e) {
            log.error("[AgencyEventQuery] 이벤트 조회 실패: agencyCode={} error={}", agencyCode, e.getMessage(), e);
            throw new RuntimeException("이벤트 조회 중 오류 발생", e);
        }
    }

    /**
     * ResultSet → AgencyEventResponse 매핑
     *
     * <p>payload(JSONB)는 Jackson으로 역직렬화하여 {@code Object}로 반환.
     * 역직렬화 실패 시 원본 문자열을 그대로 반환 (기관이 파싱 가능하도록).
     */
    private AgencyEventResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        String payloadStr = rs.getString("payload");
        Object payloadObj = deserializePayload(payloadStr);

        Timestamp dispatchedAtTs = rs.getTimestamp("dispatched_at");
        Instant dispatchedAt = (dispatchedAtTs != null) ? dispatchedAtTs.toInstant() : null;

        Timestamp createdAtTs = rs.getTimestamp("created_at");
        Instant createdAt = (createdAtTs != null) ? createdAtTs.toInstant() : null;

        return AgencyEventResponse.builder()
                .dispatchId(rs.getString("dispatch_id"))
                .eventType(rs.getString("source_event_type"))
                .sourceEventId(rs.getString("source_event_id"))
                .agencyCode(rs.getString("agency_code"))
                .status(rs.getString("status"))
                .payload(payloadObj)
                .correlationId(rs.getString("correlation_id"))
                .retryCount(rs.getInt("retry_count"))
                .createdAt(createdAt)
                .dispatchedAt(dispatchedAt)
                .build();
    }

    /**
     * JSONB payload 문자열 → Map 역직렬화
     * 실패 시 원본 문자열 반환 (비치명적)
     */
    private Object deserializePayload(String payloadStr) {
        if (payloadStr == null || payloadStr.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payloadStr, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[AgencyEventQuery] payload 역직렬화 실패, 원본 문자열 반환: error={}", e.getMessage());
            return payloadStr;
        }
    }
}

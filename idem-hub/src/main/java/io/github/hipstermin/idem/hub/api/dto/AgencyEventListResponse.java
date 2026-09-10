package io.github.hipstermin.idem.hub.api.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 기관 이벤트 폴링 목록 응답 DTO
 *
 * <p>설계서 §P1-06 — {@code GET /api/v1/agency/events} 응답 최상위 래퍼.
 *
 * <p><b>페이징 설계</b>:
 * <ul>
 *   <li>limit: 요청된 최대 건수 (기본 20, 최대 100)</li>
 *   <li>count: 실제 반환된 건수</li>
 *   <li>hasMore: count == limit 이면 true → 기관이 다시 폴링해야 함</li>
 *   <li>oldestEventAt: 반환된 이벤트 중 가장 오래된 created_at (since 파라미터 연속 폴링용)</li>
 * </ul>
 *
 * <p><b>연속 폴링 권장 패턴</b>:
 * <pre>
 * GET /api/v1/agency/events?limit=50
 * → hasMore=true → GET /api/v1/agency/events?limit=50&amp;since={polledAt}
 * → hasMore=false → 완료
 * </pre>
 *
 * @see AgencyEventResponse
 */
@Getter
@Builder
public class AgencyEventListResponse {

    /** 이벤트 목록 */
    private final List<AgencyEventResponse> events;

    /** 실제 반환된 이벤트 수 */
    private final int count;

    /** 요청 limit 도달 여부 — true이면 추가 폴링 필요 */
    private final boolean hasMore;

    /** 폴링 실행 시각 (UTC ISO-8601) — 다음 폴링 since 파라미터로 활용 가능 */
    private final Instant polledAt;

    /** 적용된 필터 정보 (디버깅용) */
    private final QueryInfo queryInfo;

    @Getter
    @Builder
    public static class QueryInfo {
        private final int limit;
        private final String eventType;   // null이면 전체
        private final String since;       // null이면 전체
        private final String agencyCode;
    }
}

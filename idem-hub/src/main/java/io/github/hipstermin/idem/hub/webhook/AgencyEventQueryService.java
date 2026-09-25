package io.github.hipstermin.idem.hub.webhook;

import io.github.hipstermin.idem.hub.api.dto.AgencyEventListResponse;
import java.time.Instant;

/**
 * 기관 이벤트 폴링 조회 서비스 인터페이스
 *
 * <p>설계서 §P1-06 — 유관기관이 Kafka 직접 접속 없이
 * IdO의 HTTP 폴링 API를 통해 자신에게 발생한 이벤트를 가져가는 기능.
 *
 * <p><b>데이터 소스</b>: {@code idem_hub.webhook_dispatch_outbox}
 * WebhookDispatcherService 가 이미 마스킹·JSONB 저장한 레코드를 그대로 조회.
 *
 * <p><b>읽기 전용</b>: 이 서비스는 조회만 수행하며, 상태 변경(mark-as-read)은
 * {@link #markAsRead(String, String)} 메서드에서 별도 처리.
 *
 * @see AgencyEventQueryServiceImpl
 * @see io.github.hipstermin.idem.hub.api.AgencyEventController
 */
public interface AgencyEventQueryService {

    /**
     * 기관의 이벤트 목록 조회
     *
     * <p>조회 기준:
     * <ul>
     *   <li>agency_code = agencyCode</li>
     *   <li>status IN ('PENDING', 'DISPATCHED') — 처리 중이거나 전송 완료된 이벤트</li>
     *   <li>eventType 지정 시 source_event_type = eventType (선택 필터)</li>
     *   <li>since 지정 시 created_at &gt; since (연속 폴링 커서)</li>
     *   <li>created_at ASC 정렬 (오래된 이벤트부터)</li>
     *   <li>limit 건수 제한 (기본 20, 최대 100)</li>
     * </ul>
     *
     * @param agencyCode  검증된 기관 코드 (인터셉터에서 확인 완료)
     * @param limit       최대 반환 건수 (1~100)
     * @param eventType   이벤트 타입 필터 (null이면 전체)
     * @param since       이 시각 이후 이벤트만 조회 (null이면 전체)
     * @return 이벤트 목록 응답
     */
    AgencyEventListResponse queryEvents(String agencyCode, int limit,
                                        String eventType, Instant since);

    /**
     * 특정 이벤트를 읽음 처리 (mark-as-read)
     *
     * <p>기관이 이벤트를 수신 확인하면 해당 dispatch 레코드의 status를 DISPATCHED로 갱신.
     * 이미 DISPATCHED인 경우 멱등 처리 (무시).
     *
     * <p><b>보안</b>: agencyCode가 해당 dispatch_id의 소유자인지 반드시 검증.
     *
     * @param dispatchId  mark-as-read 대상 dispatch_id
     * @param agencyCode  검증된 기관 코드 (본인 레코드만 변경 가능)
     * @return true = 상태 변경됨, false = 이미 처리되었거나 권한 없음
     */
    boolean markAsRead(String dispatchId, String agencyCode);
}

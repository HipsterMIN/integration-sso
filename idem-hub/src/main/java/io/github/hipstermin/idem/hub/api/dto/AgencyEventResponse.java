package io.github.hipstermin.idem.hub.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * 기관 폴링 이벤트 단건 응답 DTO
 *
 * <p>설계서 §P1-06 — 유관기관이 {@code GET /api/v1/agency/events}로 폴링할 때
 * {@code ido.webhook_dispatch_outbox} 테이블의 각 레코드를 이 형식으로 반환.
 *
 * <p><b>보안 원칙</b>:
 * <ul>
 *   <li>qimUserId 원본 금지 — payload 내 agencySubjectId 사용</li>
 *   <li>payload 는 WebhookDispatcherService 가 이미 개인정보 마스킹 후 저장한 JSONB</li>
 *   <li>기관별 발송 결과(status) 노출 — 기관 자신의 레코드만 반환되므로 안전</li>
 * </ul>
 *
 * @see AgencyEventListResponse
 * @see io.github.hipstermin.idem.hub.api.AgencyEventController
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgencyEventResponse {

    /**
     * Outbox 발송 레코드 식별자 (UUIDv4)
     * 기관이 mark-as-read 요청 시 이 ID를 사용
     */
    private final String dispatchId;

    /**
     * 원본 이벤트 유형
     * e.g. HANDOFF_ISSUED / HANDOFF_REVOKED / MEMBER_LOOKUP_RESULT / MEMBER_WITHDRAWN
     */
    private final String eventType;

    /**
     * 원본 Kafka 이벤트 ID (추적용)
     */
    private final String sourceEventId;

    /**
     * 이벤트 발생 기관 코드 (자기 자신)
     */
    private final String agencyCode;

    /**
     * 발송 상태: PENDING / DISPATCHED / FAILED / SKIPPED
     * 폴링 모드에서는 PENDING + DISPATCHED 이벤트 모두 반환
     */
    private final String status;

    /**
     * 이벤트 payload (WebhookDispatcherService가 마스킹 완료한 JSONB 내용)
     * JSON 문자열 그대로 반환 — 기관이 파싱하여 사용
     */
    private final Object payload;

    /**
     * 상관 ID (end-to-end 추적)
     */
    private final String correlationId;

    /**
     * webhook 발송 재시도 횟수 (정보 제공용)
     */
    private final int retryCount;

    /**
     * 이벤트 생성 시각 (UTC ISO-8601)
     */
    private final Instant createdAt;

    /**
     * 최초 발송 성공 시각 (DISPATCHED 상태일 때만 non-null)
     */
    private final Instant dispatchedAt;
}

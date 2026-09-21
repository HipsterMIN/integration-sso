package io.github.hipstermin.idem.common.event;

import lombok.Getter;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

/**
 * 기관 Webhook 발송 요청 이벤트
 *
 * <p>IdO 내부 Kafka 버스에서만 유통된다.
 * HandoffEventConsumer / MemberLookupService 등이 이 이벤트를 발행하면,
 * WebhookDispatcherService 가 수신하여 기관 HTTPS endpoint 로 HTTP POST 를 보낸다.
 *
 * <p><b>토픽</b>: {@code ido.webhook.dispatch.requests}
 * <p><b>파티션 키</b>: {@code agencyCode} — 동일 기관 이벤트의 순서 보장
 *
 * <p>이벤트 타입:
 * <ul>
 *   <li>{@code HANDOFF_ISSUED}       — Handoff 티켓 발급 통보</li>
 *   <li>{@code HANDOFF_CONSUMED}     — Handoff 티켓 소비(검증 완료) 통보</li>
 *   <li>{@code HANDOFF_REVOKED}      — Handoff 강제 취소 통보 (보안 이벤트)</li>
 *   <li>{@code SESSION_ADVISORY}     — 세션 종료 권고</li>
 *   <li>{@code MEMBER_LOOKUP_RESULT} — CI/DN 회원 조회 결과 push</li>
 *   <li>{@code MEMBER_WITHDRAWN}     — 회원 탈퇴 통보</li>
 * </ul>
 */
@Getter
@SuperBuilder
@Jacksonized // D1-b: Kafka JsonDeserializer·아웃박스 프로세스 내 배달 모두 이 클래스로 역직렬화한다 (생성자만으로는 Jackson 이 만들 수 없었다)
public class WebhookDispatchEvent extends DomainEvent {

    // ── 이벤트 타입 상수 ────────────────────────────────────────────────────
    public static final String TYPE_HANDOFF_ISSUED       = "HANDOFF_ISSUED";
    public static final String TYPE_HANDOFF_CONSUMED     = "HANDOFF_CONSUMED";
    public static final String TYPE_HANDOFF_REVOKED      = "HANDOFF_REVOKED";
    public static final String TYPE_SESSION_ADVISORY     = "SESSION_ADVISORY";
    public static final String TYPE_MEMBER_LOOKUP_RESULT = "MEMBER_LOOKUP_RESULT";
    public static final String TYPE_MEMBER_WITHDRAWN     = "MEMBER_WITHDRAWN";

    /** 발송 대상 기관 코드 (Kafka 파티션 키이기도 함) */
    private final String agencyCode;

    /** 기관 webhook endpoint URL (발송 시점 스냅샷) */
    private final String endpointUrl;

    /**
     * 기관에 전달할 실제 페이로드 (JSON 문자열)
     *
     * <p>개인정보 마스킹 원칙:
     * <ul>
     *   <li>CI/DN 원본값 포함 금지</li>
     *   <li>qimUserId 대신 agencySubjectId (HMAC-SHA256(qimUserId, agencySecret)) 사용</li>
     *   <li>허용 속성(allowedAttributes)에 따라 필드 필터링 완료 후 이 필드에 저장</li>
     * </ul>
     */
    private final String webhookPayloadJson;

    /** 원본 이벤트 ID (멱등 키 — webhook_dispatch_outbox.source_event_id) */
    private final String sourceEventId;

    /** 원본 이벤트 토픽 (추적용) */
    private final String sourceTopic;

    /** 재시도 최대 횟수 (기관별 설정에서 오버라이드 가능) */
    private final int maxRetry;

    public WebhookDispatchEvent(String eventType, String sourceSystem,
                                String correlationId, String qimUserId, Long eventVersion,
                                String agencyCode, String endpointUrl,
                                String webhookPayloadJson, String sourceEventId,
                                String sourceTopic, int maxRetry) {
        super(eventType, sourceSystem, correlationId, qimUserId, eventVersion);
        this.agencyCode           = agencyCode;
        this.endpointUrl          = endpointUrl;
        this.webhookPayloadJson   = webhookPayloadJson;
        this.sourceEventId        = sourceEventId;
        this.sourceTopic          = sourceTopic;
        this.maxRetry             = maxRetry;
    }
}

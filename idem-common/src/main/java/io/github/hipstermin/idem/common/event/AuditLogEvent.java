package io.github.hipstermin.idem.common.event;

import lombok.Getter;
import lombok.experimental.SuperBuilder;

/**
 * 플랫폼 전역 감사 로그 이벤트
 *
 * <p>모든 서비스(q-sign, q-im, ido)가 이 이벤트를 발행하여
 * {@code platform.audit.log} 토픽에 기록한다.
 * IdO 의 AuditLogConsumer 가 로컬 DB({@code ido.audit_log})에 이중 저장한다.
 *
 * <p><b>토픽</b>: {@code platform.audit.log}
 * <p><b>파티션 키</b>: {@code agencyCode} (없으면 {@code sourceSystem})
 * <p><b>retention</b>: 2년 (법적 보존 요건)
 *
 * <p><b>개인정보 마스킹 원칙</b>:
 * <ul>
 *   <li>CI / DN 원본값 포함 금지 → {@code identifierHash} 사용</li>
 *   <li>이름·연락처 등 PII 는 {@code metadata} 에 포함 금지</li>
 * </ul>
 */
@Getter
@SuperBuilder
public class AuditLogEvent extends DomainEvent {

    // ── 이벤트 카테고리 상수 ───────────────────────────────────────────────
    public static final String CATEGORY_AUTH    = "AUTH";
    public static final String CATEGORY_HANDOFF = "HANDOFF";
    public static final String CATEGORY_MEMBER  = "MEMBER";
    public static final String CATEGORY_SESSION = "SESSION";
    public static final String CATEGORY_WEBHOOK = "WEBHOOK";
    public static final String CATEGORY_SYSTEM  = "SYSTEM";

    // ── 처리 결과 상수 ─────────────────────────────────────────────────────
    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILURE = "FAILURE";
    public static final String OUTCOME_PARTIAL = "PARTIAL";

    // ── 행위자 타입 상수 ───────────────────────────────────────────────────
    public static final String ACTOR_USER   = "USER";
    public static final String ACTOR_SYSTEM = "SYSTEM";
    public static final String ACTOR_AGENCY = "AGENCY";

    /** 이벤트 분류 (AUTH / HANDOFF / MEMBER / SESSION / WEBHOOK / SYSTEM) */
    private final String eventCategory;

    /** 세부 액션 (AUTH_COMPLETED / HANDOFF_ISSUED / WEBHOOK_DISPATCHED 등) */
    private final String eventAction;

    /** 행위자 타입 (USER / SYSTEM / AGENCY) */
    private final String actorType;

    /** 행위자 ID (qimUserId / serviceId / agencyCode) */
    private final String actorId;

    /** 대상 리소스 타입 (TICKET / SESSION / MEMBER / WEBHOOK) */
    private final String resourceType;

    /** 대상 리소스 ID (ticketId / sessionId / instMbrId 등) */
    private final String resourceId;

    /** 관련 기관 코드 */
    private final String agencyCode;

    /** 발생 시스템 (q-sign / q-im / ido) */
    private final String sourceSystem;

    /** 클라이언트 IP (개인정보 처리 방침에 따라 마스킹 적용 가능) */
    private final String sourceIp;

    /** 처리 결과 (SUCCESS / FAILURE / PARTIAL) */
    private final String outcome;

    /** 결과 상세 설명 (오류 코드·이유 등) */
    private final String outcomeDetail;

    /**
     * 추가 컨텍스트 메타데이터 (JSON 문자열)
     *
     * <p>개인정보(CI/DN/이름/연락처) 포함 금지.
     * identifierHash, authLevel, eventType 등 비PII 필드만 허용.
     */
    private final String metadataJson;

    public AuditLogEvent(String eventType, String sourceSystem,
                         String correlationId, String qimUserId, Long eventVersion,
                         String eventCategory, String eventAction,
                         String actorType, String actorId,
                         String resourceType, String resourceId,
                         String agencyCode, String sourceIp,
                         String outcome, String outcomeDetail,
                         String metadataJson) {
        super(eventType, sourceSystem, correlationId, qimUserId, eventVersion);
        this.eventCategory  = eventCategory;
        this.eventAction    = eventAction;
        this.actorType      = actorType;
        this.actorId        = actorId;
        this.resourceType   = resourceType;
        this.resourceId     = resourceId;
        this.agencyCode     = agencyCode;
        this.sourceSystem   = sourceSystem;
        this.sourceIp       = sourceIp;
        this.outcome        = outcome;
        this.outcomeDetail  = outcomeDetail;
        this.metadataJson   = metadataJson;
    }
}

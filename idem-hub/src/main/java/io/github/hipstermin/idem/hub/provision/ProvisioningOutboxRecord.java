package io.github.hipstermin.idem.hub.provision;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

/**
 * ido.provisioning_outbox 테이블 레코드 도메인 객체
 *
 * <p>프로비저닝 아웃박스 레코드:
 * 68개 기관에 전달할 사용자 이벤트 하나의 단위.
 * PK: id (UUID v7), UNIQUE: (idempotency_key, agency_code)
 */
@Value
@Builder(toBuilder = true)
public class ProvisioningOutboxRecord {

    /** 레코드 ID (UUID v7, PK) */
    String id;

    /** 대상 사용자 ID (qimUserId) */
    String qimUserId;

    /** 전송 대상 기관 코드 */
    String agencyCode;

    /**
     * 이벤트 타입 (provisioning_outbox.event_type 컬럼)
     *
     * <p><b>QIM-OUTBOX-SPEC-001 신규 5종 (V18 CHECK 제약 우선 허용)</b>:
     * <ul>
     *   <li>{@code PERSONAL_MEMBER_REGISTERED} — 개인 신규 가입 (isTransfer=false, isCorporate=false)</li>
     *   <li>{@code PERSONAL_MEMBER_CONVERTED}  — 개인 전환     (isTransfer=true,  isCorporate=false)</li>
     *   <li>{@code BIZ_MEMBER_REGISTERED}      — 기업 신규 가입 (isTransfer=false, isCorporate=true)</li>
     *   <li>{@code BIZ_MEMBER_CONVERTED}       — 기업 전환     (isTransfer=true,  isCorporate=true)</li>
     *   <li>{@code MEMBER_WITHDRAWN}           — 회원 탈퇴</li>
     * </ul>
     *
     * <p><b>@Deprecated 구 타입 (V18 하위 호환, forRemoval=true)</b>:
     * {@code USER_REGISTERED} / {@code BIZ_CONVERTED} / {@code USER_UPDATED} / {@code USER_WITHDRAWN}
     *
     * <p><b>데이터 흐름</b>: QimEventConsumer → triggerProvisioning() → insertOutbox() →
     * provisioning_outbox.event_type (V18 CHECK 제약 적용)
     *
     * @see ProvisioningEventType
     */
    String eventType;

    /** 전송 페이로드 (JSONB, PII 최소화) */
    String payloadJson;

    /** 멱등성 키 (UUID v7) — (idempotency_key, agency_code) UNIQUE */
    String idempotencyKey;

    /** 현재 상태: PENDING / COMPLETED / DEAD_LETTER */
    @Builder.Default
    String status = "PENDING";

    /** 현재 재시도 횟수 */
    @Builder.Default
    int retryCount = 0;

    /** 최대 재시도 횟수 (기본 3) */
    @Builder.Default
    int maxRetry = 3;

    /** 다음 재시도 가능 시각 (지수 백오프) */
    Instant nextRetryAt;

    /** 생성 시각 */
    Instant createdAt;

    /** 마지막 시도 시각 */
    Instant lastAttemptedAt;

    /** 성공 완료 시각 */
    Instant completedAt;

    /** 마지막 실패 메시지 */
    String errorMessage;

    /** 흐름 추적 ID */
    String correlationId;

    /** 트리거한 Q-IM 이벤트 ID */
    String sourceEventId;
}

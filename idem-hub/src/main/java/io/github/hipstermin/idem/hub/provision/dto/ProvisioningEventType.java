package io.github.hipstermin.idem.hub.provision.dto;

/**
 * 프로비저닝 이벤트 타입 열거형
 *
 * <p>QIM-OUTBOX-SPEC-001 기준 Q-IM Kafka 이벤트 → ProvisioningService 트리거 매핑:
 * <ul>
 *   <li>PERSONAL_MEMBER_REGISTERED — 개인 회원 신규 가입</li>
 *   <li>PERSONAL_MEMBER_CONVERTED  — 개인 회원 전환 (기존 계정 연계)</li>
 *   <li>BIZ_MEMBER_REGISTERED      — 기업 회원 신규 가입</li>
 *   <li>BIZ_MEMBER_CONVERTED       — 기업 회원 전환 (기존 계정 연계)</li>
 *   <li>MEMBER_WITHDRAWN           — 회원 탈퇴 (계정 삭제 연동)</li>
 *   <li>USER_UPDATED               — 프로필 변경 (추후 확장용 보존)</li>
 * </ul>
 *
 * <p><b>이벤트 결정 규칙</b> (resolveRegisterEventType 참조):
 * <pre>
 *   isTransfer=false, isCorporate=false → PERSONAL_MEMBER_REGISTERED
 *   isTransfer=true,  isCorporate=false → PERSONAL_MEMBER_CONVERTED
 *   isTransfer=false, isCorporate=true  → BIZ_MEMBER_REGISTERED
 *   isTransfer=true,  isCorporate=true  → BIZ_MEMBER_CONVERTED
 *   탈퇴 요청                           → MEMBER_WITHDRAWN
 * </pre>
 *
 * <p><b>⚠️ Flyway 마이그레이션 주의</b>:
 * V15의 provisioning_outbox.event_type CHECK 제약은 구 이벤트 타입
 * ('USER_REGISTERED','BIZ_CONVERTED','USER_UPDATED','USER_WITHDRAWN')으로 정의되어 있다.
 * 신규 이벤트 타입 적용 시 Flyway 마이그레이션(V17 또는 V18)으로
 * ALTER TABLE + CONSTRAINT 갱신이 필요하다.
 *
 * <p><b>하위 호환성</b>:
 * 구 이벤트 타입(USER_REGISTERED, BIZ_CONVERTED)은 {@code @Deprecated}로 보존.
 * QimEventConsumer.isProvisioningTriggerEvent()는 이미 신규 4종을 사용하므로
 * provisioning_outbox와의 연동 시 신규 타입을 직접 사용한다.
 *
 * @since QIM-OUTBOX-SPEC-001 (v0.8.8)
 */
public enum ProvisioningEventType {

    // ─── 신규 4종 (QIM-OUTBOX-SPEC-001 표준) ───────────────────────────────

    /** 개인 회원 신규 가입 — resolveRegisterEventType(false, false) */
    PERSONAL_MEMBER_REGISTERED,

    /** 개인 회원 전환 (기존 계정 연계) — resolveRegisterEventType(true, false) */
    PERSONAL_MEMBER_CONVERTED,

    /** 기업 회원 신규 가입 — resolveRegisterEventType(false, true) */
    BIZ_MEMBER_REGISTERED,

    /** 기업 회원 전환 (기존 계정 연계) — resolveRegisterEventType(true, true) */
    BIZ_MEMBER_CONVERTED,

    /** 회원 탈퇴 — 계정 삭제 연동 */
    MEMBER_WITHDRAWN,

    // ─── 확장용 ─────────────────────────────────────────────────────────────

    /** 프로필 변경 — 추후 Sprint 확장용 (현재 미사용) */
    USER_UPDATED,

    // ─── Deprecated (하위 호환성 보존) ──────────────────────────────────────

    /**
     * @deprecated QIM-OUTBOX-SPEC-001에서 PERSONAL_MEMBER_REGISTERED 또는
     *             BIZ_MEMBER_REGISTERED로 분리됨. V15 DB CHECK 제약 갱신 후 제거 예정.
     */
    @Deprecated(since = "QIM-OUTBOX-SPEC-001", forRemoval = true)
    USER_REGISTERED,

    /**
     * @deprecated QIM-OUTBOX-SPEC-001에서 PERSONAL_MEMBER_CONVERTED 또는
     *             BIZ_MEMBER_CONVERTED로 분리됨. V15 DB CHECK 제약 갱신 후 제거 예정.
     */
    @Deprecated(since = "QIM-OUTBOX-SPEC-001", forRemoval = true)
    BIZ_CONVERTED,

    /**
     * @deprecated MEMBER_WITHDRAWN으로 이름 통일. forRemoval=true.
     */
    @Deprecated(since = "QIM-OUTBOX-SPEC-001", forRemoval = true)
    USER_WITHDRAWN;

    // ─── 신규 4종 이벤트 판단 유틸 ─────────────────────────────────────────

    /**
     * QIM-OUTBOX-SPEC-001 신규 이벤트 타입 4종 (프로비저닝 트리거 대상).
     * QimEventConsumer.isProvisioningTriggerEvent()와 동일한 기준.
     *
     * @param eventType Kafka 메시지의 eventType 필드
     * @return 프로비저닝 트리거 대상이면 true
     */
    public static boolean isProvisioningTrigger(String eventType) {
        return PERSONAL_MEMBER_REGISTERED.name().equals(eventType)
            || PERSONAL_MEMBER_CONVERTED.name().equals(eventType)
            || BIZ_MEMBER_REGISTERED.name().equals(eventType)
            || BIZ_MEMBER_CONVERTED.name().equals(eventType);
    }

    /**
     * Q-IM 이벤트 타입 문자열로부터 변환 (null 안전).
     * 구 이벤트 타입(USER_REGISTERED, BIZ_CONVERTED)도 변환 가능하나 @Deprecated.
     */
    public static ProvisioningEventType fromString(String value) {
        if (value == null) return null;
        try {
            return ProvisioningEventType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** provisioning_outbox.event_type 컬럼용 문자열 반환 */
    public String toColumnValue() {
        return this.name();
    }
}

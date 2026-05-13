package kr.go.smes.ido.provision.dto;

/**
 * 프로비저닝 이벤트 타입 열거형
 *
 * <p>Q-IM Kafka 이벤트 → ProvisioningService 트리거 매핑:
 * <ul>
 *   <li>USER_REGISTERED  — 일반 회원가입</li>
 *   <li>BIZ_CONVERTED    — 기업회원 전환</li>
 *   <li>USER_UPDATED     — 프로필 변경 (추후 확장)</li>
 *   <li>USER_WITHDRAWN   — 회원 탈퇴 (계정 삭제 연동)</li>
 * </ul>
 *
 * <p>provisioning_outbox.event_type 컬럼 값과 일치해야 함.
 * (V15 CHECK 제약: 'USER_REGISTERED','BIZ_CONVERTED','USER_UPDATED','USER_WITHDRAWN')
 */
public enum ProvisioningEventType {

    /** 일반 회원가입 — QimEventConsumer USER_REGISTERED 이벤트 */
    USER_REGISTERED,

    /** 기업회원 전환 — QimEventConsumer BIZ_CONVERTED 이벤트 */
    BIZ_CONVERTED,

    /** 프로필 변경 — 추후 Sprint 15+ 확장 */
    USER_UPDATED,

    /** 회원 탈퇴 — 계정 삭제 연동 (추후 Sprint 15+ 확장) */
    USER_WITHDRAWN;

    /** Q-IM 이벤트 타입 문자열로부터 변환 (null 안전) */
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

package io.github.hipstermin.idem.hub.provision;

/**
 * 전 기관 프로비저닝 서비스 인터페이스
 *
 * <p><b>Sprint 14~17 핵심 서비스</b>:<br>
 * QIM-OUTBOX-SPEC-001 5종 이벤트 발생 시 등록된 모든 기관(최대 68개)에
 * 사용자 존재를 병렬 HTTP로 알림.
 *
 * <p><b>데이터 흐름</b>:
 * <pre>
 *   QimEventConsumer.isProvisioningTriggerEvent(eventType)  [4종 신규 이벤트만]
 *     → triggerProvisioning(qimUserId, eventType, sourceEventId, correlationId)
 *       → ProvisioningServiceImpl.insertOutbox(eventType)
 *         → provisioning_outbox.event_type  [V18 CHECK 제약: 신규 5종 + 구 4종]
 *           → ProvisioningOutboxRelay (at-least-once 재시도)
 *             → 기관 HTTPS POST  [Virtual Thread, 최대 68개 병렬]
 * </pre>
 *
 * <p><b>허용 eventType 값 (QIM-OUTBOX-SPEC-001, V18 CHECK 제약 기준)</b>:
 * <ul>
 *   <li>PERSONAL_MEMBER_REGISTERED — 개인 신규 가입 (isTransfer=false, isCorporate=false)</li>
 *   <li>PERSONAL_MEMBER_CONVERTED  — 개인 전환     (isTransfer=true,  isCorporate=false)</li>
 *   <li>BIZ_MEMBER_REGISTERED      — 기업 신규 가입 (isTransfer=false, isCorporate=true)</li>
 *   <li>BIZ_MEMBER_CONVERTED       — 기업 전환     (isTransfer=true,  isCorporate=true)</li>
 *   <li>MEMBER_WITHDRAWN           — 회원 탈퇴</li>
 *   <li>USER_REGISTERED, BIZ_CONVERTED, USER_WITHDRAWN (@Deprecated, forRemoval=true)</li>
 * </ul>
 *
 * <p><b>설계 원칙</b>:
 * <ul>
 *   <li>at-least-once: 실패 기관은 provisioning_outbox PENDING → 릴레이 재시도</li>
 *   <li>Virtual Thread: JDK 21 가상 스레드 68개 병렬 HTTP (OS 스레드 블로킹 없음)</li>
 *   <li>PII 최소화: qimUserId + identity_hash만 기관에 전달 (원문 미포함)</li>
 *   <li>멱등성: (idempotency_key, agency_code) UNIQUE → 중복 트리거 안전</li>
 * </ul>
 *
 * @see ProvisioningEventType
 * @see io.github.hipstermin.idem.hub.kafka.QimEventConsumer
 */
public interface ProvisioningService {

    /**
     * 전 기관 프로비저닝 트리거
     *
     * <p>동작 흐름:
     * <ol>
     *   <li>중복 트리거 체크 (sourceEventId 기반)</li>
     *   <li>PROVISIONING 엔드포인트 활성 기관 전체 조회</li>
     *   <li>멱등성 키(idempotency_key = UUID v7) 생성</li>
     *   <li>각 기관에 Virtual Thread로 병렬 HTTP POST</li>
     *   <li>성공 → provisioning_outbox INSERT + markCompleted</li>
     *   <li>실패 → provisioning_outbox INSERT(PENDING) → 릴레이 재시도</li>
     * </ol>
     *
     * @param qimUserId     대상 사용자 ID (OnePass UUID v7)
     * @param eventType     이벤트 타입 (ProvisioningEventType.name() 값.
     *                      QIM-OUTBOX-SPEC-001 신규 5종 또는 @Deprecated 구 타입.
     *                      신규 코드는 반드시 ProvisioningEventType 신규 5종 사용)
     * @param sourceEventId 트리거한 Q-IM Kafka 이벤트 ID (중복 방지)
     * @param correlationId X-Correlation-ID (흐름 추적)
     */
    void triggerProvisioning(String qimUserId, String eventType, String sourceEventId, String correlationId);
}

package kr.go.smes.ido.provision;

/**
 * 전 기관 프로비저닝 서비스 인터페이스
 *
 * <p>Sprint 14 핵심 서비스:
 * 회원가입(USER_REGISTERED) / 기업회원 전환(BIZ_CONVERTED) 발생 시
 * 등록된 모든 기관(최대 68개)에 사용자 존재를 병렬 HTTP로 알림.
 *
 * <p>설계 원칙:
 * <ul>
 *   <li>at-least-once: 실패 기관은 provisioning_outbox에 PENDING 저장 → 릴레이 재시도</li>
 *   <li>Virtual Thread: JDK 21 가상 스레드로 68개 병렬 HTTP 호출 (OS 스레드 블로킹 없음)</li>
 *   <li>PII 최소화: qimUserId + identity_hash만 기관에 전달</li>
 *   <li>멱등성: (idempotency_key, agency_code) UNIQUE → 중복 트리거 안전</li>
 * </ul>
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
     * @param eventType     이벤트 타입 ("USER_REGISTERED" / "BIZ_CONVERTED")
     * @param sourceEventId 트리거한 Q-IM Kafka 이벤트 ID (중복 방지)
     * @param correlationId X-Correlation-ID (흐름 추적)
     */
    void triggerProvisioning(String qimUserId, String eventType, String sourceEventId, String correlationId);
}

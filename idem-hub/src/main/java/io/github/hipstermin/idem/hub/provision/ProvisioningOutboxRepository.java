package io.github.hipstermin.idem.hub.provision;

import java.time.Instant;
import java.util.List;

/**
 * 프로비저닝 아웃박스 리포지토리 인터페이스
 *
 * <p>ido.provisioning_outbox 테이블 (V15 Flyway 생성) 접근.
 * JdbcTemplate 구현 — FOR UPDATE SKIP LOCKED 직접 제어.
 *
 * <p>at-least-once 보장 패턴:
 * <ol>
 *   <li>초기 HTTP 발송 성공 → {@code markCompleted()}</li>
 *   <li>실패 → {@code incrementRetryWithBackoff()} (지수 백오프)</li>
 *   <li>maxRetry 초과 → {@code markDeadLetter()}</li>
 *   <li>릴레이 재시도 → {@code findPendingBatch()} (FOR UPDATE SKIP LOCKED)</li>
 * </ol>
 */
public interface ProvisioningOutboxRepository {

    /**
     * 프로비저닝 레코드 삽입
     * (idempotency_key, agency_code) UNIQUE 제약으로 중복 삽입 무시.
     *
     * @param record 삽입할 레코드
     * @return 실제 삽입된 행 수 (0이면 중복 — 정상 처리)
     */
    int insert(ProvisioningOutboxRecord record);

    /**
     * PENDING 상태이고 next_retry_at ≤ NOW() 인 레코드 배치 조회
     * FOR UPDATE SKIP LOCKED — 병렬 스케줄러 간 중복 처리 방지.
     *
     * @param batchSize 최대 조회 건수
     * @return PENDING 레코드 목록
     */
    List<ProvisioningOutboxRecord> findPendingBatch(int batchSize);

    /**
     * HTTP 전송 성공 → COMPLETED 상태로 변경
     *
     * @param id 레코드 ID (PK)
     */
    void markCompleted(String id);

    /**
     * HTTP 전송 실패 → retry_count 증가 + 지수 백오프 next_retry_at 갱신
     *
     * <p>백오프 스케줄:
     * <ul>
     *   <li>retry_count = 0 → 1분 후</li>
     *   <li>retry_count = 1 → 5분 후</li>
     *   <li>retry_count ≥ 2 → 30분 후</li>
     * </ul>
     *
     * @param id           레코드 ID
     * @param errorMessage 실패 메시지 (최대 2000자)
     */
    void incrementRetryWithBackoff(String id, String errorMessage);

    /**
     * 최대 재시도 초과 → DEAD_LETTER 상태로 변경
     *
     * @param id           레코드 ID
     * @param errorMessage 최종 실패 메시지
     */
    void markDeadLetter(String id, String errorMessage);

    /**
     * 특정 사용자의 DEAD_LETTER 건수 조회 (운영 모니터링용)
     *
     * @param qimUserId 사용자 ID
     * @return DEAD_LETTER 상태 레코드 수
     */
    int countDeadLetterByUser(String qimUserId);

    /**
     * 특정 이벤트 소스로 이미 삽입된 레코드 수 조회 (중복 트리거 방지)
     *
     * @param sourceEventId Q-IM 이벤트 ID
     * @return 해당 소스 이벤트로 생성된 레코드 수
     */
    int countBySourceEventId(String sourceEventId);

    /**
     * next_retry_at 을 특정 시각으로 갱신 (테스트/운영 수동 재시도용)
     *
     * @param id          레코드 ID
     * @param nextRetryAt 다음 재시도 시각
     */
    void reschedule(String id, Instant nextRetryAt);

    /**
     * idempotency_key + agency_code로 레코드 ID(PK) 조회
     * insertOutbox 후 생성된 레코드의 UUID를 반환하기 위해 사용.
     *
     * @param idempotencyKey 멱등성 키
     * @param agencyCode     기관 코드
     * @return 레코드 ID (PK), 없으면 null
     */
    String findIdByIdempotencyKeyAndAgency(String idempotencyKey, String agencyCode);
}

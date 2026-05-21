-- ============================================================
-- V10: ido.outbox — next_retry_at 컬럼 추가 (QIM-OUTBOX-SPEC-001)
-- ============================================================
-- 목적:
--   IdoOutboxRepository.incrementRetryWithBackoff() 에서 지수 백오프 재시도를
--   지원하기 위해 next_retry_at 컬럼을 추가한다.
--
-- 변경 전 문제:
--   IdoOutboxRelay 가 실패한 PENDING 레코드를 즉시 재조회하여
--   Kafka 재시도 폭풍(Thundering Herd) 이 발생할 수 있었다.
--
-- 변경 후 효과:
--   발행 실패 시 next_retry_at = NOW() + 2^retryCount 초 로 설정하고,
--   findPendingBatch WHERE 절에 (next_retry_at IS NULL OR next_retry_at <= NOW())
--   조건을 추가하여 백오프 대기 중인 레코드를 건너뛴다.
--
-- 영향:
--   - ido.outbox 테이블 컬럼 1개 추가 (기존 PENDING 레코드 영향 없음 — NULL 기본값)
--   - 신규 인덱스 1개 추가 (폴링 성능 보장)
-- ============================================================

-- 1. next_retry_at 컬럼 추가 (NULL 허용: 최초 시도 대상은 NULL)
ALTER TABLE ido.outbox
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ NULL;

COMMENT ON COLUMN ido.outbox.next_retry_at IS
    '지수 백오프 재시도 예약 시각. NULL = 즉시 재시도 대상. '
    'incrementRetryWithBackoff() 에서 NOW() + 2^retryCount 초로 설정.';

-- 2. 기존 PENDING 레코드의 next_retry_at = NULL 유지
--    (NULL 이면 즉시 재시도 대상으로 처리되므로 기존 동작 보존)

-- 3. 복합 인덱스 추가: (status, next_retry_at, created_at)
--    findPendingBatch / findPendingBatchExcludingTopics / findPendingBatchByTopic 의
--    WHERE status='PENDING' AND (next_retry_at IS NULL OR next_retry_at <= NOW())
--    ORDER BY created_at ASC 쿼리 성능 최적화
CREATE INDEX IF NOT EXISTS idx_ido_outbox_pending_retry
    ON ido.outbox (status, next_retry_at, created_at)
    WHERE status = 'PENDING';

-- 4. topic 컬럼 인덱스 추가: findPendingBatchByTopic / findPendingBatchExcludingTopics
--    WHERE topic = ? 또는 topic NOT IN (...) 필터 성능 최적화
CREATE INDEX IF NOT EXISTS idx_ido_outbox_topic
    ON ido.outbox (topic, status, created_at)
    WHERE status = 'PENDING';

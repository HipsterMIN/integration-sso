-- ============================================================================
-- V5 (1.1): 인가 아웃박스 피드
--   · topic 기본값을 코드(AuthzOutboxService.TOPIC = idem.authz.assignment.events)와 맞춘다
--     (V2 의 'idem_authz.assignment.events' 는 코드가 topic 을 항상 명시해 실제로 쓰인 적이 없다)
--   · hub 폴링 피드의 키셋 순회용 인덱스 (topic, created_at, event_id)
-- ============================================================================

ALTER TABLE idem_authz.authz_outbox
    ALTER COLUMN topic SET DEFAULT 'idem.authz.assignment.events';

UPDATE idem_authz.authz_outbox
   SET topic = 'idem.authz.assignment.events'
 WHERE topic = 'idem_authz.assignment.events';

CREATE INDEX IF NOT EXISTS idx_authz_outbox_feed
    ON idem_authz.authz_outbox (topic, created_at, event_id);

COMMENT ON TABLE idem_authz.authz_outbox IS '인가 이벤트 트랜잭셔널 아웃박스 — Kafka 릴레이(선택) 발행 + hub 폴링 피드(GET /api/v1/internal/authz/events). 1.1 부터 할당(ASSIGNED/UNASSIGNED/ASSIGNMENT_EXPIRED)도 적재.';

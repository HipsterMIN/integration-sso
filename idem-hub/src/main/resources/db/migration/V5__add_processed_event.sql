-- ============================================================
-- IdO Schema V5 : 멱등 컨슈머 보조 테이블
-- 설계서 §16.3 / §11.5.5 참조
--
-- IdempotentEventStore (idem_hub.processed_event):
--   event_id + consumer_group 복합키 기반 중복 처리 차단.
--   Q-IM(MariaDB) 의 processed_event 와 동일한 역할을
--   IdO(PostgreSQL) 측에서 자체 운영.
-- ============================================================

-- ──────────────────────────────────────────────────────────────
-- 1. 처리 완료 이벤트 (idem_hub.processed_event)
--    §16.3 멱등 컨슈머 중복 방지.
--    at-least-once 구간에서 eventId 기준 중복 처리 차단.
--    컨슈머 그룹: ido-qim-consumer / ido-qsign-consumer / ido-qim-sp-member-consumer
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS idem_hub.processed_event (
    event_id       VARCHAR(36)   NOT NULL,
    consumer_group VARCHAR(100)  NOT NULL,
    event_type     VARCHAR(80),
    result_code    VARCHAR(50),       -- OK / SKIPPED / ERROR
    processed_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ido_processed_event
        PRIMARY KEY (event_id, consumer_group)
);

CREATE INDEX IF NOT EXISTS idx_ido_processed_event_group
    ON idem_hub.processed_event (consumer_group, processed_at DESC);

COMMENT ON TABLE  idem_hub.processed_event
    IS '§16.3 멱등 컨슈머 중복 처리 방지 — at-least-once 차단';
COMMENT ON COLUMN idem_hub.processed_event.event_id
    IS 'DomainEvent.eventId (UUID)';
COMMENT ON COLUMN idem_hub.processed_event.consumer_group
    IS 'Kafka consumer group ID (ido-qim-consumer 등)';
COMMENT ON COLUMN idem_hub.processed_event.result_code
    IS 'OK = 정상 처리 / SKIPPED = 버전 역전·중복 / ERROR = 처리 실패';

-- ──────────────────────────────────────────────────────────────
-- 2. 이벤트 버전 추적 (idem_hub.last_event_version)
--    §11.5.5 Ordered Consumer 패턴.
--    컨슈머 그룹 + qimUserId 기준 마지막 처리된 이벤트 버전 추적.
--    version <= stored → 중복 무시 / gap detected → Selective Pull 트리거.
--
--    참고: Redis LastEventVersionStoreImpl 이 1차 저장소이며,
--          이 테이블은 Redis 장애 복구용 fallback 및 감사 목적.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS idem_hub.last_event_version (
    consumer_group   VARCHAR(100)  NOT NULL,
    aggregate_id     VARCHAR(36)   NOT NULL, -- qimUserId
    last_version     BIGINT        NOT NULL,
    last_event_id    VARCHAR(36)   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ido_last_event_version
        PRIMARY KEY (consumer_group, aggregate_id)
);

CREATE INDEX IF NOT EXISTS idx_ido_last_event_version_updated
    ON idem_hub.last_event_version (updated_at DESC);

COMMENT ON TABLE  idem_hub.last_event_version
    IS '§11.5.5 Ordered Consumer 버전 추적 (Redis fallback)';
COMMENT ON COLUMN idem_hub.last_event_version.aggregate_id
    IS 'qimUserId — partitionKey 와 동일';

-- ============================================================
-- Q-Sign Schema V4 : 멱등 컨슈머 테이블 추가
-- 설계서 §16.3.1 GAP-QS-03 — Q-Sign 멱등 컨슈머 계약
-- ============================================================

-- ──────────────────────────────────────────────────────────────
-- 1. Q-Sign 멱등 컨슈머 (idem_gate.processed_event)
--    at-least-once 중복 처리 방지
--    IdO의 idem_hub.processed_event 와 동일 구조 (스키마만 상이)
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS idem_gate.processed_event (
    event_id        VARCHAR(36)   NOT NULL,
    consumer_group  VARCHAR(100)  NOT NULL,
    event_type      VARCHAR(80),
    result_code     VARCHAR(50),                  -- OK / SKIPPED / ERROR
    processed_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_qsign_processed_event PRIMARY KEY (event_id, consumer_group)
);

CREATE INDEX idx_qsign_processed_event_group
    ON idem_gate.processed_event (consumer_group, processed_at DESC);

COMMENT ON TABLE  idem_gate.processed_event               IS '§16.3.1 Q-Sign 멱등 컨슈머 — at-least-once 중복 처리 방지';
COMMENT ON COLUMN idem_gate.processed_event.event_id      IS 'DomainEvent.eventId (UUID)';
COMMENT ON COLUMN idem_gate.processed_event.consumer_group IS 'Kafka 컨슈머 그룹 ID';
COMMENT ON COLUMN idem_gate.processed_event.result_code   IS 'OK=정상처리 / SKIPPED=중복/버전오류 / ERROR=처리실패';

-- ──────────────────────────────────────────────────────────────
-- 2. Q-Sign 이벤트 버전 추적 (idem_gate.last_event_version)
--    §11.5.5 Ordered Consumer 패턴 — 버전 역전 방지
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS idem_gate.last_event_version (
    consumer_group  VARCHAR(100)  NOT NULL,
    aggregate_id    VARCHAR(36)   NOT NULL,       -- qimUserId 또는 identifierHash
    last_version    BIGINT        NOT NULL,
    last_event_id   VARCHAR(36)   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_qsign_last_event_version PRIMARY KEY (consumer_group, aggregate_id)
);

CREATE INDEX idx_qsign_last_event_version_agg
    ON idem_gate.last_event_version (aggregate_id, updated_at DESC);

COMMENT ON TABLE  idem_gate.last_event_version                IS '§11.5.5 Q-Sign 이벤트 버전 추적 — 순서 역전 방지';
COMMENT ON COLUMN idem_gate.last_event_version.consumer_group IS 'Kafka 컨슈머 그룹 ID';
COMMENT ON COLUMN idem_gate.last_event_version.aggregate_id   IS 'qimUserId 또는 identifierHash';

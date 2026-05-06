-- ============================================================
-- Q-IM V2 : 멱등 컨슈머 / 이벤트 버전 추적
-- 설계서 §11.5.5 Ordered Consumer / §16.3 멱등 소비 기반
-- ============================================================

-- ──────────────────────────────────────────────────────────────
-- 1. 이벤트 버전 추적 (LastEventVersion)
--    §11.5.5 Ordered Consumer 패턴.
--    컨슈머 그룹이 처리한 qimUserId 기준 마지막 버전을 저장.
--    version <= stored → 중복 무시 / version > stored → 처리 후 갱신.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE qim.last_event_version (
    consumer_group       VARCHAR(100)  NOT NULL,
    aggregate_id         VARCHAR(36)   NOT NULL,          -- qimUserId
    last_version         BIGINT        NOT NULL,
    last_event_id        VARCHAR(36)   NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_last_event_version PRIMARY KEY (consumer_group, aggregate_id)
);

CREATE INDEX idx_last_event_version_updated ON qim.last_event_version (updated_at DESC);

COMMENT ON TABLE  qim.last_event_version                IS '§11.5.5 Ordered Consumer 버전 추적';
COMMENT ON COLUMN qim.last_event_version.consumer_group IS 'Kafka 컨슈머 그룹 ID';
COMMENT ON COLUMN qim.last_event_version.aggregate_id   IS 'qimUserId';
COMMENT ON COLUMN qim.last_event_version.last_version   IS '처리 완료된 최대 event_version – 이 값 이하 무시';

-- ──────────────────────────────────────────────────────────────
-- 2. 처리 완료 이벤트 (ProcessedEvent)
--    §16.3 멱등 컨슈머 중복 방지 테이블.
--    event_id 기준 at-least-once 중복 처리 차단.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE qim.processed_event (
    event_id             VARCHAR(36)   NOT NULL,
    consumer_group       VARCHAR(100)  NOT NULL,
    event_type           VARCHAR(80),
    result_code          VARCHAR(50),                     -- OK / SKIPPED / ERROR
    processed_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id, consumer_group)
);

CREATE INDEX idx_processed_event_group ON qim.processed_event (consumer_group, processed_at DESC);

COMMENT ON TABLE qim.processed_event IS '§16.3 멱등 컨슈머 중복 처리 방지 – at-least-once 차단';

-- ──────────────────────────────────────────────────────────────
-- 3. 스냅샷 메타 (SnapshotMeta)
--    §11.5.6 Compacted Snapshot Topic 발행 이력.
--    어떤 event_version 기준 스냅샷이 발행되었는지 추적.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE qim.snapshot_meta (
    snapshot_id          VARCHAR(36)   NOT NULL,
    qim_user_id          VARCHAR(36)   NOT NULL,
    snapshot_version     BIGINT        NOT NULL,          -- 스냅샷 기준 event_version
    topic                VARCHAR(200)  NOT NULL DEFAULT 'qim.user.snapshot',
    status               VARCHAR(20)   NOT NULL DEFAULT 'PUBLISHED',
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_snapshot_meta PRIMARY KEY (snapshot_id),
    CONSTRAINT uq_snapshot_user_version UNIQUE (qim_user_id, snapshot_version),
    CONSTRAINT fk_snapshot_user
        FOREIGN KEY (qim_user_id) REFERENCES qim.qim_user (qim_user_id)
);

CREATE INDEX idx_snapshot_meta_user ON qim.snapshot_meta (qim_user_id, snapshot_version DESC);

COMMENT ON TABLE  qim.snapshot_meta                  IS '§11.5.6 Compacted Snapshot 발행 이력';
COMMENT ON COLUMN qim.snapshot_meta.snapshot_version IS '스냅샷 기준 event_version – 이후 delta 이벤트 재생 시작점';

-- ============================================================
-- Q-IM V2 : 멱등 컨슈머 / 이벤트 버전 추적
-- 설계서 §11.5.5 Ordered Consumer / §16.3 멱등 소비 기반
--
-- [DB 변경] PostgreSQL → MariaDB
--   TIMESTAMPTZ  → DATETIME(6)
--   WHERE partial index → 일반 인덱스 (MariaDB 미지원)
-- ============================================================

-- ──────────────────────────────────────────────────────────────
-- 1. 이벤트 버전 추적 (last_event_version)
--    §11.5.5 Ordered Consumer 패턴.
--    컨슈머 그룹이 처리한 qimUserId 기준 마지막 버전을 저장.
--    version <= stored → 중복 무시 / version > stored → 처리 후 갱신.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS last_event_version (
    consumer_group    VARCHAR(100)  NOT NULL,
    aggregate_id      VARCHAR(36)   NOT NULL COMMENT 'qimUserId',
    last_version      BIGINT        NOT NULL,
    last_event_id     VARCHAR(36)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_last_event_version PRIMARY KEY (consumer_group, aggregate_id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='§11.5.5 Ordered Consumer 버전 추적';

CREATE INDEX idx_last_event_version_updated ON last_event_version (updated_at DESC);

-- ──────────────────────────────────────────────────────────────
-- 2. 처리 완료 이벤트 (processed_event)
--    §16.3 멱등 컨슈머 중복 방지 테이블.
--    event_id 기준 at-least-once 중복 처리 차단.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS processed_event (
    event_id          VARCHAR(36)   NOT NULL,
    consumer_group    VARCHAR(100)  NOT NULL,
    event_type        VARCHAR(80),
    result_code       VARCHAR(50)   COMMENT 'OK / SKIPPED / ERROR',
    processed_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id, consumer_group)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='§16.3 멱등 컨슈머 중복 처리 방지 – at-least-once 차단';

CREATE INDEX idx_processed_event_group ON processed_event (consumer_group, processed_at DESC);

-- ──────────────────────────────────────────────────────────────
-- 3. 스냅샷 메타 (snapshot_meta)
--    §11.5.6 Compacted Snapshot Topic 발행 이력.
--    어떤 event_version 기준 스냅샷이 발행되었는지 추적.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS snapshot_meta (
    snapshot_id       VARCHAR(36)   NOT NULL,
    qim_user_id       VARCHAR(36)   NOT NULL,
    snapshot_version  BIGINT        NOT NULL COMMENT '스냅샷 기준 event_version',
    topic             VARCHAR(200)  NOT NULL DEFAULT 'qim.user.snapshot',
    status            VARCHAR(20)   NOT NULL DEFAULT 'PUBLISHED',
    created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_snapshot_meta          PRIMARY KEY (snapshot_id),
    CONSTRAINT uq_snapshot_user_version  UNIQUE (qim_user_id, snapshot_version),
    CONSTRAINT fk_snapshot_user          FOREIGN KEY (qim_user_id)
        REFERENCES qim_user (qim_user_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='§11.5.6 Compacted Snapshot 발행 이력';

CREATE INDEX idx_snapshot_meta_user ON snapshot_meta (qim_user_id, snapshot_version DESC);

-- ──────────────────────────────────────────────────────────────────────────────
-- 통합인증 플랫폼 PoC DB 초기화
-- 각 서비스별 스키마 분리 (설계서 SoR 4축 반영)
-- ──────────────────────────────────────────────────────────────────────────────

-- Q-Sign 스키마 (인증 SoR)
CREATE SCHEMA IF NOT EXISTS qsign;

-- Q-IM 스키마 (식별·매핑 SoR)
CREATE SCHEMA IF NOT EXISTS qim;

-- IdO 스키마 (정책 SoR)
CREATE SCHEMA IF NOT EXISTS ido;

-- Agency-Stub 스키마
CREATE SCHEMA IF NOT EXISTS agency_stub;

-- ── Q-Sign ──────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS qsign.auth_result (
    auth_result_id   VARCHAR(36)  PRIMARY KEY,
    correlation_id   VARCHAR(36)  NOT NULL,
    auth_level       VARCHAR(10)  NOT NULL,               -- L1 / L2 / L3
    provider_code    VARCHAR(50)  NOT NULL,
    provider_tx_id   VARCHAR(200),
    identifier_hash  VARCHAR(200) NOT NULL,
    verification_result VARCHAR(10) NOT NULL,             -- SUCCESS / FAIL
    session_ref      VARCHAR(36),
    signature        TEXT,
    authenticated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_auth_result_identifier_hash ON qsign.auth_result(identifier_hash);
CREATE INDEX IF NOT EXISTS idx_auth_result_correlation_id  ON qsign.auth_result(correlation_id);

-- 잠금/재시도 (설계서 9.6절)
CREATE TABLE IF NOT EXISTS qsign.auth_lock (
    lock_key         VARCHAR(300) PRIMARY KEY,            -- identifierHash:providerCode
    attempt_count    INT          NOT NULL DEFAULT 0,
    locked           BOOLEAN      NOT NULL DEFAULT FALSE,
    locked_at        TIMESTAMPTZ,
    unlock_at        TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ── Q-IM ────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS qim.qim_user (
    qim_user_id      VARCHAR(36)  PRIMARY KEY,
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / SUSPENDED / WITHDRAWN
    event_version    BIGINT       NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS qim.auth_mean_mapping (
    mapping_id       VARCHAR(36)  PRIMARY KEY,
    qim_user_id      VARCHAR(36)  NOT NULL REFERENCES qim.qim_user(qim_user_id),
    provider_code    VARCHAR(50)  NOT NULL,
    identifier_hash  VARCHAR(200) NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / REVOKED
    linked_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    revoked_at       TIMESTAMPTZ,
    UNIQUE (identifier_hash)   -- 동일 identifierHash → 단일 사용자만 허용
);
CREATE INDEX IF NOT EXISTS idx_mapping_qim_user_id      ON qim.auth_mean_mapping(qim_user_id);
CREATE INDEX IF NOT EXISTS idx_mapping_identifier_hash  ON qim.auth_mean_mapping(identifier_hash);

CREATE TABLE IF NOT EXISTS qim.user_profile (
    qim_user_id      VARCHAR(36)  PRIMARY KEY REFERENCES qim.qim_user(qim_user_id),
    name_masked      VARCHAR(100),
    mobile_masked    VARCHAR(20),
    nationality_type VARCHAR(10),
    ci               VARCHAR(200),
    di               VARCHAR(200),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Transactional Outbox (설계서 10.5.2절)
CREATE TABLE IF NOT EXISTS qim.outbox (
    event_id         VARCHAR(36)  PRIMARY KEY,
    event_type       VARCHAR(50)  NOT NULL,
    partition_key    VARCHAR(36)  NOT NULL,               -- qimUserId = Kafka partitionKey
    event_version    BIGINT       NOT NULL,
    payload          TEXT         NOT NULL,               -- JSON 직렬화
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING / PUBLISHED / FAILED
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at     TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_outbox_status     ON qim.outbox(status, created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_partition  ON qim.outbox(partition_key);

-- ── IdO ─────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS ido.agency_meta (
    agency_code         VARCHAR(50)  PRIMARY KEY,
    official_name       VARCHAR(200) NOT NULL,
    min_auth_level      VARCHAR(10)  NOT NULL DEFAULT 'L1',
    policy_version      VARCHAR(20)  NOT NULL DEFAULT '1.0',
    api_key_hash        VARCHAR(200),
    callback_whitelist  TEXT,                              -- JSON 배열
    allowed_attributes  TEXT,                              -- JSON 배열
    maintenance_windows TEXT,                              -- JSON 배열
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 기관 스텁 샘플 데이터
INSERT INTO ido.agency_meta (agency_code, official_name, min_auth_level, policy_version, active)
VALUES ('AGENCY_STUB_001', '테스트 기관 (PoC Stub)', 'L1', '1.0', TRUE)
ON CONFLICT DO NOTHING;

-- Handoff Ticket 감사 이력 (Redis가 주 저장소, DB는 감사용)
CREATE TABLE IF NOT EXISTS ido.handoff_audit (
    ticket_id        VARCHAR(36)  PRIMARY KEY,
    correlation_id   VARCHAR(36)  NOT NULL,
    agency_code      VARCHAR(50)  NOT NULL,
    qim_user_id      VARCHAR(36)  NOT NULL,
    auth_result_id   VARCHAR(36)  NOT NULL,
    auth_level       VARCHAR(10)  NOT NULL,
    state            VARCHAR(20)  NOT NULL,               -- ISSUED/CONSUMED/EXPIRED/REVOKED
    revoke_reason    VARCHAR(100),
    issued_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    consumed_at      TIMESTAMPTZ,
    expires_at       TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_handoff_audit_agency    ON ido.handoff_audit(agency_code, issued_at);
CREATE INDEX IF NOT EXISTS idx_handoff_audit_qim_user  ON ido.handoff_audit(qim_user_id);
CREATE INDEX IF NOT EXISTS idx_handoff_audit_correlation ON ido.handoff_audit(correlation_id);

-- ── Agency-Stub ─────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS agency_stub.agency_user (
    agency_user_id   VARCHAR(36)  PRIMARY KEY,
    agency_subject_id VARCHAR(200) NOT NULL UNIQUE,        -- Q-IM 기반 기관향 Projection
    qim_user_id      VARCHAR(36)  NOT NULL,               -- Q-IM 정본 참조 (정본 아님)
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Idempotent Consumer 중복 방지 (설계서 16.3절)
CREATE TABLE IF NOT EXISTS agency_stub.processed_event (
    event_id         VARCHAR(36)  PRIMARY KEY,
    consumer_name    VARCHAR(100) NOT NULL,
    result_code      VARCHAR(50),
    processed_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

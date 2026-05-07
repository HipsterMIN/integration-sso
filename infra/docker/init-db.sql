-- ──────────────────────────────────────────────────────────────────────────────
-- 통합인증 플랫폼 PoC DB 초기화 — PostgreSQL 전용
--
-- [DB 분리 구조]
--   PostgreSQL 16 (이 파일)  : Q-Sign / IdO / agency-stub / Keycloak
--   MariaDB 11    (별도 컨테이너): Q-IM 전용
--     → 운영: NHN Cloud RDS for MariaDB
--     → PoC:  infra/docker/docker-compose.yml의 mariadb 서비스
--     → Q-IM 스키마/테이블: Flyway 자동 생성 (q-im/src/main/resources/db/migration/)
--
-- 각 서비스별 스키마 분리 (설계서 SoR 4축 반영)
-- ──────────────────────────────────────────────────────────────────────────────

-- Q-Sign 스키마 (인증 SoR)
CREATE SCHEMA IF NOT EXISTS qsign;

-- IdO 스키마 (정책 SoR)
CREATE SCHEMA IF NOT EXISTS ido;

-- Agency-Stub 스키마
CREATE SCHEMA IF NOT EXISTS agency_stub;

-- Keycloak 스키마 (§10 — Keycloak DB 격리, 테이블은 Keycloak이 자동 생성)
CREATE SCHEMA IF NOT EXISTS keycloak;

-- ※ Q-IM 스키마 제거 — MariaDB(qim DB)로 이관
--   PostgreSQL에 qim 스키마/테이블을 생성하지 않음


-- ── Q-Sign ──────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS qsign.auth_result (
    auth_result_id      VARCHAR(36)  PRIMARY KEY,
    correlation_id      VARCHAR(36)  NOT NULL,
    auth_level          VARCHAR(10)  NOT NULL,               -- L1 / L2 / L3
    provider_code       VARCHAR(50)  NOT NULL,
    provider_tx_id      VARCHAR(200),
    identifier_hash     VARCHAR(200) NOT NULL,
    verification_result VARCHAR(10)  NOT NULL,               -- SUCCESS / FAIL
    session_ref         VARCHAR(36),
    signature           TEXT,
    authenticated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_auth_result_identifier_hash ON qsign.auth_result (identifier_hash);
CREATE INDEX IF NOT EXISTS idx_auth_result_correlation_id  ON qsign.auth_result (correlation_id);

-- 잠금/재시도 (설계서 9.6절)
CREATE TABLE IF NOT EXISTS qsign.auth_lock (
    lock_key         VARCHAR(300) PRIMARY KEY,               -- identifierHash:providerCode
    attempt_count    INT          NOT NULL DEFAULT 0,
    locked           BOOLEAN      NOT NULL DEFAULT FALSE,
    locked_at        TIMESTAMPTZ,
    unlock_at        TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);


-- ── IdO ─────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS ido.agency_meta (
    agency_code         VARCHAR(50)  PRIMARY KEY,
    official_name       VARCHAR(200) NOT NULL,
    min_auth_level      VARCHAR(10)  NOT NULL DEFAULT 'L1',
    policy_version      VARCHAR(20)  NOT NULL DEFAULT '1.0',
    api_key_hash        VARCHAR(200),
    callback_whitelist  TEXT,                                -- JSON 배열
    allowed_attributes  TEXT,                                -- JSON 배열
    maintenance_windows TEXT,                                -- JSON 배열
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
    state            VARCHAR(20)  NOT NULL,                  -- ISSUED/CONSUMED/EXPIRED/REVOKED
    revoke_reason    VARCHAR(100),
    issued_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    consumed_at      TIMESTAMPTZ,
    expires_at       TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_handoff_audit_agency      ON ido.handoff_audit (agency_code, issued_at);
CREATE INDEX IF NOT EXISTS idx_handoff_audit_qim_user    ON ido.handoff_audit (qim_user_id);
CREATE INDEX IF NOT EXISTS idx_handoff_audit_correlation ON ido.handoff_audit (correlation_id);


-- ── Agency-Stub ─────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS agency_stub.agency_user (
    agency_user_id    VARCHAR(36)  PRIMARY KEY,
    agency_subject_id VARCHAR(200) NOT NULL UNIQUE,          -- Q-IM 기반 기관향 Projection
    qim_user_id       VARCHAR(36)  NOT NULL,                 -- Q-IM 정본 참조 (정본 아님)
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Idempotent Consumer 중복 방지 (설계서 16.3절)
CREATE TABLE IF NOT EXISTS agency_stub.processed_event (
    event_id         VARCHAR(36)  PRIMARY KEY,
    consumer_name    VARCHAR(100) NOT NULL,
    result_code      VARCHAR(50),
    processed_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

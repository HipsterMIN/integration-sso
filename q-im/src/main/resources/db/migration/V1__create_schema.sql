-- ============================================================
-- Q-IM Schema : 사용자 식별 SoR (Source of Record)
-- 설계서 §10 Q-IM 책임 범위 기반
-- ============================================================

CREATE SCHEMA IF NOT EXISTS qim;

-- ──────────────────────────────────────────────────────────────
-- 1. 사용자 (QimUser)
--    §10.2 사용자 오브젝트 SoR.
--    qimUserId 는 플랫폼 전역 고유 식별자.
--    status 전이: ACTIVE → SUSPENDED → WITHDRAWN
-- ──────────────────────────────────────────────────────────────
CREATE TABLE qim.qim_user (
    qim_user_id          VARCHAR(36)   NOT NULL,          -- UUIDv4, 플랫폼 전역 식별자
    status               VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    withdrawal_reason    VARCHAR(200),
    event_version        BIGINT        NOT NULL DEFAULT 1, -- 낙관적 락 + Kafka ordering 기준
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    withdrawn_at         TIMESTAMPTZ,
    CONSTRAINT pk_qim_user PRIMARY KEY (qim_user_id),
    CONSTRAINT chk_qim_user_status
        CHECK (status IN ('ACTIVE','SUSPENDED','WITHDRAWN'))
);

CREATE INDEX idx_qim_user_status     ON qim.qim_user (status);
CREATE INDEX idx_qim_user_updated_at ON qim.qim_user (updated_at DESC);

COMMENT ON TABLE  qim.qim_user                IS '§10.2 사용자 오브젝트 SoR – 플랫폼 전역 qimUserId 기준';
COMMENT ON COLUMN qim.qim_user.qim_user_id    IS 'UUIDv4, 플랫폼 내 불변 식별자 (변경 불가)';
COMMENT ON COLUMN qim.qim_user.event_version  IS 'Kafka 파티션 내 순서 보장 + 낙관적 락 버전';
COMMENT ON COLUMN qim.qim_user.status         IS 'ACTIVE → SUSPENDED → WITHDRAWN (역방향 전이 불가)';

-- ──────────────────────────────────────────────────────────────
-- 2. 인증 수단 매핑 (AuthMeanMapping)
--    §10.3 identifierHash → qimUserId 단방향 매핑.
--    하나의 identifierHash 는 반드시 하나의 qimUserId 에만 귀속.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE qim.auth_mean_mapping (
    mapping_id           VARCHAR(36)   NOT NULL,
    qim_user_id          VARCHAR(36)   NOT NULL,
    provider_code        VARCHAR(50)   NOT NULL,
    identifier_hash      VARCHAR(300)  NOT NULL,          -- SHA-256(CI / idToken.sub)
    status               VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / REVOKED
    linked_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    revoked_at           TIMESTAMPTZ,
    revoke_reason        VARCHAR(200),
    CONSTRAINT pk_auth_mean_mapping PRIMARY KEY (mapping_id),
    CONSTRAINT uq_identifier_hash UNIQUE (identifier_hash),   -- 동일 hash → 단일 사용자만
    CONSTRAINT fk_mapping_qim_user
        FOREIGN KEY (qim_user_id) REFERENCES qim.qim_user (qim_user_id),
    CONSTRAINT chk_mapping_status
        CHECK (status IN ('ACTIVE','REVOKED'))
);

CREATE INDEX idx_mapping_qim_user_id     ON qim.auth_mean_mapping (qim_user_id);
CREATE INDEX idx_mapping_identifier_hash ON qim.auth_mean_mapping (identifier_hash);
CREATE INDEX idx_mapping_provider        ON qim.auth_mean_mapping (provider_code, status);

COMMENT ON TABLE  qim.auth_mean_mapping                    IS '§10.3 identifierHash → qimUserId 단방향 매핑 SoR';
COMMENT ON COLUMN qim.auth_mean_mapping.identifier_hash    IS 'SHA-256(CI) – 역복호화 불가, 유일성 강제';
COMMENT ON COLUMN qim.auth_mean_mapping.status             IS 'ACTIVE(유효) / REVOKED(파기)';

-- ──────────────────────────────────────────────────────────────
-- 3. 사용자 프로필 / 속성 (UserProfile)
--    §10.4 사용자 속성 저장. PII 마스킹 필수.
--    기관은 이 테이블을 직접 참조하지 않고 IdO Verify API 경유.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE qim.user_profile (
    qim_user_id          VARCHAR(36)   NOT NULL,
    name_masked          VARCHAR(100),                    -- 예: 홍*동
    mobile_masked        VARCHAR(20),                     -- 예: 010-****-5678
    nationality_type     VARCHAR(10),                     -- DOMESTIC / FOREIGN
    ci                   VARCHAR(300),                    -- 연계정보 (암호화 저장)
    di_map               JSONB,                           -- {agencyCode: DI} 기관별 DI 맵
    birth_year           SMALLINT,                        -- 출생 연도 (일/월 제외)
    gender               VARCHAR(10),                     -- MALE / FEMALE / UNKNOWN
    extra_attributes     JSONB,                           -- 확장 속성 (provider별)
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_user_profile PRIMARY KEY (qim_user_id),
    CONSTRAINT fk_profile_qim_user
        FOREIGN KEY (qim_user_id) REFERENCES qim.qim_user (qim_user_id)
);

COMMENT ON TABLE  qim.user_profile             IS '§10.4 사용자 속성 저장 – PII 마스킹 필수';
COMMENT ON COLUMN qim.user_profile.ci          IS '연계정보 – AES-256-GCM 암호화 저장';
COMMENT ON COLUMN qim.user_profile.di_map      IS '{agencyCode: DI} – 기관별 개별 식별자';
COMMENT ON COLUMN qim.user_profile.name_masked IS '개인정보보호법 §24 – 마스킹 처리 후 저장';

-- ──────────────────────────────────────────────────────────────
-- 4. 사용자 상태 이력 (UserStatusHistory)
--    §10.5 상태 전이 감사 이력. 불변 로그.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE qim.user_status_history (
    history_id           VARCHAR(36)   NOT NULL,
    qim_user_id          VARCHAR(36)   NOT NULL,
    status_before        VARCHAR(20)   NOT NULL,
    status_after         VARCHAR(20)   NOT NULL,
    changed_by           VARCHAR(100)  NOT NULL,          -- 시스템 / 관리자 ID / 본인
    change_reason        VARCHAR(300),
    correlation_id       VARCHAR(36),
    occurred_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_user_status_history PRIMARY KEY (history_id),
    CONSTRAINT fk_status_history_user
        FOREIGN KEY (qim_user_id) REFERENCES qim.qim_user (qim_user_id)
);

CREATE INDEX idx_status_history_user ON qim.user_status_history (qim_user_id, occurred_at DESC);

COMMENT ON TABLE qim.user_status_history IS '§10.5 사용자 상태 전이 감사 이력 – DELETE/UPDATE 금지';

-- ──────────────────────────────────────────────────────────────
-- 5. Transactional Outbox (Q-IM → Kafka)
--    §10.5.2 qim.user.events (compact) / qim.user.snapshot 발행.
--    partitionKey = qimUserId (동일 사용자 이벤트 순서 보장).
-- ──────────────────────────────────────────────────────────────
CREATE TABLE qim.outbox (
    event_id             VARCHAR(36)   NOT NULL,          -- UUIDv4 (멱등 키)
    event_type           VARCHAR(80)   NOT NULL,          -- USER_REGISTERED / USER_STATUS_CHANGED / MAPPING_ADDED / MAPPING_REVOKED / USER_SNAPSHOT
    partition_key        VARCHAR(36)   NOT NULL,          -- qimUserId
    aggregate_id         VARCHAR(36)   NOT NULL,          -- qimUserId
    event_version        BIGINT        NOT NULL,          -- qim_user.event_version 와 동기
    payload              JSONB         NOT NULL,
    topic                VARCHAR(200)  NOT NULL,          -- qim.user.events or qim.user.snapshot
    status               VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    retry_count          SMALLINT      NOT NULL DEFAULT 0,
    error_message        TEXT,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    published_at         TIMESTAMPTZ,
    CONSTRAINT pk_qim_outbox PRIMARY KEY (event_id),
    CONSTRAINT chk_qim_outbox_status
        CHECK (status IN ('PENDING','PUBLISHED','FAILED'))
);

CREATE INDEX idx_qim_outbox_pending   ON qim.outbox (status, created_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_qim_outbox_partition ON qim.outbox (partition_key, event_version);

COMMENT ON TABLE  qim.outbox               IS '§10.5.2 Transactional Outbox – Kafka 발행 큐';
COMMENT ON COLUMN qim.outbox.partition_key IS 'qimUserId = Kafka 파티션 키 (순서 보장)';
COMMENT ON COLUMN qim.outbox.event_version IS 'qim_user.event_version 동기 – 컨슈머 중복 판정 기준';
COMMENT ON COLUMN qim.outbox.topic         IS 'qim.user.events(compact) 또는 qim.user.snapshot';

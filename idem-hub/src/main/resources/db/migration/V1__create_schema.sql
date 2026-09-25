-- ============================================================
-- IdO Schema : 정책 오케스트레이터 SoR
-- 설계서 §11 IdO 책임 범위 기반
-- ============================================================
-- [멱등성] 모든 DDL에 IF NOT EXISTS 적용
--   이유: postgres 볼륨 재사용 시 Flyway schema_history 없이
--         V1 재실행 시도 → 42P07 relation already exists 오류 방지
-- [하위 호환] 테이블이 이미 존재할 경우 누락 컬럼을 ADD COLUMN IF NOT EXISTS로 보완
--   이유: 볼륨의 agency_meta가 integration_type 등 컬럼 없이 생성된 이전 버전일 수 있음
-- ============================================================

CREATE SCHEMA IF NOT EXISTS idem_hub;

-- ──────────────────────────────────────────────────────────────
-- 1. 기관 메타 / 정책 (AgencyMeta)
--    §11.3 기관 정책 캐시 SoR. TTL ≤60분 Redis 캐시의 원본.
--    기관 코드별 최소 인증 수준·허용 속성·callback whitelist 관리.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS idem_hub.agency_meta (
    agency_code             VARCHAR(50)   NOT NULL,
    official_name           VARCHAR(200)  NOT NULL,
    min_auth_level          VARCHAR(10)   NOT NULL DEFAULT 'L1',
    policy_version          VARCHAR(20)   NOT NULL DEFAULT '1.0',
    api_key_hash            VARCHAR(300),                 -- PBKDF2(apiKey) 해시
    callback_whitelist      JSONB,                        -- ["https://a.example.com/cb", ...]
    allowed_attributes      JSONB,                        -- ["name_masked","mobile_masked",...]
    maintenance_windows     JSONB,                        -- [{"start":"02:00","end":"04:00","tz":"Asia/Seoul"}]
    integration_type        VARCHAR(20)   NOT NULL DEFAULT 'DIRECT', -- DIRECT / APACHE_GATE / BRIDGE / INTERNAL_SSO
    bridge_endpoint         VARCHAR(500),                 -- BRIDGE 타입일 때 상대 엔드포인트
    sso_domain              VARCHAR(200),                 -- INTERNAL_SSO 타입일 때 공유 도메인
    active                  BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_agency_meta PRIMARY KEY (agency_code),
    CONSTRAINT chk_agency_min_auth_level
        CHECK (min_auth_level IN ('L1','L2','L3')),
    CONSTRAINT chk_integration_type
        CHECK (integration_type IN ('DIRECT','APACHE_GATE','BRIDGE','INTERNAL_SSO'))
);

-- 하위 호환: 테이블이 이전 버전으로 생성된 경우 누락 컬럼 보완
ALTER TABLE idem_hub.agency_meta
    ADD COLUMN IF NOT EXISTS maintenance_windows JSONB,
    ADD COLUMN IF NOT EXISTS integration_type    VARCHAR(20) NOT NULL DEFAULT 'DIRECT',
    ADD COLUMN IF NOT EXISTS bridge_endpoint     VARCHAR(500),
    ADD COLUMN IF NOT EXISTS sso_domain          VARCHAR(200);

-- integration_type CHECK 제약이 없을 경우에만 추가
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_integration_type'
          AND conrelid = 'idem_hub.agency_meta'::regclass
    ) THEN
        ALTER TABLE idem_hub.agency_meta
            ADD CONSTRAINT chk_integration_type
            CHECK (integration_type IN ('DIRECT','APACHE_GATE','BRIDGE','INTERNAL_SSO'));
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_agency_meta_active ON idem_hub.agency_meta (active);

COMMENT ON TABLE  idem_hub.agency_meta                    IS '§11.3 기관 정책 SoR – Redis 캐시(TTL≤60분)의 원본';
COMMENT ON COLUMN idem_hub.agency_meta.api_key_hash       IS 'PBKDF2(apiKey, salt, 310000) – 평문 저장 금지';
COMMENT ON COLUMN idem_hub.agency_meta.callback_whitelist IS '§12.3 returnUrl 화이트리스트 JSON 배열';
COMMENT ON COLUMN idem_hub.agency_meta.allowed_attributes IS '기관이 조회 허용된 사용자 속성 목록';
COMMENT ON COLUMN idem_hub.agency_meta.integration_type   IS '§13 연동 패턴: DIRECT / APACHE_GATE / BRIDGE / INTERNAL_SSO';

-- ──────────────────────────────────────────────────────────────
-- 2. 기관 메타 이력 (AgencyMetaHistory)
--    정책 변경 감사 이력. policy_version 증가 시 스냅샷 저장.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS idem_hub.agency_meta_history (
    history_id              VARCHAR(36)   NOT NULL,
    agency_code             VARCHAR(50)   NOT NULL,
    policy_version          VARCHAR(20)   NOT NULL,
    snapshot                JSONB         NOT NULL,       -- 변경 전 agency_meta 전체 스냅샷
    changed_by              VARCHAR(100)  NOT NULL,
    change_reason           VARCHAR(300),
    changed_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_agency_meta_history PRIMARY KEY (history_id)
);

CREATE INDEX IF NOT EXISTS idx_agency_meta_history_code ON idem_hub.agency_meta_history (agency_code, changed_at DESC);

COMMENT ON TABLE idem_hub.agency_meta_history IS '기관 정책 변경 감사 이력 – DELETE/UPDATE 금지';

-- ──────────────────────────────────────────────────────────────
-- 3. Handoff Ticket 감사 이력 (HandoffAudit)
--    §16.4 Handoff Issue / Verify 전 주기 감사.
--    Redis 가 TTL 기반 주 저장소, DB 는 소비 이력 감사용.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS idem_hub.handoff_audit (
    ticket_id               VARCHAR(36)   NOT NULL,       -- UUIDv4, Redis key 와 동일
    correlation_id          VARCHAR(36)   NOT NULL,
    agency_code             VARCHAR(50)   NOT NULL,
    qim_user_id             VARCHAR(36)   NOT NULL,
    auth_result_id          VARCHAR(36)   NOT NULL,
    auth_level              VARCHAR(10)   NOT NULL,
    state                   VARCHAR(20)   NOT NULL,       -- ISSUED / CONSUMED / EXPIRED / REVOKED
    revoke_reason           VARCHAR(100),
    client_ip_issue         VARCHAR(45),
    client_ip_verify        VARCHAR(45),
    issued_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    consumed_at             TIMESTAMPTZ,
    expires_at              TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_handoff_audit PRIMARY KEY (ticket_id),
    CONSTRAINT chk_handoff_state
        CHECK (state IN ('ISSUED','CONSUMED','EXPIRED','REVOKED')),
    CONSTRAINT chk_handoff_auth_level
        CHECK (auth_level IN ('L1','L2','L3'))
);

CREATE INDEX IF NOT EXISTS idx_handoff_audit_agency      ON idem_hub.handoff_audit (agency_code, issued_at DESC);
CREATE INDEX IF NOT EXISTS idx_handoff_audit_qim_user    ON idem_hub.handoff_audit (qim_user_id, issued_at DESC);
CREATE INDEX IF NOT EXISTS idx_handoff_audit_correlation ON idem_hub.handoff_audit (correlation_id);
CREATE INDEX IF NOT EXISTS idx_handoff_audit_state       ON idem_hub.handoff_audit (state, expires_at)
    WHERE state IN ('ISSUED','EXPIRED');

COMMENT ON TABLE  idem_hub.handoff_audit              IS '§16.4 Handoff Ticket 전 주기 감사 이력';
COMMENT ON COLUMN idem_hub.handoff_audit.ticket_id    IS 'Redis key 와 동일한 UUIDv4 – 단방향 추적 가능';
COMMENT ON COLUMN idem_hub.handoff_audit.state        IS 'ISSUED → CONSUMED(정상) | EXPIRED(TTL만료) | REVOKED(강제취소)';

-- ──────────────────────────────────────────────────────────────
-- 4. 정책 충돌 해결 이력 (PolicyConflictLog)
--    §11.6 정책 충돌(기관 정책 vs 플랫폼 기본 정책) 해결 이력.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS idem_hub.policy_conflict_log (
    conflict_id             VARCHAR(36)   NOT NULL,
    correlation_id          VARCHAR(36)   NOT NULL,
    agency_code             VARCHAR(50)   NOT NULL,
    conflict_type           VARCHAR(80)   NOT NULL,       -- AUTH_LEVEL_MISMATCH / ATTRIBUTE_DENIED / MAINTENANCE_WINDOW
    platform_policy         JSONB,
    agency_policy           JSONB,
    resolution              VARCHAR(50)   NOT NULL,       -- AGENCY_WINS / PLATFORM_WINS / BLOCKED
    resolution_detail       VARCHAR(300),
    occurred_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_policy_conflict_log PRIMARY KEY (conflict_id)
);

CREATE INDEX IF NOT EXISTS idx_policy_conflict_agency ON idem_hub.policy_conflict_log (agency_code, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_policy_conflict_type   ON idem_hub.policy_conflict_log (conflict_type, occurred_at DESC);

COMMENT ON TABLE idem_hub.policy_conflict_log IS '§11.6 정책 충돌 해결 감사 이력';

-- ──────────────────────────────────────────────────────────────
-- 5. 멱등 컨슈머 (ProcessedEvent)
--    §16.3 IdO Kafka 컨슈머 중복 처리 방지.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS idem_hub.processed_event (
    event_id                VARCHAR(36)   NOT NULL,
    consumer_group          VARCHAR(100)  NOT NULL,
    event_type              VARCHAR(80),
    result_code             VARCHAR(50),                  -- OK / SKIPPED / ERROR
    processed_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ido_processed_event PRIMARY KEY (event_id, consumer_group)
);

CREATE INDEX IF NOT EXISTS idx_ido_processed_event_group ON idem_hub.processed_event (consumer_group, processed_at DESC);

COMMENT ON TABLE idem_hub.processed_event IS '§16.3 IdO 멱등 컨슈머 – at-least-once 중복 처리 방지';

-- ──────────────────────────────────────────────────────────────
-- 6. 이벤트 버전 추적 (LastEventVersion)
--    §11.5.5 Q-IM 이벤트 수신 시 버전 기반 순서 보장.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS idem_hub.last_event_version (
    consumer_group          VARCHAR(100)  NOT NULL,
    aggregate_id            VARCHAR(36)   NOT NULL,       -- qimUserId
    last_version            BIGINT        NOT NULL,
    last_event_id           VARCHAR(36)   NOT NULL,
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ido_last_event_version PRIMARY KEY (consumer_group, aggregate_id)
);

COMMENT ON TABLE idem_hub.last_event_version IS '§11.5.5 Q-IM 이벤트 버전 추적 – Ordered Consumer 패턴';

-- ──────────────────────────────────────────────────────────────
-- 7. Transactional Outbox (IdO → Kafka)
--    idem_hub.handoff.events / platform.session.advisory 발행.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS idem_hub.outbox (
    event_id                VARCHAR(36)   NOT NULL,
    event_type              VARCHAR(80)   NOT NULL,       -- HANDOFF_ISSUED / HANDOFF_CONSUMED / HANDOFF_EXPIRED / SESSION_ADVISORY
    partition_key           VARCHAR(36)   NOT NULL,       -- correlationId or qimUserId
    aggregate_id            VARCHAR(36)   NOT NULL,       -- ticketId
    event_version           BIGINT        NOT NULL DEFAULT 1,
    payload                 JSONB         NOT NULL,
    topic                   VARCHAR(200)  NOT NULL,
    status                  VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    retry_count             SMALLINT      NOT NULL DEFAULT 0,
    error_message           TEXT,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    published_at            TIMESTAMPTZ,
    CONSTRAINT pk_ido_outbox PRIMARY KEY (event_id),
    CONSTRAINT chk_ido_outbox_status
        CHECK (status IN ('PENDING','PUBLISHED','FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_ido_outbox_pending   ON idem_hub.outbox (status, created_at)
    WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_ido_outbox_aggregate ON idem_hub.outbox (aggregate_id);

COMMENT ON TABLE idem_hub.outbox IS 'IdO Transactional Outbox – handoff/session advisory 이벤트 발행';

-- ──────────────────────────────────────────────────────────────
-- 8. 초기 기관 데이터 (PoC 스텁)
-- ──────────────────────────────────────────────────────────────
INSERT INTO idem_hub.agency_meta
    (agency_code, official_name, min_auth_level, policy_version,
     integration_type, callback_whitelist, allowed_attributes, active)
VALUES
    ('AGENCY_STUB_001',
     '테스트 기관 (PoC Stub)',
     'L1',
     '1.0',
     'DIRECT',
     '["http://localhost:8084/entry", "https://agency-stub.local/entry"]',
     '["name_masked","mobile_masked","nationality_type","birth_year"]',
     TRUE)
ON CONFLICT (agency_code) DO NOTHING;

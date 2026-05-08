-- =============================================================================
-- IdO V9: AES 키 레지스트리 + Rate Limit 설정 + Member Lookup 지원
-- =============================================================================
-- 목적:
--   1. crypto_key_registry — AES 키 버전 로테이션 메타데이터 (HandoffKeyRotationScheduler)
--   2. agency_rate_limit_config — 기관별 Rate Limit 설정 (AgencyRateLimiter)
--   3. agency_meta_history — 기관 설정 변경 이력 (AgencyAdminService.getHistory)
--   4. member_lookup_log — CI 기반 회원 조회 감사 로그
--
-- 작성일: 2026-05-08
-- =============================================================================

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. AES 키 레지스트리 (Handoff Ticket 암호화 키 버전 관리)
--    HandoffKeyRotationScheduler가 로테이션 이력 기록
--    실제 키 재료는 Vault/KMS에서 관리 — 이 테이블은 버전 메타만 저장
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ido.crypto_key_registry (
    key_id                  VARCHAR(36)   NOT NULL,
    key_type                VARCHAR(50)   NOT NULL   -- HANDOFF_AES / HANDOFF_HMAC 등
        CONSTRAINT chk_key_type CHECK (key_type IN ('HANDOFF_AES', 'HANDOFF_HMAC', 'WEBHOOK_HMAC')),
    key_version             VARCHAR(10)   NOT NULL,  -- v1, v2, ...
    key_material_encrypted  TEXT,                    -- KMS 암호화된 키 재료 (선택적)
    active                  BOOLEAN       NOT NULL DEFAULT TRUE,
    current_flag            BOOLEAN       NOT NULL DEFAULT FALSE,
    grace_until             TIMESTAMPTZ,             -- active=FALSE 후 복호화 허용 기간
    rotation_reason         VARCHAR(200),
    created_by              VARCHAR(100)  NOT NULL DEFAULT 'SYSTEM',
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_crypto_key_registry PRIMARY KEY (key_id),
    CONSTRAINT uq_crypto_key_type_version UNIQUE (key_type, key_version)
);

CREATE INDEX idx_crypto_key_active ON ido.crypto_key_registry (key_type, active, created_at DESC);
CREATE INDEX idx_crypto_key_current ON ido.crypto_key_registry (key_type, current_flag) WHERE current_flag = TRUE;

COMMENT ON TABLE ido.crypto_key_registry IS 'Handoff Ticket AES/HMAC 키 버전 메타데이터 (실제 키는 Vault/KMS)';
COMMENT ON COLUMN ido.crypto_key_registry.grace_until IS 'active=FALSE 후 이 시간까지 복호화에 사용 가능';

-- 초기 v1 키 버전 등록
INSERT INTO ido.crypto_key_registry (key_id, key_type, key_version, active, current_flag, rotation_reason, created_by)
VALUES
    (gen_random_uuid(), 'HANDOFF_AES',  'v1', TRUE, TRUE,  'INITIAL_SETUP', 'SYSTEM'),
    (gen_random_uuid(), 'HANDOFF_HMAC', 'v1', TRUE, TRUE,  'INITIAL_SETUP', 'SYSTEM'),
    (gen_random_uuid(), 'WEBHOOK_HMAC', 'v1', TRUE, TRUE,  'INITIAL_SETUP', 'SYSTEM')
ON CONFLICT (key_type, key_version) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. 기관별 Rate Limit 설정 테이블
--    AgencyRateLimiter에서 기관별 TPS/일일 한도 조회
--    기본값: 200 TPS, 1,000,000건/일
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ido.agency_rate_limit_config (
    agency_code         VARCHAR(50)   NOT NULL,
    tps_limit           INTEGER       NOT NULL DEFAULT 200   -- 초당 최대 요청수
        CONSTRAINT chk_tps_positive CHECK (tps_limit > 0),
    daily_limit         BIGINT        NOT NULL DEFAULT 1000000 -- 일별 최대 건수
        CONSTRAINT chk_daily_positive CHECK (daily_limit > 0),
    burst_multiplier    NUMERIC(3,1)  NOT NULL DEFAULT 1.5,   -- 버스트 허용 배수
    enabled             BOOLEAN       NOT NULL DEFAULT TRUE,
    effective_from      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_agency_rate_limit PRIMARY KEY (agency_code),
    CONSTRAINT fk_rate_limit_agency FOREIGN KEY (agency_code)
        REFERENCES ido.agency_meta (agency_code)
        ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE INDEX idx_agency_rate_limit_enabled ON ido.agency_rate_limit_config (agency_code, enabled);

COMMENT ON TABLE ido.agency_rate_limit_config IS '기관별 Rate Limit 설정 (AgencyRateLimiter 참조)';

-- AGENCY_STUB_001 기본 설정
INSERT INTO ido.agency_rate_limit_config (agency_code, tps_limit, daily_limit)
VALUES ('AGENCY_STUB_001', 200, 1000000)
ON CONFLICT (agency_code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. 기관 설정 변경 이력 테이블
--    AgencyAdminService.getHistory() 대상 테이블
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ido.agency_meta_history (
    history_id      BIGSERIAL     NOT NULL,
    agency_code     VARCHAR(50)   NOT NULL,
    policy_version  VARCHAR(20),
    changed_by      VARCHAR(100)  NOT NULL,
    change_reason   VARCHAR(500),
    change_detail   JSONB,        -- 변경 전/후 스냅샷
    changed_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_agency_meta_history PRIMARY KEY (history_id)
);

CREATE INDEX idx_agency_history_code ON ido.agency_meta_history (agency_code, changed_at DESC);

COMMENT ON TABLE ido.agency_meta_history IS '기관 설정 변경 이력 (AgencyAdminService.getHistory)';

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. Member Lookup 감사 로그 테이블
--    MemberLookupController → CI/Hash 기반 회원 조회 이력
--    GDPR §15: 개인정보 처리 기록 의무 (3년 보존 권장)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ido.member_lookup_log (
    log_id          BIGSERIAL     NOT NULL,
    agency_code     VARCHAR(50)   NOT NULL,
    lookup_type     VARCHAR(20)   NOT NULL  -- BY_CI / BY_HASH
        CONSTRAINT chk_lookup_type CHECK (lookup_type IN ('BY_CI', 'BY_HASH')),
    result_code     VARCHAR(20)   NOT NULL  -- FOUND / NOT_FOUND / ERROR
        CONSTRAINT chk_result_code CHECK (result_code IN ('FOUND', 'NOT_FOUND', 'ERROR')),
    correlation_id  VARCHAR(36),
    qim_user_id     VARCHAR(36),            -- 조회 성공 시만 기록
    response_ms     INTEGER,                -- 응답 시간 (ms)
    occurred_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_member_lookup_log PRIMARY KEY (log_id)
);

CREATE INDEX idx_member_lookup_agency ON ido.member_lookup_log (agency_code, occurred_at DESC);
CREATE INDEX idx_member_lookup_user ON ido.member_lookup_log (qim_user_id, occurred_at DESC) WHERE qim_user_id IS NOT NULL;

COMMENT ON TABLE ido.member_lookup_log IS 'CI/Hash 기반 회원 조회 감사 로그 (GDPR §15 준수)';

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. agency_meta 컬럼 추가 (integration_type 등 Production 설계 컬럼 보완)
-- ─────────────────────────────────────────────────────────────────────────────
-- daily_lookup_limit 컬럼 추가 (Admin API에서 기관별 CI 조회 한도 설정)
ALTER TABLE ido.agency_meta
    ADD COLUMN IF NOT EXISTS daily_lookup_limit INTEGER DEFAULT 1000000
        CONSTRAINT chk_daily_lookup_positive CHECK (daily_lookup_limit > 0);

COMMENT ON COLUMN ido.agency_meta.daily_lookup_limit IS '기관별 일일 CI/Hash 조회 한도 (기본 1,000,000)';

-- ─────────────────────────────────────────────────────────────────────────────
-- 6. 검증 쿼리 (마이그레이션 후 확인용 주석)
-- ─────────────────────────────────────────────────────────────────────────────
-- SELECT * FROM ido.crypto_key_registry;
-- SELECT * FROM ido.agency_rate_limit_config WHERE agency_code = 'AGENCY_STUB_001';
-- \d ido.agency_meta_history
-- \d ido.member_lookup_log

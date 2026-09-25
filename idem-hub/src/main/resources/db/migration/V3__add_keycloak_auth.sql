-- ============================================================
-- IdO Schema V3 : Keycloak 브로커링 + AuthResult SoR 이관
-- 문서 §8.3 Strategy B — IdO가 AuthResult 직접 생성
-- ============================================================

-- ──────────────────────────────────────────────────────────────
-- 1. IdO AuthResult (idem_hub.auth_result)
--    Keycloak/비OIDC 모드에서 IdO가 직접 생성하는 인증 결과 SoR.
--    기존 idem_gate.auth_result와 동일한 구조 — 공통 이벤트 버스(idem_gate.auth.events)로 발행.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS idem_hub.auth_result (
    auth_result_id      VARCHAR(36)   NOT NULL,
    correlation_id      VARCHAR(36)   NOT NULL,
    auth_level          VARCHAR(10)   NOT NULL,       -- L1 / L2 / L3
    provider_code       VARCHAR(50)   NOT NULL,       -- KAKAO_OIDC / NAVER_OIDC / PASS / FINANCIAL_CERT / GPKI
    provider_tx_id      VARCHAR(200),                 -- 외부 사업자 트랜잭션 ID (Keycloak sub / PASS tx 등)
    identifier_hash     VARCHAR(64)   NOT NULL,       -- SHA-256(sub 또는 원본 식별자)
    verification_result VARCHAR(20)   NOT NULL DEFAULT 'SUCCESS',  -- SUCCESS / FAIL
    source_system       VARCHAR(50)   NOT NULL,       -- ido-keycloak / ido-nonoidc
    session_ref         VARCHAR(36),                  -- 연관 FE 세션 ID (선택)
    authenticated_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ido_auth_result      PRIMARY KEY (auth_result_id),
    CONSTRAINT chk_ido_auth_level
        CHECK (auth_level IN ('L1','L2','L3')),
    CONSTRAINT chk_ido_verification_result
        CHECK (verification_result IN ('SUCCESS','FAIL')),
    CONSTRAINT chk_ido_source_system
        CHECK (source_system IN ('ido-keycloak','ido-nonoidc','ido-adapter'))
);

CREATE INDEX IF NOT EXISTS idx_ido_auth_result_correlation  ON idem_hub.auth_result (correlation_id);
CREATE INDEX IF NOT EXISTS idx_ido_auth_result_identifier   ON idem_hub.auth_result (identifier_hash, authenticated_at DESC);
CREATE INDEX IF NOT EXISTS idx_ido_auth_result_provider     ON idem_hub.auth_result (provider_code, authenticated_at DESC);

COMMENT ON TABLE  idem_hub.auth_result                    IS '§8.3 Strategy B — Keycloak/비OIDC 모드 인증 결과 SoR';
COMMENT ON COLUMN idem_hub.auth_result.identifier_hash    IS 'SHA-256(sub | rawIdentifier) — Q-IM 조회 키';
COMMENT ON COLUMN idem_hub.auth_result.source_system      IS 'ido-keycloak: Keycloak OIDC | ido-nonoidc: PASS/GPKI 등';
COMMENT ON COLUMN idem_hub.auth_result.provider_tx_id     IS 'Keycloak sub 또는 외부 사업자 트랜잭션 ID';

-- ──────────────────────────────────────────────────────────────
-- 2. 인증 잠금 (idem_hub.auth_lock)
--    연속 실패 시 잠금 상태 관리.
--    설계서 §10.3 lock-attempts=5, lock-duration=30분 기준.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS idem_hub.auth_lock (
    lock_id             VARCHAR(36)   NOT NULL,
    identifier_hash     VARCHAR(64)   NOT NULL,
    provider_code       VARCHAR(50)   NOT NULL,
    failure_count       SMALLINT      NOT NULL DEFAULT 1,
    locked_until        TIMESTAMPTZ,                  -- NULL = 잠금 아님
    first_failure_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    last_failure_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    reset_at            TIMESTAMPTZ,                  -- 관리자 수동 해제 시각
    CONSTRAINT pk_ido_auth_lock         PRIMARY KEY (lock_id),
    CONSTRAINT uq_ido_auth_lock_key     UNIQUE (identifier_hash, provider_code)
);

CREATE INDEX IF NOT EXISTS idx_ido_auth_lock_hash     ON idem_hub.auth_lock (identifier_hash);
CREATE INDEX IF NOT EXISTS idx_ido_auth_lock_locked   ON idem_hub.auth_lock (locked_until)
    WHERE locked_until IS NOT NULL;

COMMENT ON TABLE  idem_hub.auth_lock              IS '§10.3 인증 연속 실패 잠금 — 5회 실패 시 30분 잠금';
COMMENT ON COLUMN idem_hub.auth_lock.locked_until IS 'NULL이면 잠금 해제 상태; 값이 있으면 해당 시각까지 잠금';

-- ──────────────────────────────────────────────────────────────
-- 3. OIDC 세션 로그 (idem_hub.oidc_session_log)
--    Keycloak 브로커링 과정에서의 OIDC 세션 감사 이력.
--    q-sign의 idem_gate.oidc_session_log에 해당하는 ido 측 로그.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS idem_hub.oidc_session_log (
    log_id              VARCHAR(36)   NOT NULL,
    correlation_id      VARCHAR(36)   NOT NULL,
    provider_code       VARCHAR(50)   NOT NULL,       -- KAKAO_OIDC / NAVER_OIDC
    provider_subject    VARCHAR(200),                 -- Keycloak sub (PII — 운영 시 암호화 고려)
    identifier_hash     VARCHAR(64)   NOT NULL,       -- SHA-256(sub)
    auth_result_id      VARCHAR(36),                  -- 연관 auth_result
    broker_mode         VARCHAR(20)   NOT NULL DEFAULT 'keycloak',
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ido_oidc_session_log PRIMARY KEY (log_id),
    CONSTRAINT chk_ido_broker_mode
        CHECK (broker_mode IN ('keycloak','qsign'))
);

CREATE INDEX IF NOT EXISTS idx_ido_oidc_log_correlation ON idem_hub.oidc_session_log (correlation_id);
CREATE INDEX IF NOT EXISTS idx_ido_oidc_log_hash        ON idem_hub.oidc_session_log (identifier_hash, created_at DESC);

COMMENT ON TABLE  idem_hub.oidc_session_log                 IS 'Keycloak OIDC 세션 감사 이력';
COMMENT ON COLUMN idem_hub.oidc_session_log.provider_subject IS 'Keycloak sub — PII, 운영 환경에서는 암호화 저장 권고';

-- ──────────────────────────────────────────────────────────────
-- 4. nonce 사용 이력 (idem_hub.oidc_nonce_used)
--    id_token replay attack 방지 — 이미 사용된 nonce 기록.
--    Redis state/nonce store의 1회 소비 보완용 영속 기록.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS idem_hub.oidc_nonce_used (
    nonce               VARCHAR(64)   NOT NULL,
    correlation_id      VARCHAR(36)   NOT NULL,
    provider_code       VARCHAR(50)   NOT NULL,
    used_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ido_oidc_nonce_used PRIMARY KEY (nonce)
);

CREATE INDEX IF NOT EXISTS idx_ido_nonce_used_at ON idem_hub.oidc_nonce_used (used_at DESC);

COMMENT ON TABLE idem_hub.oidc_nonce_used IS 'OIDC nonce 사용 이력 — replay attack 방지 보조';

-- ──────────────────────────────────────────────────────────────
-- 5. 인증 수단별 설정 캐시 (idem_hub.provider_config)
--    provider별 AuthLevel, 활성화 여부, 브로커 모드 관리.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS idem_hub.provider_config (
    provider_code       VARCHAR(50)   NOT NULL,
    display_name        VARCHAR(100)  NOT NULL,
    auth_level          VARCHAR(10)   NOT NULL DEFAULT 'L1',
    broker_mode         VARCHAR(20)   NOT NULL DEFAULT 'keycloak',  -- keycloak / qsign / direct
    idp_hint            VARCHAR(100),                -- kc_idp_hint (Keycloak IdP alias)
    active              BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ido_provider_config   PRIMARY KEY (provider_code),
    CONSTRAINT chk_ido_provider_level   CHECK (auth_level IN ('L1','L2','L3')),
    CONSTRAINT chk_ido_provider_mode    CHECK (broker_mode IN ('keycloak','qsign','direct'))
);

COMMENT ON TABLE idem_hub.provider_config IS '인증 수단 설정 — AuthLevel / 브로커 모드 / Keycloak IdP 힌트';

-- 초기 provider 설정 데이터
INSERT INTO idem_hub.provider_config
    (provider_code, display_name, auth_level, broker_mode, idp_hint, active)
VALUES
    ('KAKAO_OIDC',     '카카오 간편인증',     'L1', 'keycloak', 'social-kakao',   TRUE),
    ('NAVER_OIDC',     '네이버 간편인증',     'L1', 'keycloak', 'social-naver',   TRUE),
    ('PASS',           'PASS 본인인증',       'L2', 'direct',   NULL,             TRUE),
    ('FINANCIAL_CERT', '금융인증서',          'L3', 'direct',   NULL,             TRUE),
    ('GPKI',           '정부 공개키 인증서',  'L3', 'direct',   NULL,             TRUE),
    ('JOINT_CERT',     '공동인증서',          'L3', 'direct',   NULL,             TRUE)
ON CONFLICT (provider_code) DO NOTHING;

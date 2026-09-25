-- ============================================================
-- IdO Schema V2 : FE 세션 감사 보조 테이블
-- 설계서 §12.3 / §12.4 / §12.5 참조
--
-- onepass-fe Spring Boot BFF 제거에 따라
-- FE 세션 생명주기 관리가 IdO 로 이관됨.
--
-- 주 저장소: Redis (fe:session:{feSessionId}, TTL 기반 sliding)
-- 보조 저장소: idem_hub.fe_session_audit (감사 이력 — DELETE 금지)
-- ============================================================

-- ──────────────────────────────────────────────────────────────
-- 1. FE 세션 감사 이력 (FeSessionAudit)
--    §12.5 세션 생성/만료/무효화 감사.
--    Redis 가 주 저장소이며 이 테이블은 감사/알림 전용.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS idem_hub.fe_session_audit (
    fe_session_id       VARCHAR(64)   NOT NULL,       -- SecureRandom Base64URL (43자)
    qim_user_id         VARCHAR(36)   NOT NULL,
    auth_result_id      VARCHAR(36)   NOT NULL,
    auth_level          VARCHAR(10)   NOT NULL,
    return_url          VARCHAR(500),
    event_type          VARCHAR(30)   NOT NULL,       -- CREATED / EXPIRED / INVALIDATED / ADVISORY_SET
    reason              VARCHAR(200),
    client_ip           VARCHAR(45),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_fe_session_audit PRIMARY KEY (fe_session_id, event_type, created_at),
    CONSTRAINT chk_fe_session_event_type
        CHECK (event_type IN ('CREATED','EXPIRED','INVALIDATED','ADVISORY_SET')),
    CONSTRAINT chk_fe_session_auth_level
        CHECK (auth_level IN ('L1','L2','L3'))
);

CREATE INDEX IF NOT EXISTS idx_fe_session_audit_user
    ON idem_hub.fe_session_audit (qim_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_fe_session_audit_event
    ON idem_hub.fe_session_audit (event_type, created_at DESC);

COMMENT ON TABLE  idem_hub.fe_session_audit               IS '§12.5 FE 세션 감사 이력 (BFF→IdO 이관)';
COMMENT ON COLUMN idem_hub.fe_session_audit.fe_session_id IS 'SecureRandom Base64URL 256-bit — Redis key 와 동일';
COMMENT ON COLUMN idem_hub.fe_session_audit.event_type    IS 'CREATED / EXPIRED / INVALIDATED(MANDATORY) / ADVISORY_SET';

-- ──────────────────────────────────────────────────────────────
-- 2. returnUrl 화이트리스트 (FeReturnUrlWhitelist)
--    §12.6 returnUrl 검증 SoR.
--    application.yml idem_hub.fe.allowed-return-urls 와 병행 사용.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS idem_hub.fe_return_url_whitelist (
    id                  SERIAL        NOT NULL,
    url_prefix          VARCHAR(500)  NOT NULL,       -- e.g. https://agency-a.example.com
    description         VARCHAR(200),
    active              BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_fe_return_url_whitelist PRIMARY KEY (id),
    CONSTRAINT uq_fe_return_url_prefix UNIQUE (url_prefix)
);

CREATE INDEX IF NOT EXISTS idx_fe_return_url_active
    ON idem_hub.fe_return_url_whitelist (active, url_prefix);

COMMENT ON TABLE idem_hub.fe_return_url_whitelist IS '§12.6 FE returnUrl 화이트리스트 SoR';

-- PoC 초기 화이트리스트
INSERT INTO idem_hub.fe_return_url_whitelist (url_prefix, description, active) VALUES
    ('https://agency-a.example.com', '테스트 기관 A',              TRUE),
    ('https://agency-b.example.com', '테스트 기관 B',              TRUE),
    ('http://localhost:8084',         'agency-stub (PoC)',          TRUE),
    ('http://localhost:3000',         'React dev server (개발전용)', TRUE),
    ('http://localhost:3001',         'Nginx React (OptionB)',      TRUE)
ON CONFLICT (url_prefix) DO NOTHING;

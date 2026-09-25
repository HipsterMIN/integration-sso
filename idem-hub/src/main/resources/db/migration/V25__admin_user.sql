-- ═══════════════════════════════════════════════════════════════════════════
-- V25: 관리자 계정·비밀번호 이력 (S7 관리자 인증 — ADR-015)
--
--   admin_user            : 관리자 신원(로컬 계정). 비밀번호 PBKDF2, TOTP 비밀은 AES-GCM 으로 봉인해 저장
--   admin_password_history: 최근 N개 비밀번호 해시(재사용 금지)
--   audit_log             : 관리 행위 감사를 위해 actor_type 'ADMIN'·event_category 'ADMIN' 허용
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS idem_hub.admin_user (
    admin_id              VARCHAR(36)   NOT NULL,
    username              VARCHAR(64)   NOT NULL,
    display_name          VARCHAR(100),
    password_hash         VARCHAR(400)  NOT NULL,
    role                  VARCHAR(30)   NOT NULL,
    tenant_code           VARCHAR(50),                  -- NULL = 전체(글로벌) 범위
    status                VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    totp_secret_enc       VARCHAR(400),                 -- AES-GCM 봉인(idem_hub.admin.secret-key), NULL = 미등록
    totp_enrolled         BOOLEAN       NOT NULL DEFAULT FALSE,
    must_change_password  BOOLEAN       NOT NULL DEFAULT TRUE,
    failed_attempts       SMALLINT      NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    password_changed_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    last_login_at         TIMESTAMPTZ,
    last_login_ip         VARCHAR(45),
    created_by            VARCHAR(64),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_admin_user PRIMARY KEY (admin_id),
    CONSTRAINT uq_admin_user_username UNIQUE (username),
    CONSTRAINT chk_admin_user_role   CHECK (role IN ('SYSTEM_ADMIN','POLICY_ADMIN','AUDITOR')),
    CONSTRAINT chk_admin_user_status CHECK (status IN ('ACTIVE','LOCKED','DISABLED')),
    CONSTRAINT fk_admin_user_tenant  FOREIGN KEY (tenant_code) REFERENCES idem_hub.tenant (tenant_code)
);
COMMENT ON TABLE idem_hub.admin_user IS '관리자 계정 (S7) — 역할 SYSTEM_ADMIN/POLICY_ADMIN/AUDITOR, tenant_code NULL 이면 전체 범위';

CREATE TABLE IF NOT EXISTS idem_hub.admin_password_history (
    id            BIGSERIAL     NOT NULL,
    admin_id      VARCHAR(36)   NOT NULL,
    password_hash VARCHAR(400)  NOT NULL,
    changed_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_admin_password_history PRIMARY KEY (id),
    CONSTRAINT fk_admin_password_history_admin FOREIGN KEY (admin_id) REFERENCES idem_hub.admin_user (admin_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_admin_password_history_admin ON idem_hub.admin_password_history (admin_id, changed_at DESC);

-- 관리 행위 감사: 주체 유형 ADMIN, 분류 ADMIN
ALTER TABLE idem_hub.audit_log DROP CONSTRAINT IF EXISTS chk_audit_actor_type;
ALTER TABLE idem_hub.audit_log ADD CONSTRAINT chk_audit_actor_type
    CHECK (actor_type IN ('USER','SYSTEM','AGENCY','ADMIN'));
ALTER TABLE idem_hub.audit_log DROP CONSTRAINT IF EXISTS chk_audit_event_category;
ALTER TABLE idem_hub.audit_log ADD CONSTRAINT chk_audit_event_category
    CHECK (event_category IN ('AUTH','HANDOFF','MEMBER','SESSION','WEBHOOK','SYSTEM','ADMIN'));
CREATE INDEX IF NOT EXISTS idx_audit_log_occurred ON idem_hub.audit_log (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor ON idem_hub.audit_log (actor_id, occurred_at DESC);

-- ============================================================
-- Q-Sign V2 : 감사 로그 / 인증 이벤트 이력
-- 설계서 §15 감사·추적 키 흐름 기반
-- ============================================================

-- ──────────────────────────────────────────────────────────────
-- 1. 인증 감사 로그
--    모든 인증 시도(성공·실패·잠금)를 불변 이력으로 기록.
--    §15: auditKey = SHA-256(correlationId ‖ authResultId ‖ timestamp)
-- ──────────────────────────────────────────────────────────────
CREATE TABLE idem_gate.auth_audit_log (
    audit_id             VARCHAR(36)   NOT NULL,
    audit_key            VARCHAR(300)  NOT NULL,          -- SHA-256(correlationId‖authResultId‖ts)
    correlation_id       VARCHAR(36)   NOT NULL,
    auth_result_id       VARCHAR(36),                     -- 성공 시에만 non-null
    event_type           VARCHAR(50)   NOT NULL,          -- AUTH_INITIATED / AUTH_SUCCESS / AUTH_FAIL / AUTH_LOCKED / LOCK_RELEASED
    provider_code        VARCHAR(50)   NOT NULL,
    identifier_hash      VARCHAR(300)  NOT NULL,
    auth_level           VARCHAR(10),
    client_ip            VARCHAR(45),                     -- IPv4 / IPv6
    user_agent           VARCHAR(500),
    request_id           VARCHAR(36),
    detail               JSONB,                           -- 이벤트 부가 정보
    occurred_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_auth_audit_log PRIMARY KEY (audit_id),
    CONSTRAINT uq_auth_audit_key UNIQUE (audit_key)
);

CREATE INDEX idx_audit_log_correlation   ON idem_gate.auth_audit_log (correlation_id);
CREATE INDEX idx_audit_log_identifier    ON idem_gate.auth_audit_log (identifier_hash, occurred_at DESC);
CREATE INDEX idx_audit_log_event_type    ON idem_gate.auth_audit_log (event_type, occurred_at DESC);
CREATE INDEX idx_audit_log_occurred_at   ON idem_gate.auth_audit_log (occurred_at DESC);

COMMENT ON TABLE  idem_gate.auth_audit_log             IS '§15 인증 감사 로그 – 불변 이력 (DELETE/UPDATE 금지)';
COMMENT ON COLUMN idem_gate.auth_audit_log.audit_key   IS 'SHA-256(correlationId ‖ authResultId ‖ ts) 위변조 검증용';
COMMENT ON COLUMN idem_gate.auth_audit_log.client_ip   IS 'PII 주의 – 마스킹 정책 적용 대상';

-- ──────────────────────────────────────────────────────────────
-- 2. 잠금 이벤트 이력 (LockEvent)
--    auth_lock 의 상태 전이를 감사 목적으로 별도 기록.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE idem_gate.lock_event_log (
    lock_event_id        VARCHAR(36)   NOT NULL,
    lock_key             VARCHAR(400)  NOT NULL,
    event_type           VARCHAR(30)   NOT NULL,          -- ATTEMPT / LOCKED / UNLOCK_MANUAL / UNLOCK_EXPIRED
    attempt_count_before SMALLINT,
    attempt_count_after  SMALLINT,
    triggered_by         VARCHAR(100),                    -- 시스템 / 관리자 ID
    detail               JSONB,
    occurred_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_lock_event_log PRIMARY KEY (lock_event_id),
    CONSTRAINT chk_lock_event_type
        CHECK (event_type IN ('ATTEMPT','LOCKED','UNLOCK_MANUAL','UNLOCK_EXPIRED','RESET'))
);

CREATE INDEX idx_lock_event_key  ON idem_gate.lock_event_log (lock_key, occurred_at DESC);
CREATE INDEX idx_lock_event_type ON idem_gate.lock_event_log (event_type, occurred_at DESC);

COMMENT ON TABLE idem_gate.lock_event_log IS '잠금 상태 전이 감사 이력';

-- ──────────────────────────────────────────────────────────────
-- 3. Nonce 재사용 방지 테이블
--    §16.4.1 Clock Skew / Replay Attack 방지.
--    TTL 경과 레코드는 배치로 purge.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE idem_gate.used_nonce (
    nonce                VARCHAR(200)  NOT NULL,
    correlation_id       VARCHAR(36)   NOT NULL,
    provider_code        VARCHAR(50)   NOT NULL,
    used_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    expires_at           TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_used_nonce PRIMARY KEY (nonce)
);

CREATE INDEX idx_used_nonce_expires ON idem_gate.used_nonce (expires_at);  -- purge 배치 대상 (일반 인덱스; partial index WHERE NOW() 는 IMMUTABLE 제약으로 불가)

COMMENT ON TABLE  idem_gate.used_nonce          IS '§16.4.1 Nonce 재사용 방지 – TTL 기반 자동 purge 대상';
COMMENT ON COLUMN idem_gate.used_nonce.nonce    IS 'JTI 또는 HMAC-based nonce';

-- ============================================================
-- Q-Sign V3 : OIDC Authorization Code Flow 세션 추적
-- 설계서 §9.7 카카오 OIDC 브로커링 흐름 기반
--
-- 목적:
--   1. OIDC Authorization 세션 추적 (state/nonce 는 Redis 관리,
--      여기서는 완료된 흐름의 감사 이력만 보관)
--   2. idToken 재사용(replay) 방지 — jti 기반 중복 검사
-- ============================================================

-- ──────────────────────────────────────────────────────────────
-- 1. OIDC 인증 세션 이력 (OidcSessionLog)
--    Authorization URL 발급부터 callback 완료까지 감사 이력.
--    Redis state 는 TTL 후 자동 삭제되므로,
--    완료된 흐름의 correlation_id 연결 고리를 여기에 보관.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE idem_gate.oidc_session_log (
    session_log_id      VARCHAR(36)   NOT NULL,
    correlation_id      VARCHAR(36)   NOT NULL,
    provider_code       VARCHAR(50)   NOT NULL DEFAULT 'KAKAO_OIDC',
    state               VARCHAR(64)   NOT NULL,       -- Authorization URL 발급 시 state (완료 후 기록)
    nonce               VARCHAR(64),                  -- idToken nonce (검증 완료 후 기록)
    auth_result_id      VARCHAR(36),                  -- 성공 시 연결된 AuthResult ID
    identifier_hash     VARCHAR(300),                 -- SHA-256(sub) — PII 비보관
    requested_level     VARCHAR(10),
    return_url          VARCHAR(500),
    status              VARCHAR(20)   NOT NULL,       -- INITIATED / COMPLETED / FAILED / DENIED
    fail_reason         VARCHAR(200),
    client_ip           VARCHAR(45),
    initiated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMPTZ,
    CONSTRAINT pk_oidc_session_log PRIMARY KEY (session_log_id),
    CONSTRAINT uq_oidc_state UNIQUE (state),
    CONSTRAINT chk_oidc_status
        CHECK (status IN ('INITIATED','COMPLETED','FAILED','DENIED'))
);

CREATE INDEX idx_oidc_session_correlation ON idem_gate.oidc_session_log (correlation_id);
CREATE INDEX idx_oidc_session_auth_result ON idem_gate.oidc_session_log (auth_result_id)
    WHERE auth_result_id IS NOT NULL;
CREATE INDEX idx_oidc_session_status      ON idem_gate.oidc_session_log (status, initiated_at DESC);

COMMENT ON TABLE  idem_gate.oidc_session_log                  IS '§9.7 OIDC Authorization Code Flow 감사 이력';
COMMENT ON COLUMN idem_gate.oidc_session_log.state            IS 'CSRF 방어용 state (1회성, 완료 후 기록용)';
COMMENT ON COLUMN idem_gate.oidc_session_log.identifier_hash  IS 'SHA-256(sub) — 원문(PII) 저장 금지';
COMMENT ON COLUMN idem_gate.oidc_session_log.return_url       IS '인증 완료 후 리다이렉트 기관 URL';

-- ──────────────────────────────────────────────────────────────
-- 2. ID Token JTI 중복 방지 (ReplayGuard)
--    nonce 기반 replay attack 방지는 Redis(OidcStateStore) 에서 1차 처리.
--    DB 는 nonce 재사용 이력을 장기 보관 (감사 목적).
-- ──────────────────────────────────────────────────────────────
CREATE TABLE idem_gate.oidc_nonce_used (
    nonce               VARCHAR(64)   NOT NULL,
    provider_code       VARCHAR(50)   NOT NULL DEFAULT 'KAKAO_OIDC',
    correlation_id      VARCHAR(36)   NOT NULL,
    used_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_oidc_nonce_used PRIMARY KEY (nonce, provider_code)
);

CREATE INDEX idx_oidc_nonce_used_at ON idem_gate.oidc_nonce_used (used_at DESC);

COMMENT ON TABLE  idem_gate.oidc_nonce_used         IS 'OIDC nonce 재사용 방지 이력 (replay attack 방어)';
COMMENT ON COLUMN idem_gate.oidc_nonce_used.nonce   IS 'idToken payload.nonce — 1회만 허용';

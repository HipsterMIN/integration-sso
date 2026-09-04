-- ============================================================
-- V14: Cross-Agency SSO — CAST 토큰 감사 테이블 + SSO 세션 연결 테이블
-- Sprint 13 — 설계서 §13.4 스키마 정의
-- ============================================================

-- ── 1. CAST 토큰 감사 테이블 ────────────────────────────────────────────────
-- 발급된 모든 CAST 토큰의 감사 이력 (보안 감사 / GDPR 파기 기준선)
CREATE TABLE IF NOT EXISTS ido.cast_token_audit (
    jti              VARCHAR(36)   NOT NULL,                   -- JWT ID (UUID v7) PK
    qim_user_id      VARCHAR(36)   NOT NULL,                   -- OnePass 사용자 ID
    source_agency    VARCHAR(50)   NOT NULL,                   -- 발행 기관 코드 (기관 A)
    target_agency    VARCHAR(50)   NOT NULL,                   -- 대상 기관 코드 (기관 B)
    auth_level       VARCHAR(10)   NOT NULL,                   -- 인증 수준 (LOW/MEDIUM/HIGH)
    issued_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),     -- 발행 시각
    expires_at       TIMESTAMPTZ   NOT NULL,                   -- 만료 시각 (issued_at + 5분)
    consumed_at      TIMESTAMPTZ,                              -- 소비 시각 (NULL = 미사용)
    status           VARCHAR(20)   NOT NULL DEFAULT 'ISSUED',  -- ISSUED / CONSUMED / EXPIRED
    consumer_ip      VARCHAR(45),                              -- 소비 요청 IP (감사용)
    correlation_id   VARCHAR(36),                              -- 흐름 추적 키

    CONSTRAINT pk_cast_token_audit PRIMARY KEY (jti),
    CONSTRAINT chk_cast_status CHECK (status IN ('ISSUED', 'CONSUMED', 'EXPIRED')),
    CONSTRAINT chk_cast_auth_level CHECK (auth_level IN ('LOW', 'MEDIUM', 'HIGH'))
);

-- 인덱스: 사용자별 CAST 토큰 조회 (GDPR 파기 시 userId 기준 삭제)
CREATE INDEX IF NOT EXISTS idx_cast_audit_qim_user_id ON ido.cast_token_audit(qim_user_id);

-- 인덱스: 만료 배치 처리 (expired_at < NOW() AND status = 'ISSUED')
CREATE INDEX IF NOT EXISTS idx_cast_audit_expires_at ON ido.cast_token_audit(expires_at) WHERE status = 'ISSUED';

-- 인덱스: 기관별 CAST 토큰 통계
CREATE INDEX IF NOT EXISTS idx_cast_audit_source_agency ON ido.cast_token_audit(source_agency, issued_at DESC);
CREATE INDEX IF NOT EXISTS idx_cast_audit_target_agency ON ido.cast_token_audit(target_agency, issued_at DESC);

COMMENT ON TABLE  ido.cast_token_audit                IS 'Cross-Agency SSO CAST 토큰 감사 이력';
COMMENT ON COLUMN ido.cast_token_audit.jti            IS 'JWT ID (UUID v7) — Redis 1회 소비 키와 동일';
COMMENT ON COLUMN ido.cast_token_audit.status         IS 'ISSUED: 발급됨, CONSUMED: 기관 B에서 소비됨, EXPIRED: 만료';


-- ── 2. SSO 세션 연결 테이블 ─────────────────────────────────────────────────
-- 기관 A → 기관 B Cross-Agency SSO 성공 이력
-- (CAST 토큰 소비 + HandoffTicket 발급 성공 시 기록)
CREATE TABLE IF NOT EXISTS ido.sso_session_link (
    id               VARCHAR(36)   NOT NULL DEFAULT gen_random_uuid(),
    cast_jti         VARCHAR(36)   NOT NULL,                   -- 소비된 CAST 토큰 JTI (FK)
    qim_user_id      VARCHAR(36)   NOT NULL,                   -- OnePass 사용자 ID
    source_agency    VARCHAR(50)   NOT NULL,                   -- 원 로그인 기관 (기관 A)
    target_agency    VARCHAR(50)   NOT NULL,                   -- 진입 기관 (기관 B)
    handoff_ticket_id VARCHAR(36),                             -- 발급된 Handoff Ticket ID
    linked_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),     -- SSO 연결 시각
    source_fe_session VARCHAR(64),                             -- 기관 A FE 세션 ID (감사용)
    correlation_id   VARCHAR(36),                              -- 흐름 추적 키

    CONSTRAINT pk_sso_session_link PRIMARY KEY (id),
    CONSTRAINT fk_sso_cast_jti FOREIGN KEY (cast_jti)
        REFERENCES ido.cast_token_audit(jti) ON DELETE RESTRICT
);

-- 인덱스: 사용자별 SSO 연결 이력
CREATE INDEX IF NOT EXISTS idx_sso_link_qim_user_id ON ido.sso_session_link(qim_user_id, linked_at DESC);

-- 인덱스: CAST JTI로 연결 이력 조회
CREATE INDEX IF NOT EXISTS idx_sso_link_cast_jti ON ido.sso_session_link(cast_jti);

-- 인덱스: 기관 A→B 통계
CREATE INDEX IF NOT EXISTS idx_sso_link_agencies ON ido.sso_session_link(source_agency, target_agency, linked_at DESC);

COMMENT ON TABLE  ido.sso_session_link                 IS 'Cross-Agency SSO 성공 연결 이력';
COMMENT ON COLUMN ido.sso_session_link.cast_jti        IS '소비된 CAST 토큰 JTI — cast_token_audit 참조';
COMMENT ON COLUMN ido.sso_session_link.handoff_ticket_id IS '기관 B 진입에 사용된 Handoff Ticket ID';

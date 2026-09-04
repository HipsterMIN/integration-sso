-- ============================================================================
-- V1: 연합 인가(Federated Authorization) — L1 코어 스키마
--   설계 원칙:
--     · 부여(assignment)는 중앙 SoR, 해석/집행(decision)은 기관 지역
--     · 단일 멀티테넌트(agency_code) + Row-Level Security (기관별 물리 스키마 금지)
--     · 역할 코드는 기관별 불투명 문자열 — 의미를 기관 간 통일하지 않음
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS authz;

-- ── 1. 역할 카탈로그 ────────────────────────────────────────────────────────
-- 기관별 네임스페이스. agency_code='PLATFORM' = 전역 역할(소수 고정).
CREATE TABLE IF NOT EXISTS authz.authz_role (
    agency_code    VARCHAR(50)  NOT NULL,                  -- 테넌트 키 (RLS)
    role_code      VARCHAR(64)  NOT NULL,                  -- 기관이 정의 (불투명)
    name           VARCHAR(128),
    description    TEXT,
    is_assignable  BOOLEAN      NOT NULL DEFAULT TRUE,     -- false면 신규 부여 차단
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by     VARCHAR(128),

    CONSTRAINT pk_authz_role PRIMARY KEY (agency_code, role_code)
);

COMMENT ON TABLE  authz.authz_role            IS '기관별 역할 카탈로그(의미 불투명). PLATFORM=전역 역할.';
COMMENT ON COLUMN authz.authz_role.role_code  IS '기관이 자유 정의하는 역할 코드. 플랫폼은 의미를 해석하지 않음.';

-- ── 2. 사용자 역할 부여 (assignment) — ★ 핵심 SoR ──────────────────────────
CREATE TABLE IF NOT EXISTS authz.authz_user_role (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    qim_user_id   VARCHAR(36)  NOT NULL,                   -- q-im 정체성 참조
    agency_code   VARCHAR(50)  NOT NULL,                   -- 테넌트 키 (RLS)
    role_code     VARCHAR(64)  NOT NULL,
    granted_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    granted_by    VARCHAR(128) NOT NULL,                   -- 관리자/기관운영자/시스템
    expires_at    TIMESTAMPTZ,                             -- JIT/한시 권한 (NULL=무기한)
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE/REVOKED/EXPIRED
    source        VARCHAR(24)  NOT NULL DEFAULT 'API',     -- CONSOLE/SCIM/API/AGENCY_PUSH
    revoked_at    TIMESTAMPTZ,
    revoked_by    VARCHAR(128),

    CONSTRAINT pk_authz_user_role PRIMARY KEY (id),
    CONSTRAINT uq_authz_user_role UNIQUE (qim_user_id, agency_code, role_code),
    CONSTRAINT chk_authz_ur_status CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT fk_authz_ur_role FOREIGN KEY (agency_code, role_code)
        REFERENCES authz.authz_role(agency_code, role_code) ON DELETE RESTRICT
);

-- 사용자×기관 유효권한 조회(토큰 클레임 발급 핵심 경로)
CREATE INDEX IF NOT EXISTS idx_authz_ur_user_agency
    ON authz.authz_user_role(qim_user_id, agency_code) WHERE status = 'ACTIVE';
-- 기관 스코프 회원/역할 조회
CREATE INDEX IF NOT EXISTS idx_authz_ur_agency_role
    ON authz.authz_user_role(agency_code, role_code) WHERE status = 'ACTIVE';
-- 만료 배치 스캔
CREATE INDEX IF NOT EXISTS idx_authz_ur_expires
    ON authz.authz_user_role(expires_at) WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;

COMMENT ON TABLE  authz.authz_user_role        IS '사용자 역할 부여(중앙 SoR). 토큰 roles[] 클레임의 원천.';
COMMENT ON COLUMN authz.authz_user_role.status IS 'ACTIVE: 유효, REVOKED: 회수, EXPIRED: 만료(스케줄러 전이)';

-- ── 3. append-only 부여 감사 (100% 기록) ───────────────────────────────────
CREATE TABLE IF NOT EXISTS authz.authz_grant_audit (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    event          VARCHAR(16)  NOT NULL,                  -- GRANT/REVOKE/EXPIRE/ROLE_CREATED
    qim_user_id    VARCHAR(36),
    agency_code    VARCHAR(50)  NOT NULL,
    role_code      VARCHAR(64),
    actor          VARCHAR(128) NOT NULL,                  -- 행위 주체
    actor_ip       VARCHAR(45),
    reason         TEXT,
    correlation_id VARCHAR(36),
    at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_authz_grant_audit PRIMARY KEY (id),
    CONSTRAINT chk_authz_audit_event CHECK (event IN ('GRANT','REVOKE','EXPIRE','ROLE_CREATED'))
);

CREATE INDEX IF NOT EXISTS idx_authz_audit_user   ON authz.authz_grant_audit(qim_user_id, at DESC);
CREATE INDEX IF NOT EXISTS idx_authz_audit_agency ON authz.authz_grant_audit(agency_code, at DESC);

COMMENT ON TABLE authz.authz_grant_audit IS '인가 부여/회수 append-only 감사 이력(법적·보안 추적).';

-- ── 4. Row-Level Security (멀티테넌트 행 격리) ──────────────────────────────
-- 정책: 세션 GUC app.current_agency 가 설정되면 해당 기관 행만, 미설정(NULL)이면
--       전체 허용(서비스/플랫폼 컨텍스트). 애플리케이션 레벨 agency_code 필터와
--       이중 방어(defense-in-depth). 운영 경화 시 GUC 강제(미설정→거부)로 전환 예정.
-- ※ PostgreSQL 전용. 테스트(H2)·단위테스트에는 영향 없음.
ALTER TABLE authz.authz_role           ENABLE ROW LEVEL SECURITY;
ALTER TABLE authz.authz_user_role      ENABLE ROW LEVEL SECURITY;
ALTER TABLE authz.authz_grant_audit    ENABLE ROW LEVEL SECURITY;

CREATE POLICY rls_authz_role_tenant ON authz.authz_role
    USING (current_setting('app.current_agency', TRUE) IS NULL
           OR agency_code = current_setting('app.current_agency', TRUE)
           OR agency_code = 'PLATFORM');

CREATE POLICY rls_authz_user_role_tenant ON authz.authz_user_role
    USING (current_setting('app.current_agency', TRUE) IS NULL
           OR agency_code = current_setting('app.current_agency', TRUE));

CREATE POLICY rls_authz_grant_audit_tenant ON authz.authz_grant_audit
    USING (current_setting('app.current_agency', TRUE) IS NULL
           OR agency_code = current_setting('app.current_agency', TRUE));

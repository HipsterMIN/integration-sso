-- ═══════════════════════════════════════════════════════════════════════════
-- S8-b 할당(assignment) 모델 — 사용자 ↔ Service(기관) 할당 SoR
--
-- 역할(authz_user_role) 은 "무엇을 할 수 있나", 할당은 "이 Service 의 사용자인가" 다.
-- 프로파일이 policy.assignment.required=true 면 hub 가 발급 전에 할당을 확인한다(미할당 → E-IDO-120,
-- selfSignup 허용 시 GUEST). 역할 부여는 할당을 자동으로 만든다(source=ROLE_GRANT).
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS idem_authz.authz_assignment (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    qim_user_id   VARCHAR(36)  NOT NULL,
    agency_code   VARCHAR(50)  NOT NULL,                   -- Service(기관) 코드 = 테넌트 키 (RLS)
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE/REVOKED/EXPIRED
    source        VARCHAR(24)  NOT NULL DEFAULT 'API',     -- CONSOLE/SCIM/API/AGENCY_PUSH/ROLE_GRANT/SELF_SIGNUP
    granted_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    granted_by    VARCHAR(128) NOT NULL,
    expires_at    TIMESTAMPTZ,
    reason        TEXT,
    revoked_at    TIMESTAMPTZ,
    revoked_by    VARCHAR(128),
    CONSTRAINT pk_authz_assignment PRIMARY KEY (id),
    CONSTRAINT uq_authz_assignment UNIQUE (qim_user_id, agency_code),
    CONSTRAINT chk_authz_assignment_status CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED'))
);
CREATE INDEX IF NOT EXISTS idx_authz_assignment_agency
    ON idem_authz.authz_assignment(agency_code) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_authz_assignment_expires
    ON idem_authz.authz_assignment(expires_at) WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;
COMMENT ON TABLE  idem_authz.authz_assignment        IS '사용자 ↔ Service 할당(중앙 SoR). hub ASSIGNMENT 정책 규칙·Handoff/CAST 발급의 원천.';
COMMENT ON COLUMN idem_authz.authz_assignment.source IS 'ROLE_GRANT: 역할 부여로 자동 생성, SELF_SIGNUP: 기관 셀프 가입 완료 보고, AGENCY_PUSH: 기관 API';

ALTER TABLE idem_authz.authz_assignment ENABLE ROW LEVEL SECURITY;
CREATE POLICY rls_authz_assignment_tenant ON idem_authz.authz_assignment
    USING (current_setting('app.current_agency', TRUE) IS NULL
           OR agency_code = current_setting('app.current_agency', TRUE));

-- 감사 이벤트에 할당/해제 추가
ALTER TABLE idem_authz.authz_grant_audit DROP CONSTRAINT IF EXISTS chk_authz_audit_event;
ALTER TABLE idem_authz.authz_grant_audit ADD CONSTRAINT chk_authz_audit_event
    CHECK (event IN ('GRANT','REVOKE','EXPIRE','ROLE_CREATED','ASSIGN','UNASSIGN'));

-- 백필: 이미 활성 역할을 가진 (사용자, Service) 는 할당된 것으로 본다 — 도입 전 데이터가 잠기지 않도록
INSERT INTO idem_authz.authz_assignment (qim_user_id, agency_code, status, source, granted_at, granted_by, reason)
SELECT DISTINCT ur.qim_user_id, ur.agency_code, 'ACTIVE', 'ROLE_GRANT', NOW(), 'SYSTEM', 'S8-b 백필: 활성 역할 보유'
FROM idem_authz.authz_user_role ur
WHERE ur.status = 'ACTIVE'
ON CONFLICT (qim_user_id, agency_code) DO NOTHING;

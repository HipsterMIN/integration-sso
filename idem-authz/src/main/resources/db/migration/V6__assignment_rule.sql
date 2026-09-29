-- ═══════════════════════════════════════════════════════════════════════════
-- 1.1 PR-2: 그룹·속성 규칙 할당 (S8-b PR-2 남긴 것)
--
-- 직접 할당(authz_assignment) 외에 "조건을 만족하는 사용자는 이 Service 에 할당된 것으로 본다" 는 규칙을 둔다.
--   GROUP     : match_key = "<agencyCode>:<roleCode>" (SCIM Group id) — 그 역할이 유효(ACTIVE·만료 전)한 사용자
--   ATTRIBUTE : match_key = 속성명(authLevel·providerCode …, hub 가 발급 시 함께 보내는 비-PII 컨텍스트),
--               match_values = 허용 값 목록(콤마 구분, '*' = 값이 있으면 통과)
-- 규칙 할당은 hub 의 접근 평가(POST /users/{id}/access) 시점에 실체화(materialize)된다: source=RULE, rule_id 로 추적,
-- expires_days 가 있으면 그만큼 뒤 만료(재평가 강제). 규칙 비활성화 → 그 규칙으로 만든 ACTIVE 할당을 즉시 회수(UNASSIGNED 전파).
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS idem_authz.authz_assignment_rule (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    agency_code   VARCHAR(50)  NOT NULL,                   -- 대상 Service(기관) = 테넌트 키 (RLS)
    rule_type     VARCHAR(16)  NOT NULL,                   -- GROUP / ATTRIBUTE
    match_key     VARCHAR(160) NOT NULL,
    match_values  TEXT,                                    -- ATTRIBUTE 전용, 콤마 구분
    expires_days  INTEGER,                                 -- 실체화된 할당의 유효 일수 (NULL = 무기한)
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    description   VARCHAR(500),
    created_by    VARCHAR(128) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    disabled_at   TIMESTAMPTZ,
    disabled_by   VARCHAR(128),
    CONSTRAINT pk_authz_assignment_rule PRIMARY KEY (id),
    CONSTRAINT chk_authz_assignment_rule_type CHECK (rule_type IN ('GROUP', 'ATTRIBUTE')),
    CONSTRAINT chk_authz_assignment_rule_expires CHECK (expires_days IS NULL OR expires_days > 0)
);
CREATE INDEX IF NOT EXISTS idx_authz_assignment_rule_agency
    ON idem_authz.authz_assignment_rule(agency_code, created_at) WHERE enabled = TRUE;
COMMENT ON TABLE  idem_authz.authz_assignment_rule IS '1.1 규칙 할당: GROUP(역할 보유)·ATTRIBUTE(발급 컨텍스트 속성) 조건을 만족하면 접근 평가 시 할당을 실체화한다.';
COMMENT ON COLUMN idem_authz.authz_assignment_rule.match_key IS 'GROUP: agencyCode:roleCode, ATTRIBUTE: 속성명(authLevel·providerCode …)';

-- RLS 는 두지 않는다 — V4(D3) 가 동작하지 않는 GUC 기반 정책을 걷어냈고 테넌트 스코프는 서비스 계층이 강제한다.

-- 실체화된 할당 ↔ 규칙 추적
ALTER TABLE idem_authz.authz_assignment ADD COLUMN IF NOT EXISTS rule_id UUID;
CREATE INDEX IF NOT EXISTS idx_authz_assignment_rule
    ON idem_authz.authz_assignment(rule_id) WHERE rule_id IS NOT NULL AND status = 'ACTIVE';
COMMENT ON COLUMN idem_authz.authz_assignment.source  IS 'ROLE_GRANT: 역할 부여로 자동 생성, SELF_SIGNUP: 기관 셀프 가입 완료 보고, AGENCY_PUSH: 기관 API, RULE: 규칙 실체화(rule_id)';
COMMENT ON COLUMN idem_authz.authz_assignment.rule_id IS 'source=RULE 일 때 실체화한 규칙. 규칙 비활성화 시 이 할당은 회수된다.';

-- 감사 이벤트에 규칙 생성/비활성 추가
ALTER TABLE idem_authz.authz_grant_audit DROP CONSTRAINT IF EXISTS chk_authz_audit_event;
ALTER TABLE idem_authz.authz_grant_audit ADD CONSTRAINT chk_authz_audit_event
    CHECK (event IN ('GRANT','REVOKE','EXPIRE','ROLE_CREATED','ASSIGN','UNASSIGN','RULE_CREATED','RULE_DISABLED'));

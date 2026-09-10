-- =============================================================================
-- V22 (범용화 S4b): Tenant/Service 계층 정립 + 회원통합 브로커 흐름 제거
--   docs/generalization-plan.md §2.0 · §3 S4b
-- =============================================================================
--
-- 1. ido.tenant — Tenant(Realm): 운영기관·사용자 디렉터리 단위. 설치본은 최소 1개(DEFAULT).
-- 2. agency_meta.tenant_code — Service(기관)가 속한 Tenant. 기존 행은 DEFAULT.
-- 3. agency_meta.profile 의 루트 블록 `tenant` → `service` (Service Profile 개명, + service.tenant 참조)
-- 4. 전 기관 프로비저닝 제거: provisioning_outbox 삭제, PROVISIONING 엔드포인트 행 삭제
--    (agency_endpoint_registry 자체는 CAST_VERIFY·WEBHOOK·STATUS·GATEWAY_INBOUND 가 쓰므로 유지)
-- =============================================================================

-- 1. Tenant
CREATE TABLE IF NOT EXISTS ido.tenant (
    tenant_code   VARCHAR(50)  NOT NULL,
    name          VARCHAR(200) NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_tenant PRIMARY KEY (tenant_code),
    CONSTRAINT chk_tenant_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
COMMENT ON TABLE  ido.tenant IS 'Tenant(Realm) — 운영기관·사용자 디렉터리 단위. Service(agency_meta)는 Tenant 에 속한다';
COMMENT ON COLUMN ido.tenant.tenant_code IS 'Tenant 코드 — registry qim_user.tenant_code 와 같은 값';

INSERT INTO ido.tenant (tenant_code, name, status)
VALUES ('DEFAULT', 'Default Tenant', 'ACTIVE')
ON CONFLICT (tenant_code) DO NOTHING;

-- 2. Service → Tenant
ALTER TABLE ido.agency_meta
    ADD COLUMN IF NOT EXISTS tenant_code VARCHAR(50) NOT NULL DEFAULT 'DEFAULT';
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_agency_meta_tenant') THEN
        ALTER TABLE ido.agency_meta
            ADD CONSTRAINT fk_agency_meta_tenant FOREIGN KEY (tenant_code)
            REFERENCES ido.tenant (tenant_code);
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_agency_meta_tenant ON ido.agency_meta (tenant_code);
COMMENT ON COLUMN ido.agency_meta.tenant_code IS '소속 Tenant(Realm). S4b';

-- 3. Service Profile 루트 블록 개명: tenant → service (+ service.tenant)
UPDATE ido.agency_meta
   SET profile = (profile - 'tenant')
                 || jsonb_build_object('service',
                        (profile -> 'tenant') || jsonb_build_object('tenant', tenant_code))
 WHERE profile IS NOT NULL
   AND profile ? 'tenant'
   AND NOT (profile ? 'service');

-- 4. 전 기관 프로비저닝 제거
DROP TABLE IF EXISTS ido.provisioning_outbox;
DELETE FROM ido.agency_endpoint_registry WHERE endpoint_type = 'PROVISIONING';

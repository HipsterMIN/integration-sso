-- S2 범용화 (docs/generalization-plan.md §2.1): Tenant Profile — 기관 설정의 선언적 단일 원천
--
-- agency_meta 의 낱개 컬럼(official_name·min_auth_level·integration_type·…)은 이제 profile JSONB 의 투영이다.
-- 쓰기는 프로파일을 거쳐 컬럼으로 내려가고(TenantProfileService.put / TenantProfileMapper.applyToEntity),
-- 레거시 쓰기 경로는 저장 직전에 컬럼 → 프로파일을 동기화한다(syncProfileColumn). 읽기는 아직 컬럼이 진실이다.
-- 스키마: idem-hub/src/main/resources/tenant-profile/tenant-profile.v1.schema.json

ALTER TABLE ido.agency_meta
    ADD COLUMN IF NOT EXISTS profile                JSONB,
    ADD COLUMN IF NOT EXISTS profile_schema_version INTEGER;

COMMENT ON COLUMN ido.agency_meta.profile
    IS 'Tenant Profile (tenant-profile.v{n}.schema.json). 기관 설정의 단일 원천 — 낱개 컬럼은 이 문서의 투영';
COMMENT ON COLUMN ido.agency_meta.profile_schema_version
    IS 'profile 이 따르는 스키마 버전 (마이그레이터 체인 기준)';

-- 백필: 기존 컬럼 → 프로파일 v1. null 값 키는 제거(jsonb_strip_nulls). identity.attributes 가 없으면 "제한 없음".
UPDATE ido.agency_meta
   SET profile = jsonb_strip_nulls(jsonb_build_object(
           'schemaVersion', 1,
           'tenant', jsonb_build_object(
               'code',   agency_code,
               'name',   official_name,
               'status', CASE WHEN active THEN 'ACTIVE' ELSE 'INACTIVE' END),
           'protocol', jsonb_build_object(
               'type', integration_type,
               'endpoints', jsonb_build_object(
                   'callbackWhitelist', callback_whitelist,
                   'bridge',            bridge_endpoint,
                   'apacheGate',        apache_gate_endpoint,
                   'ssoDomain',         sso_domain)),
           'identity', jsonb_build_object(
               'attributes', allowed_attributes),
           'policy', jsonb_build_object(
               'minAuthLevel',  min_auth_level,
               'policyVersion', policy_version,
               'maintenance',   maintenance_windows),
           'limits', jsonb_build_object(
               'daily', daily_lookup_limit))),
       profile_schema_version = 1
 WHERE profile IS NULL;

CREATE INDEX IF NOT EXISTS idx_agency_meta_profile_type
    ON ido.agency_meta ((profile -> 'protocol' ->> 'type'));

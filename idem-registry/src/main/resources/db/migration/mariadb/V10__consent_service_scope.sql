-- ============================================================
-- V10 (1.1 PR-9): 동의 카탈로그 — 동의 버전을 서비스(기관) 범위로도 둔다 (플랜 §5 #8, S8 에서 남긴 것)
--   service_code NULL  = 플랫폼 공통 항목 (종전과 같다 — TERMS_OF_SERVICE·PRIVACY_POLICY …)
--   service_code = 'X' = 서비스 X 에만 보이는 항목
--   사용자 동의 기록(consent_record)은 그대로 version_id 를 가리킨다 — 카탈로그가 바뀌면 새 버전에 다시 동의한다.
--   PostgreSQL 판: db/migration/postgresql/V2__consent_service_scope.sql
-- ============================================================

ALTER TABLE consent_version
    ADD COLUMN IF NOT EXISTS service_code VARCHAR(50) NULL COMMENT '1.1 동의 카탈로그 범위 — NULL 이면 플랫폼 공통, 값이 있으면 그 서비스(기관) 전용';

CREATE INDEX IF NOT EXISTS idx_consent_ver_scope ON consent_version (service_code, consent_type, status);

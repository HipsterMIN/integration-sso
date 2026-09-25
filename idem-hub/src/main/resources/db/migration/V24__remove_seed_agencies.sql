-- ═══════════════════════════════════════════════════════════════════════════
-- V24: 운영 마이그레이션에 실려 있던 PoC 시드 기관 제거 (D3 설치본 정직화)
--
--   V1/V8  AGENCY_STUB_001                         — 개발용 stub 기관 (api key 'stub-api-key-dev-001' 해시 포함)
--   V13    AGENCY_BRIDGE_001 · AGENCY_APACHEGATE_001 · AGENCY_SSO_001 · AGENCY_STRICT_L3 · AGENCY_CHAOS_001
--
-- 설치본은 기관 0개로 시작한다. 로컬 개발·tenant-sample 은 scripts/dev/seed-dev-agencies.sh 가 관리 API 로 같은 기관을 만든다.
-- FK(agency_webhook_config·agency_rate_limit_config·agency_endpoint_registry) 순서로 지운다. 감사·이력 행은 남긴다.
-- ═══════════════════════════════════════════════════════════════════════════
DELETE FROM ido.agency_endpoint_registry
 WHERE agency_code IN ('AGENCY_STUB_001','AGENCY_BRIDGE_001','AGENCY_APACHEGATE_001','AGENCY_SSO_001','AGENCY_STRICT_L3','AGENCY_CHAOS_001');
DELETE FROM ido.agency_rate_limit_config
 WHERE agency_code IN ('AGENCY_STUB_001','AGENCY_BRIDGE_001','AGENCY_APACHEGATE_001','AGENCY_SSO_001','AGENCY_STRICT_L3','AGENCY_CHAOS_001');
DELETE FROM ido.agency_webhook_config
 WHERE agency_code IN ('AGENCY_STUB_001','AGENCY_BRIDGE_001','AGENCY_APACHEGATE_001','AGENCY_SSO_001','AGENCY_STRICT_L3','AGENCY_CHAOS_001');
DELETE FROM ido.agency_meta
 WHERE agency_code IN ('AGENCY_STUB_001','AGENCY_BRIDGE_001','AGENCY_APACHEGATE_001','AGENCY_SSO_001','AGENCY_STRICT_L3','AGENCY_CHAOS_001');

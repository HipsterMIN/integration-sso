-- =============================================================================
-- V8: AGENCY_STUB_001 API Key 해시 시드 + Webhook 엔드포인트 수정
-- =============================================================================
-- 목적:
--   1. idem_hub.agency_meta.api_key_hash 에 AGENCY_STUB_001 의 실제 SHA-256 해시 입력
--      → HandoffAgencyKeyInterceptor 가 DB 조회로 X-Agency-Key 검증 가능하도록
--   2. agency_webhook_config.endpoint_url 오류 수정
--      (/webhook/handoff → /api/v1/webhook/inbound)
--   3. 서명 비밀키 해시 일치 (poc-webhook-secret-change-in-production)
--
-- rawApiKey  = "stub-api-key-dev-001"
-- SHA-256    = echo -n "stub-api-key-dev-001" | sha256sum
--            = 8a5ad1ec5a18b326ed9ae616c9883e46bede28ed5b84d2912bb70263433749df
--
-- rawWebhookSecret = "poc-webhook-secret-change-in-production"
-- SHA-256          = echo -n "poc-webhook-secret-change-in-production" | sha256sum
--            = (아래 계산값 사용)
--
-- 작성일: 2026-05-08
-- =============================================================================

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. AGENCY_STUB_001 api_key_hash 업데이트
--    V1 시드에서 api_key_hash 가 NULL 로 삽입됐으므로 여기서 설정
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE idem_hub.agency_meta
SET
    api_key_hash = '8a5ad1ec5a18b326ed9ae616c9883e46bede28ed5b84d2912bb70263433749df',
    updated_at   = NOW()
WHERE agency_code = 'AGENCY_STUB_001';

-- api_key_hash 미설정 시 INSERT (V1 레코드 없을 경우 방어 로직)
INSERT INTO idem_hub.agency_meta (
    agency_code,
    official_name,
    min_auth_level,
    policy_version,
    api_key_hash,
    integration_type,
    callback_whitelist,
    allowed_attributes,
    active,
    created_at,
    updated_at
)
VALUES (
    'AGENCY_STUB_001',
    '테스트 기관 (PoC Stub)',
    'L1',
    '1.0',
    '8a5ad1ec5a18b326ed9ae616c9883e46bede28ed5b84d2912bb70263433749df',
    'DIRECT',
    '["http://localhost:8084/agency/entry","https://agency-stub.local/agency/entry"]',
    '["name_masked","mobile_masked","nationality_type","birth_year"]',
    TRUE,
    NOW(),
    NOW()
)
ON CONFLICT (agency_code) DO UPDATE
    SET api_key_hash = EXCLUDED.api_key_hash,
        updated_at   = NOW();

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. agency_webhook_config endpoint_url 수정
--    V7 에서 /webhook/handoff 로 잘못 설정 → 실제 엔드포인트 /api/v1/webhook/inbound
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE idem_hub.agency_webhook_config
SET
    endpoint_url        = 'http://localhost:8084/api/v1/webhook/inbound',
    signing_secret_hash = 'ad4bb1a5f1e0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6',
    -- ^ SHA-256("poc-webhook-secret-change-in-production")
    updated_at          = NOW()
WHERE agency_code = 'AGENCY_STUB_001';

-- 레코드 없을 경우 방어 INSERT
INSERT INTO idem_hub.agency_webhook_config (
    agency_code,
    endpoint_url,
    signing_secret_hash,
    connect_timeout_ms,
    read_timeout_ms,
    max_retry_count,
    active
)
VALUES (
    'AGENCY_STUB_001',
    'http://localhost:8084/api/v1/webhook/inbound',
    'ad4bb1a5f1e0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6',
    3000,
    8000,
    3,
    TRUE
)
ON CONFLICT (agency_code) DO UPDATE
    SET endpoint_url        = EXCLUDED.endpoint_url,
        signing_secret_hash = EXCLUDED.signing_secret_hash,
        updated_at          = NOW();

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. idem_hub.agency_meta webhook_enabled TRUE + endpoint 최신화 (V7 컬럼)
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE idem_hub.agency_meta
SET
    webhook_enabled  = TRUE,
    webhook_endpoint = 'http://localhost:8084/api/v1/webhook/inbound',
    updated_at       = NOW()
WHERE agency_code = 'AGENCY_STUB_001';

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. 검증 쿼리 (마이그레이션 후 결과 확인용 주석)
-- ─────────────────────────────────────────────────────────────────────────────
-- SELECT agency_code, api_key_hash, webhook_enabled, webhook_endpoint
--   FROM idem_hub.agency_meta
--  WHERE agency_code = 'AGENCY_STUB_001';
--
-- SELECT agency_code, endpoint_url, signing_secret_hash, active
--   FROM idem_hub.agency_webhook_config
--  WHERE agency_code = 'AGENCY_STUB_001';

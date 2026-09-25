-- =============================================================================
-- V13: 유관기관 연동 패턴별 시드 데이터 + 최악 시나리오 기관 등록
-- =============================================================================
-- 목적:
--   설계서 §8절의 4종 HandoffStrategy 패턴별 기관 시드 등록.
--   각 패턴의 정상 + 최악(장애/레거시/악의적) 시나리오 시뮬레이션용.
--
-- 등록 기관 목록:
--   AGENCY_BRIDGE_001     — BRIDGE 패턴 (폐쇄망 기관, Bridge 서버 경유)
--   AGENCY_APACHEGATE_001 — APACHE_GATE 패턴 (레거시 Apache/mod_auth 기관)
--   AGENCY_SSO_001        — INTERNAL_SSO 패턴 (기관 내부 SSO 연계)
--   AGENCY_STRICT_L3      — DIRECT + L3 고보안 (금융·국방 수준 기관)
--   AGENCY_CHAOS_001      — CHAOS 시나리오 (장애·최악 상황 시뮬레이터)
--
-- API Key (rawKey → SHA-256 HEX):
--   AGENCY_BRIDGE_001     : bridge-api-key-dev-001
--                           → 976c6196929d622e1d286aceb95cad012e6e294f3a366eed79e8ec7f18f44d06
--   AGENCY_APACHEGATE_001 : apachegate-api-key-dev-001
--                           → baef5025c784223b61cda7a2f5c9572b596d98a24feccfc9fad282bfc69742e4
--   AGENCY_SSO_001        : internalsso-api-key-dev-001
--                           → b927a4e05ef5090b5478c57e1d044beeddd72ef159cddcc75bd2f99e508faa25
--   AGENCY_STRICT_L3      : strict-api-key-dev-001
--                           → 5d746163bf3e3f5771233de1dc3b49920423f3771523ebcac7aa043724186fa8
--   AGENCY_CHAOS_001      : chaos-api-key-dev-001
--                           → 5174a9c9dc6f6ede2b12d0bb0cbb87267a13328347868e9723f84a547b8cfe64
--
-- Webhook Signing Secret (공통 PoC용):
--   poc-webhook-secret-change-in-production
--   → ad4bb1a5f1e0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6
--
-- 작성일: 2026-05-10
-- Sprint: 6 S6-T2 (유관기관 패턴 Stub 완성)
-- =============================================================================


-- ─────────────────────────────────────────────────────────────────────────────
-- 1. BRIDGE 패턴 기관 — 폐쇄망 기관 (외부 직접 호출 불가)
--    설계서 §8.3: IdO → Bridge 서버 → 기관 내부 조회
--    실제 사례: 금융망/국방망 등 망분리 기관
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO idem_hub.agency_meta (
    agency_code,
    official_name,
    min_auth_level,
    policy_version,
    api_key_hash,
    integration_type,
    bridge_endpoint,
    callback_whitelist,
    allowed_attributes,
    webhook_enabled,
    webhook_endpoint,
    active,
    created_at,
    updated_at
)
VALUES (
    'AGENCY_BRIDGE_001',
    '폐쇄망 연계 기관 (Bridge 패턴 PoC)',
    'L2',
    '1.0',
    '976c6196929d622e1d286aceb95cad012e6e294f3a366eed79e8ec7f18f44d06',
    'BRIDGE',
    'http://localhost:8084/mock/bridge',   -- agency-stub 내 Bridge Mock 엔드포인트
    '["http://localhost:8084/agency/entry","http://localhost:8085/agency/entry"]',
    '["name_masked","mobile_masked","nationality_type"]',
    TRUE,
    'http://localhost:8084/api/v1/webhook/inbound',
    TRUE,
    NOW(),
    NOW()
)
ON CONFLICT (agency_code) DO UPDATE
    SET official_name     = EXCLUDED.official_name,
        api_key_hash      = EXCLUDED.api_key_hash,
        integration_type  = EXCLUDED.integration_type,
        bridge_endpoint   = EXCLUDED.bridge_endpoint,
        callback_whitelist= EXCLUDED.callback_whitelist,
        webhook_enabled   = EXCLUDED.webhook_enabled,
        webhook_endpoint  = EXCLUDED.webhook_endpoint,
        updated_at        = NOW();


-- ─────────────────────────────────────────────────────────────────────────────
-- 2. APACHE_GATE 패턴 기관 — 레거시 Apache/mod_auth_openidc 기관
--    설계서 §8.5: IdO → Apache 게이트웨이 세션 헤더 사전 Push
--    실제 사례: eGovFrame + Apache httpd 앞단 기관, 행안부 표준망
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO idem_hub.agency_meta (
    agency_code,
    official_name,
    min_auth_level,
    policy_version,
    api_key_hash,
    integration_type,
    bridge_endpoint,       -- APACHE_GATE는 bridge_endpoint 컬럼 재사용 (설계서 §AgencyMeta 주석 참조)
    callback_whitelist,
    allowed_attributes,
    webhook_enabled,
    webhook_endpoint,
    active,
    created_at,
    updated_at
)
VALUES (
    'AGENCY_APACHEGATE_001',
    '레거시 Apache 연계 기관 (APACHE_GATE 패턴 PoC)',
    'L1',
    '1.0',
    'baef5025c784223b61cda7a2f5c9572b596d98a24feccfc9fad282bfc69742e4',
    'APACHE_GATE',
    'http://localhost:8084/mock/apache-gate',  -- agency-stub 내 Apache Mock 엔드포인트
    '["http://localhost:8084/agency/entry","http://localhost:8086/agency/entry"]',
    '["name_masked","birth_year","nationality_type"]',
    TRUE,
    'http://localhost:8084/api/v1/webhook/inbound',
    TRUE,
    NOW(),
    NOW()
)
ON CONFLICT (agency_code) DO UPDATE
    SET official_name     = EXCLUDED.official_name,
        api_key_hash      = EXCLUDED.api_key_hash,
        integration_type  = EXCLUDED.integration_type,
        bridge_endpoint   = EXCLUDED.bridge_endpoint,
        callback_whitelist= EXCLUDED.callback_whitelist,
        webhook_enabled   = EXCLUDED.webhook_enabled,
        webhook_endpoint  = EXCLUDED.webhook_endpoint,
        updated_at        = NOW();


-- ─────────────────────────────────────────────────────────────────────────────
-- 3. INTERNAL_SSO 패턴 기관 — 기관 내부 SSO 연계
--    설계서 §8.4: IdO → 기관 SSO 서버 세션 사전 등록
--    실제 사례: 행안부 국가SSO, 기관 자체 LDAP/SSO 시스템
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO idem_hub.agency_meta (
    agency_code,
    official_name,
    min_auth_level,
    policy_version,
    api_key_hash,
    integration_type,
    sso_domain,
    callback_whitelist,
    allowed_attributes,
    webhook_enabled,
    webhook_endpoint,
    active,
    created_at,
    updated_at
)
VALUES (
    'AGENCY_SSO_001',
    '내부 SSO 연계 기관 (INTERNAL_SSO 패턴 PoC)',
    'L1',
    '1.0',
    'b927a4e05ef5090b5478c57e1d044beeddd72ef159cddcc75bd2f99e508faa25',
    'INTERNAL_SSO',
    'http://localhost:8084',   -- agency-stub이 /internal/sso-session 수신 처리
    '["http://localhost:8084/agency/entry","http://localhost:8087/agency/entry"]',
    '["name_masked","mobile_masked","birth_year","nationality_type"]',
    TRUE,
    'http://localhost:8084/api/v1/webhook/inbound',
    TRUE,
    NOW(),
    NOW()
)
ON CONFLICT (agency_code) DO UPDATE
    SET official_name     = EXCLUDED.official_name,
        api_key_hash      = EXCLUDED.api_key_hash,
        integration_type  = EXCLUDED.integration_type,
        sso_domain        = EXCLUDED.sso_domain,
        callback_whitelist= EXCLUDED.callback_whitelist,
        webhook_enabled   = EXCLUDED.webhook_enabled,
        webhook_endpoint  = EXCLUDED.webhook_endpoint,
        updated_at        = NOW();


-- ─────────────────────────────────────────────────────────────────────────────
-- 4. DIRECT + L3 고보안 기관 — 금융·보안 수준 최소 인증 요구
--    설계서 §PolicyEngine: min_auth_level=L3 → L1/L2 인증 결과는 거부
--    실제 사례: 금융위, 국방부, 국정원 연계 기관
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO idem_hub.agency_meta (
    agency_code,
    official_name,
    min_auth_level,
    policy_version,
    api_key_hash,
    integration_type,
    callback_whitelist,
    allowed_attributes,
    webhook_enabled,
    webhook_endpoint,
    active,
    created_at,
    updated_at
)
VALUES (
    'AGENCY_STRICT_L3',
    'L3 고보안 요구 기관 (DIRECT 패턴, 인증수준 L3 강제)',
    'L3',
    '1.0',
    '5d746163bf3e3f5771233de1dc3b49920423f3771523ebcac7aa043724186fa8',
    'DIRECT',
    '["http://localhost:8084/agency/entry"]',
    '["name_masked","nationality_type"]',  -- 최소 속성만 허용
    TRUE,
    'http://localhost:8084/api/v1/webhook/inbound',
    TRUE,
    NOW(),
    NOW()
)
ON CONFLICT (agency_code) DO UPDATE
    SET official_name     = EXCLUDED.official_name,
        api_key_hash      = EXCLUDED.api_key_hash,
        min_auth_level    = EXCLUDED.min_auth_level,
        integration_type  = EXCLUDED.integration_type,
        callback_whitelist= EXCLUDED.callback_whitelist,
        allowed_attributes= EXCLUDED.allowed_attributes,
        webhook_enabled   = EXCLUDED.webhook_enabled,
        webhook_endpoint  = EXCLUDED.webhook_endpoint,
        updated_at        = NOW();


-- ─────────────────────────────────────────────────────────────────────────────
-- 5. CHAOS 시나리오 기관 — 최악 시나리오 전용
--    장애·비정상·악의적 동작 시뮬레이션에 사용.
--    DIRECT 패턴이지만 scenarioMode=CHAOS/TIMEOUT/REPLAY 등으로 동작.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO idem_hub.agency_meta (
    agency_code,
    official_name,
    min_auth_level,
    policy_version,
    api_key_hash,
    integration_type,
    callback_whitelist,
    allowed_attributes,
    webhook_enabled,
    webhook_endpoint,
    active,
    created_at,
    updated_at
)
VALUES (
    'AGENCY_CHAOS_001',
    '최악 시나리오 시뮬레이터 (Chaos Engineering PoC)',
    'L1',
    '1.0',
    '5174a9c9dc6f6ede2b12d0bb0cbb87267a13328347868e9723f84a547b8cfe64',
    'DIRECT',
    '["http://localhost:8084/agency/entry","http://localhost:8084/mock/chaos"]',
    '["name_masked","mobile_masked","nationality_type","birth_year"]',
    TRUE,
    'http://localhost:8084/api/v1/webhook/inbound',
    TRUE,
    NOW(),
    NOW()
)
ON CONFLICT (agency_code) DO UPDATE
    SET official_name     = EXCLUDED.official_name,
        api_key_hash      = EXCLUDED.api_key_hash,
        integration_type  = EXCLUDED.integration_type,
        callback_whitelist= EXCLUDED.callback_whitelist,
        webhook_enabled   = EXCLUDED.webhook_enabled,
        webhook_endpoint  = EXCLUDED.webhook_endpoint,
        updated_at        = NOW();


-- ─────────────────────────────────────────────────────────────────────────────
-- 6. 패턴 기관별 Webhook 설정 등록
--    agency_webhook_config 테이블 (V7 생성)
-- ─────────────────────────────────────────────────────────────────────────────

-- BRIDGE 기관
INSERT INTO idem_hub.agency_webhook_config (
    agency_code, endpoint_url, signing_secret_hash,
    connect_timeout_ms, read_timeout_ms, max_retry_count, active
)
VALUES (
    'AGENCY_BRIDGE_001',
    'http://localhost:8084/api/v1/webhook/inbound',
    'ad4bb1a5f1e0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6',
    3000, 8000, 3, TRUE
)
ON CONFLICT (agency_code) DO UPDATE
    SET endpoint_url        = EXCLUDED.endpoint_url,
        signing_secret_hash = EXCLUDED.signing_secret_hash,
        updated_at          = NOW();

-- APACHE_GATE 기관
INSERT INTO idem_hub.agency_webhook_config (
    agency_code, endpoint_url, signing_secret_hash,
    connect_timeout_ms, read_timeout_ms, max_retry_count, active
)
VALUES (
    'AGENCY_APACHEGATE_001',
    'http://localhost:8084/api/v1/webhook/inbound',
    'ad4bb1a5f1e0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6',
    3000, 8000, 3, TRUE
)
ON CONFLICT (agency_code) DO UPDATE
    SET endpoint_url        = EXCLUDED.endpoint_url,
        signing_secret_hash = EXCLUDED.signing_secret_hash,
        updated_at          = NOW();

-- INTERNAL_SSO 기관
INSERT INTO idem_hub.agency_webhook_config (
    agency_code, endpoint_url, signing_secret_hash,
    connect_timeout_ms, read_timeout_ms, max_retry_count, active
)
VALUES (
    'AGENCY_SSO_001',
    'http://localhost:8084/api/v1/webhook/inbound',
    'ad4bb1a5f1e0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6',
    3000, 8000, 3, TRUE
)
ON CONFLICT (agency_code) DO UPDATE
    SET endpoint_url        = EXCLUDED.endpoint_url,
        signing_secret_hash = EXCLUDED.signing_secret_hash,
        updated_at          = NOW();

-- L3 고보안 기관
INSERT INTO idem_hub.agency_webhook_config (
    agency_code, endpoint_url, signing_secret_hash,
    connect_timeout_ms, read_timeout_ms, max_retry_count, active
)
VALUES (
    'AGENCY_STRICT_L3',
    'http://localhost:8084/api/v1/webhook/inbound',
    'ad4bb1a5f1e0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6',
    2000, 5000, 5, TRUE   -- 고보안: 짧은 타임아웃, 더 많은 재시도
)
ON CONFLICT (agency_code) DO UPDATE
    SET endpoint_url        = EXCLUDED.endpoint_url,
        signing_secret_hash = EXCLUDED.signing_secret_hash,
        connect_timeout_ms  = EXCLUDED.connect_timeout_ms,
        read_timeout_ms     = EXCLUDED.read_timeout_ms,
        max_retry_count     = EXCLUDED.max_retry_count,
        updated_at          = NOW();

-- CHAOS 기관
INSERT INTO idem_hub.agency_webhook_config (
    agency_code, endpoint_url, signing_secret_hash,
    connect_timeout_ms, read_timeout_ms, max_retry_count, active
)
VALUES (
    'AGENCY_CHAOS_001',
    'http://localhost:8084/api/v1/webhook/inbound',
    'ad4bb1a5f1e0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6',
    3000, 8000, 3, TRUE
)
ON CONFLICT (agency_code) DO UPDATE
    SET endpoint_url        = EXCLUDED.endpoint_url,
        signing_secret_hash = EXCLUDED.signing_secret_hash,
        updated_at          = NOW();


-- ─────────────────────────────────────────────────────────────────────────────
-- 7. Rate Limit 설정 등록 (V9 생성 테이블)
--    기관별 TPS·일일 쿼터 차별화
-- ─────────────────────────────────────────────────────────────────────────────
-- agency_rate_limit_config 실제 컬럼명: enabled (active 아님 — V9 테이블 정의 기준)
INSERT INTO idem_hub.agency_rate_limit_config (agency_code, tps_limit, daily_limit, enabled)
VALUES
    ('AGENCY_BRIDGE_001',     50,  100000, TRUE),
    ('AGENCY_APACHEGATE_001', 30,   50000, TRUE),   -- 레거시: 낮은 TPS
    ('AGENCY_SSO_001',       100,  200000, TRUE),
    ('AGENCY_STRICT_L3',      20,   20000, TRUE),   -- 고보안: 엄격한 제한
    ('AGENCY_CHAOS_001',     200, 1000000, TRUE)    -- Chaos: 제한 없음 수준
ON CONFLICT (agency_code) DO UPDATE
    SET tps_limit   = EXCLUDED.tps_limit,
        daily_limit = EXCLUDED.daily_limit,
        enabled     = EXCLUDED.enabled,
        updated_at  = NOW();


-- ─────────────────────────────────────────────────────────────────────────────
-- 8. 검증 쿼리 (마이그레이션 후 수동 확인용 주석)
-- ─────────────────────────────────────────────────────────────────────────────
-- SELECT agency_code, official_name, integration_type, min_auth_level, active
--   FROM idem_hub.agency_meta
--  ORDER BY agency_code;
--
-- AGENCY_BRIDGE_001     | 폐쇄망 연계 기관    | BRIDGE       | L2 | TRUE
-- AGENCY_APACHEGATE_001 | 레거시 Apache 연계  | APACHE_GATE  | L1 | TRUE
-- AGENCY_CHAOS_001      | 최악 시나리오       | DIRECT       | L1 | TRUE
-- AGENCY_SSO_001        | 내부 SSO 연계 기관  | INTERNAL_SSO | L1 | TRUE
-- AGENCY_STRICT_L3      | L3 고보안 요구 기관 | DIRECT       | L3 | TRUE
-- AGENCY_STUB_001       | 테스트 기관 (PoC)   | DIRECT       | L1 | TRUE  ← 기존

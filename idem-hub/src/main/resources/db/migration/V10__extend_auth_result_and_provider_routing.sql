-- =============================================================================
-- IdO V10: auth_result 컬럼 확장 + provider_code 단위 회로차단기 설정 테이블
-- =============================================================================
-- 목적:
--   1. auth_result  — auth_method / issued_at / expires_at / raw_id_token 추가
--      (설계서 §24.4.1 auth_method 저장 규칙, §7 OIDC token claim 보존)
--   2. provider_circuit_config — provider_code 단위 Resilience4j 동적 설정
--      (P1: 회로차단기 임계값을 DB에서 관리하여 재배포 없이 조정 가능)
--
-- 기존 호환성:
--   - 모든 신규 컬럼은 nullable 또는 DEFAULT 지정 — 기존 데이터 영향 없음
--   - ADD COLUMN IF NOT EXISTS 사용 — 재실행 안전
--
-- 작성일: 2026-05-09
-- =============================================================================

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. idem_hub.auth_result — 신규 컬럼 4개 추가
-- ─────────────────────────────────────────────────────────────────────────────

-- 1-1. auth_method: 인증 방법 분류 코드 (설계서 §24.4.1)
--       STANDARD_OIDC_KAKAO_OIDC / NON_STANDARD_PASS / SEMI_STANDARD_OIDC_... 등
--       platform-common AuthResult.resolveAuthMethod() 로직과 1:1 매핑
ALTER TABLE idem_hub.auth_result
    ADD COLUMN IF NOT EXISTS auth_method VARCHAR(80);

COMMENT ON COLUMN idem_hub.auth_result.auth_method
    IS '인증 방법 분류 코드 — AuthResult.resolveAuthMethod(providerCode) 결과값 저장';

-- 1-2. issued_at: id_token iat claim (토큰 발급 시각)
--       authenticated_at 과 다를 수 있음 (IdP 서버 시각 vs IdO 처리 시각)
ALTER TABLE idem_hub.auth_result
    ADD COLUMN IF NOT EXISTS issued_at TIMESTAMPTZ;

COMMENT ON COLUMN idem_hub.auth_result.issued_at
    IS 'id_token iat claim — IdP가 토큰을 발급한 시각 (authenticated_at 과 별도)';

-- 1-3. expires_at: id_token exp claim (토큰 만료 시각)
--       Handoff Ticket 유효성 검증 시 참고 (토큰 만료 전에만 Handoff 허용)
ALTER TABLE idem_hub.auth_result
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;

COMMENT ON COLUMN idem_hub.auth_result.expires_at
    IS 'id_token exp claim — IdP 토큰 만료 시각 (HandoffTicket TTL 검증 참고)';

-- 1-4. raw_id_token: id_token 원문 (감사·디버깅 목적)
--       운영 환경에서는 AES-256-GCM 암호화 후 저장 권장
--       비OIDC(PASS/GPKI 등) 에서는 NULL 허용
ALTER TABLE idem_hub.auth_result
    ADD COLUMN IF NOT EXISTS raw_id_token TEXT;

COMMENT ON COLUMN idem_hub.auth_result.raw_id_token
    IS 'id_token 원문 (감사 목적, 운영 시 암호화 저장 권장; 비OIDC 는 NULL)';

-- 기존 auth_result 인덱스 보강: auth_method 조회 (감사 리포트용)
CREATE INDEX IF NOT EXISTS idx_auth_result_auth_method
    ON idem_hub.auth_result (auth_method)
    WHERE auth_method IS NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. idem_hub.provider_circuit_config — provider_code 단위 회로차단기 동적 설정
--    Resilience4j 기본값을 DB에서 오버라이드하여 재배포 없이 임계값 조정
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS idem_hub.provider_circuit_config (
    provider_code                       VARCHAR(50)     NOT NULL,
    -- 슬라이딩 윈도우 설정
    sliding_window_size                 SMALLINT        NOT NULL DEFAULT 10,
    failure_rate_threshold              SMALLINT        NOT NULL DEFAULT 60
        CONSTRAINT chk_pcc_failure_rate CHECK (failure_rate_threshold BETWEEN 1 AND 100),
    slow_call_rate_threshold            SMALLINT        NOT NULL DEFAULT 80
        CONSTRAINT chk_pcc_slow_rate    CHECK (slow_call_rate_threshold BETWEEN 1 AND 100),
    slow_call_duration_threshold_ms     INT             NOT NULL DEFAULT 3000,
    -- OPEN 상태 유지 시간 (ms)
    wait_duration_in_open_ms            INT             NOT NULL DEFAULT 30000,
    -- HALF_OPEN 허용 요청 수
    permitted_calls_in_half_open        SMALLINT        NOT NULL DEFAULT 5,
    -- 최소 요청 수 (통계 활성화 기준)
    minimum_number_of_calls             SMALLINT        NOT NULL DEFAULT 5,
    -- 활성 여부 (FALSE 면 회로차단기 비활성화 — provider 기본 설정 사용)
    enabled                             BOOLEAN         NOT NULL DEFAULT TRUE,
    -- 비고
    note                                VARCHAR(200),
    created_at                          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_provider_circuit_config PRIMARY KEY (provider_code),
    CONSTRAINT fk_pcc_provider_code
        FOREIGN KEY (provider_code) REFERENCES idem_hub.provider_config (provider_code)
        ON UPDATE CASCADE ON DELETE CASCADE
);

COMMENT ON TABLE idem_hub.provider_circuit_config
    IS 'provider_code 단위 Resilience4j 회로차단기 동적 설정 (재배포 없이 임계값 조정)';

-- 기본 provider 회로차단기 설정 삽입 (provider_config 데이터 연동)
-- NOTE: provider_config 에 실제 데이터가 없으면 이 INSERT는 0건 실행 (정상)
INSERT INTO idem_hub.provider_circuit_config
    (provider_code, sliding_window_size, failure_rate_threshold, wait_duration_in_open_ms, note)
SELECT
    provider_code,
    10,   -- sliding_window_size
    60,   -- failure_rate_threshold 60%
    30000, -- wait_duration_in_open_ms 30s
    'auto-generated from provider_config'
FROM idem_hub.provider_config
ON CONFLICT (provider_code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. broker_audit_log 인덱스 보강 (P1: 코드 기록 경로 마감 대비)
--    V6 에서 테이블만 생성됨 — 조회 패턴 기반 인덱스 추가
-- ─────────────────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_broker_audit_correlation
    ON idem_hub.broker_audit_log (correlation_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_broker_audit_provider_action
    ON idem_hub.broker_audit_log (provider_code, action, created_at DESC);

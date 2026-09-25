-- =============================================================================
-- IdO V12: MFA/AAL 스키마 확장 포인트
-- =============================================================================
-- 목적:
--   - auth_result에 AAL(Authenticator Assurance Level) 및 MFA 관련 컬럼 추가
--   - 향후 FIDO2/WebAuthn, TOTP(OTP), SMS OTP 도입 시 스키마 변경 없이 수용
--   - NIST SP 800-63B AAL 레벨 체계 준수 (AAL1/AAL2/AAL3)
--
-- AAL 정의 (NIST SP 800-63B):
--   AAL1: Single-factor — 패스워드 / Q-IM(CI 기반) 단순 조회
--   AAL2: Two-factor   — 패스워드 + TOTP/FIDO2 (또는 CI 기반 강도 인증)
--   AAL3: Hardware MFA — 하드웨어 보안키 (FIDO2 roaming authenticator)
--
-- 플랫폼 매핑:
--   PASS 비대면 인증(CI 확인)  → AAL2
--   GPKI 인증서 로그인         → AAL2
--   Keycloak 단순 OIDC        → AAL1
--   향후 FIDO2 하드웨어키     → AAL3
--
-- 기존 호환성:
--   - 모든 신규 컬럼: nullable, DEFAULT NULL
--   - ADD COLUMN IF NOT EXISTS 사용 — 재실행 안전
--   - 기존 레코드: aal_level = NULL (마이그레이션 후 배치 채움 필요)
--
-- 작성일: 2026-05-09
-- =============================================================================

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. idem_hub.auth_result — AAL / MFA 확장 컬럼
-- ─────────────────────────────────────────────────────────────────────────────

-- 1-1. aal_level: NIST SP 800-63B Authenticator Assurance Level
--       'AAL1' | 'AAL2' | 'AAL3'
--       NULL = 레거시 레코드 (마이그레이션 전 데이터)
ALTER TABLE idem_hub.auth_result
    ADD COLUMN IF NOT EXISTS aal_level VARCHAR(10);

COMMENT ON COLUMN idem_hub.auth_result.aal_level
    IS 'NIST SP 800-63B AAL 레벨: AAL1(단요소) | AAL2(이중요소) | AAL3(하드웨어). NULL=레거시';

-- 1-2. mfa_type: 2차 인증 수단 종류
--       'TOTP' | 'FIDO2_PLATFORM' | 'FIDO2_ROAMING' | 'SMS_OTP' | 'EMAIL_OTP' | NULL
--       AAL1에서는 NULL (MFA 미사용)
ALTER TABLE idem_hub.auth_result
    ADD COLUMN IF NOT EXISTS mfa_type VARCHAR(30);

COMMENT ON COLUMN idem_hub.auth_result.mfa_type
    IS '2차 인증 수단: TOTP | FIDO2_PLATFORM | FIDO2_ROAMING | SMS_OTP | EMAIL_OTP. AAL1 시 NULL';

-- 1-3. mfa_verified_at: 2차 인증 완료 시각
--       MFA 성공 시 서버 기준 타임스탬프 저장
--       1차 인증(authenticated_at)과 시간 차이를 감사 추적에 활용
ALTER TABLE idem_hub.auth_result
    ADD COLUMN IF NOT EXISTS mfa_verified_at TIMESTAMPTZ;

COMMENT ON COLUMN idem_hub.auth_result.mfa_verified_at
    IS 'MFA(2차 인증) 완료 시각. authenticated_at(1차)과 별도 기록. MFA 미사용 시 NULL';

-- 1-4. mfa_device_id: MFA 디바이스 식별자 (해시 처리)
--       FIDO2 authenticator AAGUID 또는 TOTP 디바이스 ID의 SHA-256 해시
--       PII 비보관 원칙: 원문 디바이스 ID는 저장하지 않음
ALTER TABLE idem_hub.auth_result
    ADD COLUMN IF NOT EXISTS mfa_device_id VARCHAR(64);

COMMENT ON COLUMN idem_hub.auth_result.mfa_device_id
    IS 'MFA 디바이스 식별자 (SHA-256 해시). FIDO2 AAGUID 또는 TOTP device_id의 해시값. PII 미보관';

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. idem_hub.mfa_enrollment — MFA 디바이스 등록 테이블 (FIDO2/TOTP 등록 정보)
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS idem_hub.mfa_enrollment (
    enrollment_id       VARCHAR(36)  NOT NULL PRIMARY KEY,  -- UUID
    qim_user_id         VARCHAR(128) NOT NULL,              -- Q-IM 사용자 식별자
    mfa_type            VARCHAR(30)  NOT NULL,              -- 등록 MFA 종류
    device_id_hash      VARCHAR(64),                        -- 디바이스 ID 해시 (PII 보호)
    -- FIDO2 전용 필드
    credential_id_hash  VARCHAR(128),                       -- FIDO2 credential_id 해시
    aaguid              VARCHAR(36),                        -- FIDO2 AAGUID (제조사 식별)
    -- 등록 상태
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | REVOKED | EXPIRED
    enrolled_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_used_at        TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    revoke_reason       VARCHAR(200),
    -- 메타
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE idem_hub.mfa_enrollment
    IS 'MFA 디바이스 등록 정보. FIDO2 Credential / TOTP 디바이스 등록 이력 관리';
COMMENT ON COLUMN idem_hub.mfa_enrollment.enrollment_id
    IS 'MFA 등록 UUID — 등록 취소 시 참조 키';
COMMENT ON COLUMN idem_hub.mfa_enrollment.qim_user_id
    IS 'Q-IM 사용자 식별자 — auth_result.qim_user_id와 동일 도메인';
COMMENT ON COLUMN idem_hub.mfa_enrollment.mfa_type
    IS 'MFA 종류: TOTP | FIDO2_PLATFORM | FIDO2_ROAMING | SMS_OTP | EMAIL_OTP';
COMMENT ON COLUMN idem_hub.mfa_enrollment.status
    IS 'ACTIVE(활성) | REVOKED(취소) | EXPIRED(만료)';

-- 조회 성능 인덱스
CREATE INDEX IF NOT EXISTS idx_mfa_enrollment_qim_user_id
    ON idem_hub.mfa_enrollment (qim_user_id);
CREATE INDEX IF NOT EXISTS idx_mfa_enrollment_status
    ON idem_hub.mfa_enrollment (status);

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. idem_hub.aal_policy — AAL 정책 테이블 (provider별 기본 AAL 매핑)
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS idem_hub.aal_policy (
    provider_code       VARCHAR(80)  NOT NULL PRIMARY KEY,  -- PASS, GPKI, KEYCLOAK 등
    default_aal         VARCHAR(10)  NOT NULL DEFAULT 'AAL1',  -- 해당 provider 기본 AAL
    mfa_required        BOOLEAN      NOT NULL DEFAULT FALSE, -- MFA 필수 여부
    description         VARCHAR(300),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE idem_hub.aal_policy
    IS '인증 제공자별 AAL 정책. provider_code → 기본 AAL 레벨 / MFA 필수 여부 매핑';
COMMENT ON COLUMN idem_hub.aal_policy.default_aal
    IS '제공자 기본 AAL: AAL1(단요소) | AAL2(이중요소) | AAL3(하드웨어)';
COMMENT ON COLUMN idem_hub.aal_policy.mfa_required
    IS 'TRUE이면 해당 provider 인증 시 MFA 추가 검증 필수';

-- 초기 데이터: 현재 플랫폼 인증 수단별 AAL 매핑
INSERT INTO idem_hub.aal_policy (provider_code, default_aal, mfa_required, description)
VALUES
    ('PASS',        'AAL2', FALSE, 'PASS 비대면 인증 — 통신사 CI 확인 기반 (NIST AAL2 수준)'),
    ('GPKI',        'AAL2', FALSE, 'GPKI 공동인증서 — PKI 기반 강도 인증'),
    ('KEYCLOAK',    'AAL1', FALSE, 'Keycloak 단순 OIDC — 패스워드 단요소'),
    ('KAKAO_OIDC',  'AAL1', FALSE, '카카오 소셜 로그인 — OIDC 단요소'),
    ('NAVER_OIDC',  'AAL1', FALSE, '네이버 소셜 로그인 — OIDC 단요소'),
    ('PASS_OIDC',   'AAL2', FALSE, 'PASS OIDC 경로 — 통신사 CI 확인'),
    ('GPKI_OIDC',   'AAL2', FALSE, 'GPKI OIDC 경로 — 공동인증서')
ON CONFLICT (provider_code) DO UPDATE
    SET default_aal    = EXCLUDED.default_aal,
        mfa_required   = EXCLUDED.mfa_required,
        description    = EXCLUDED.description,
        updated_at     = NOW();

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. 기존 레코드 AAL 채움 (배치 업데이트)
--    provider_code → aal_policy.default_aal 기반으로 기존 데이터 채움
-- ─────────────────────────────────────────────────────────────────────────────

UPDATE idem_hub.auth_result ar
SET aal_level = p.default_aal
FROM idem_hub.aal_policy p
WHERE ar.provider_code = p.provider_code
  AND ar.aal_level IS NULL;

-- =============================================================================
-- Q-IM V3: CI 암호화 키 버전 관리 + 상태 이력 + 회원조회 인덱스
-- =============================================================================
-- 목적:
--   1. CI 암호화 키 버전 관리 테이블 (crypto_key_version)
--      → CiCryptoServiceImpl이 키 버전 접두사(v1., v2.)로 복호화 키 선택
--   2. 사용자 상태 변경 이력 테이블 (user_status_history)
--      → UserRegistrationServiceImpl.insertStatusHistory() 대상 테이블
--   3. user_profile.ci 컬럼 크기 확장 (v1.{iv}.{ciphertext} 형식 수용)
--   4. auth_mean_mapping CI 기반 조회 성능 인덱스 추가
--
-- 작성일: 2026-05-08
-- =============================================================================

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. CI 암호화 키 버전 관리 테이블
--    CiCryptoServiceImpl에서 키 버전 접두사 기반 복호화 키 선택에 사용
--    실제 키 재료는 환경변수(QIM_CI_AES_KEY_V1 등)로 주입 — DB에는 메타만 저장
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS crypto_key_version (
    key_id          VARCHAR(36)  NOT NULL,
    key_type        VARCHAR(50)  NOT NULL COMMENT 'CI_AES / DI_HMAC 등',
    key_version     VARCHAR(10)  NOT NULL COMMENT 'v1, v2, ...',
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    grace_until     DATETIME(6)           COMMENT 'active=FALSE 후 복호화 허용 기간',
    rotation_reason VARCHAR(200)          COMMENT '로테이션 사유',
    created_by      VARCHAR(100) NOT NULL DEFAULT 'SYSTEM',
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_crypto_key_version PRIMARY KEY (key_id),
    CONSTRAINT uq_crypto_key_type_version UNIQUE (key_type, key_version)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='CI/DI 암호화 키 버전 메타데이터 (실제 키는 환경변수/KMS)';

CREATE INDEX idx_crypto_key_active ON crypto_key_version (key_type, active, created_at DESC);

-- 초기 v1 키 버전 등록 (실제 키는 QIM_CI_AES_KEY_V1 환경변수)
INSERT INTO crypto_key_version (key_id, key_type, key_version, active, rotation_reason, created_by)
VALUES
    (UUID(), 'CI_AES',  'v1', TRUE,  'INITIAL_SETUP', 'SYSTEM'),
    (UUID(), 'DI_HMAC', 'v1', TRUE,  'INITIAL_SETUP', 'SYSTEM')
ON DUPLICATE KEY UPDATE active = active;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. 사용자 상태 변경 이력 테이블
--    UserRegistrationServiceImpl.insertStatusHistory()의 대상 테이블
--    GDPR §17 Right to be Forgotten: 탈퇴(WITHDRAWN) 이력은 영구 보존
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_status_history (
    history_id      BIGINT       NOT NULL AUTO_INCREMENT,
    qim_user_id     VARCHAR(36)  NOT NULL,
    status_before   VARCHAR(20)           COMMENT 'ACTIVE / SUSPENDED / WITHDRAWN',
    status_after    VARCHAR(20)  NOT NULL,
    changed_by      VARCHAR(100) NOT NULL COMMENT 'SYSTEM / ADMIN_ID / USER',
    change_reason   VARCHAR(500),
    occurred_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_user_status_history PRIMARY KEY (history_id),
    CONSTRAINT fk_status_history_user FOREIGN KEY (qim_user_id)
        REFERENCES qim_user (qim_user_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='사용자 상태 변경 감사 이력 (GDPR 준수)';

CREATE INDEX idx_status_history_user ON user_status_history (qim_user_id, occurred_at DESC);
CREATE INDEX idx_status_history_after ON user_status_history (status_after, occurred_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. user_profile.ci 컬럼 크기 확장
--    기존: VARCHAR(88) → CI 평문 길이
--    변경: VARCHAR(512) → v1.{iv(16)}.{ciphertext+tag(~128)} Base64URL 형식 수용
--    버전 접두사 포함 최대 길이 ≈ 3 + 1 + 16 + 1 + ~150 = ~171자 (VARCHAR(512)로 여유 확보)
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE user_profile
    MODIFY COLUMN ci VARCHAR(512) COMMENT '암호화된 CI: v{n}.{base64url(iv)}.{base64url(ciphertext+tag)}';

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. auth_mean_mapping 조회 성능 인덱스 추가
--    MemberLookupController: identifierHash → 사용자 조회 (CI 복호화 → SHA-256 → 조회)
-- ─────────────────────────────────────────────────────────────────────────────
-- identifierHash 단독 인덱스 (이미 UNIQUE이면 중복 생성 방지)
CREATE INDEX IF NOT EXISTS idx_auth_mean_hash_status
    ON auth_mean_mapping (identifier_hash, status);

-- provider_code + status 복합 인덱스 (제공자별 활성 매핑 조회)
CREATE INDEX IF NOT EXISTS idx_auth_mean_provider_status
    ON auth_mean_mapping (provider_code, status);

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. user_profile 기관별 DI 조회 성능 (JSON 경로 인덱스 — MariaDB 10.4+)
--    JSON_VALUE(di_map, '$.AGENCY_CODE') 조회 가속 (제한적 — 필요 시 별도 테이블 고려)
-- ─────────────────────────────────────────────────────────────────────────────
-- Note: MariaDB에서 JSON 함수 인덱스는 Generated Column 방식 필요
-- di_map 조회는 현재 서비스 레이어(DiGenerationService)에서 In-Memory 처리 → 인덱스 불필요
-- 향후 조회량 증가 시 agency_di_mapping 정규화 테이블로 전환 권장

-- ─────────────────────────────────────────────────────────────────────────────
-- 검증 쿼리 (마이그레이션 후 확인용 주석)
-- ─────────────────────────────────────────────────────────────────────────────
-- SHOW CREATE TABLE crypto_key_version;
-- SHOW CREATE TABLE user_status_history;
-- DESCRIBE user_profile;
-- SHOW INDEXES FROM auth_mean_mapping;

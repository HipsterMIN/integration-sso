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
-- 2. 사용자 상태 변경 이력 테이블 — V1 에서 이미 생성된 user_status_history 재사용
--    UserRegistrationServiceImpl.insertStatusHistory() 대상 테이블.
--    GDPR §17 Right to be Forgotten: 탈퇴(WITHDRAWN) 이력은 영구 보존.
--
--    [γ-게이트 수정] V1 (history_id VARCHAR(36), status_before NOT NULL,
--    correlation_id VARCHAR(36) 포함) 정의를 정답(source of truth)으로 채택.
--    이전 V3 에서 동일 테이블을 BIGINT AUTO_INCREMENT 스키마로 재정의 + 동일
--    인덱스(idx_status_history_user) 를 중복 생성하여 Flyway 가 "Duplicate key
--    name" 으로 실패하던 문제를 제거함. MariaDB/MySQL 의 CREATE INDEX 는
--    IF NOT EXISTS 미지원 (10.5.x 이상은 지원하나 호환성 위해 사용하지 않음).
--    V3 의 진짜 신규 추가물은 idx_status_history_after 만 보존한다.
-- ─────────────────────────────────────────────────────────────────────────────
-- V1 가 이미 생성한 테이블이므로 CREATE TABLE 자체를 다시 호출하지 않는다.
-- (CREATE TABLE IF NOT EXISTS 라도 컬럼 시그니처가 다르면 운영 검증 단계에서
--  혼선이 발생하므로 V3 에서 별도 정의를 두지 않는다.)

-- status_after 기반 통계/검색 가속 인덱스 — V3 의 신규 추가물
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

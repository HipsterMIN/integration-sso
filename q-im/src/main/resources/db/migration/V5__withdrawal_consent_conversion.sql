-- =============================================================================
-- Q-IM V5: 회원 탈퇴 4종 + 개인정보 동의 스키마 + 전환 세션 상태 기계
-- P2 §12.1~§12.3 구현
-- 작성일: 2026-05-13
-- =============================================================================

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. qim_user 컬럼 추가 (탈퇴 4종 지원)
-- ─────────────────────────────────────────────────────────────────────────────

-- 1-1. 탈퇴 예약 일시 (SCHEDULED 탈퇴 전용)
ALTER TABLE qim_user
    ADD COLUMN withdrawal_scheduled_at TIMESTAMP(6) NULL
        COMMENT '예약 탈퇴 처리 예정 일시 (SCHEDULED 유형 전용)';

-- 1-2. 탈퇴 유형 (IMMEDIATE/SCHEDULED/AGENCY_REQUESTED/ADMIN_FORCED)
ALTER TABLE qim_user
    ADD COLUMN withdrawal_type VARCHAR(30) NULL
        COMMENT '탈퇴 유형: IMMEDIATE | SCHEDULED | AGENCY_REQUESTED | ADMIN_FORCED';

-- 1-3. WITHDRAWAL_SCHEDULED 상태 지원 — status CHECK 제약 확장 (MariaDB 10.4+)
-- 기존: ACTIVE, SUSPENDED, WITHDRAWN
-- 변경: WITHDRAWAL_SCHEDULED 추가
ALTER TABLE qim_user
    MODIFY COLUMN status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE'
        COMMENT '사용자 상태: ACTIVE | SUSPENDED | WITHDRAWAL_SCHEDULED | WITHDRAWN';

-- 1-4. 예약 탈퇴 조회 인덱스
CREATE INDEX idx_qim_user_withdrawal_scheduled
    ON qim_user (status, withdrawal_scheduled_at)
    COMMENT '예약 탈퇴 만료 스케줄러 조회 최적화';


-- ─────────────────────────────────────────────────────────────────────────────
-- 2. consent_version — 동의 버전 관리
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS consent_version (
    version_id      VARCHAR(36)  NOT NULL COMMENT 'UUIDv7 PK',
    consent_type    VARCHAR(50)  NOT NULL COMMENT 'TERMS_OF_SERVICE | PRIVACY_POLICY | THIRD_PARTY_SHARE | MARKETING',
    version_tag     VARCHAR(50)  NOT NULL COMMENT '버전 태그 (예: 2026-05-01, v3.2)',
    title           VARCHAR(200) NOT NULL COMMENT '약관 제목',
    content_url     VARCHAR(500) NULL     COMMENT '약관 전문 URL',
    required        TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '필수 동의 여부 (1=필수, 0=선택)',
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
                        COMMENT '버전 상태: DRAFT | ACTIVE | SUPERSEDED',
    effective_at    TIMESTAMP(6) NOT NULL COMMENT '적용 시작 일시',
    superseded_at   TIMESTAMP(6) NULL     COMMENT '적용 종료 일시 (SUPERSEDED 시)',
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_consent_version PRIMARY KEY (version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='개인정보 동의 버전 관리';

CREATE INDEX idx_consent_ver_type_status ON consent_version (consent_type, status);
CREATE INDEX idx_consent_ver_effective   ON consent_version (effective_at);


-- ─────────────────────────────────────────────────────────────────────────────
-- 3. consent_record — 사용자 동의 이력
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS consent_record (
    record_id        VARCHAR(36)  NOT NULL COMMENT 'UUIDv7 PK',
    qim_user_id      VARCHAR(36)  NOT NULL COMMENT '동의 사용자 (qim_user FK)',
    version_id       VARCHAR(36)  NOT NULL COMMENT '동의 버전 (consent_version FK)',
    consent_type     VARCHAR(50)  NOT NULL COMMENT '동의 유형 (비정규화 복사)',
    consent_status   VARCHAR(20)  NOT NULL DEFAULT 'AGREED'
                         COMMENT '동의 상태: AGREED | WITHDRAWN',
    agreed_via       VARCHAR(50)  NULL     COMMENT '동의 경로: WEB_SIGNUP | APP_SIGNUP | RE_CONSENT | AGENCY_API',
    client_ip        VARCHAR(45)  NULL     COMMENT '동의 IP (IPv4/IPv6)',
    agreed_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '동의 일시',
    withdrawn_at     TIMESTAMP(6) NULL     COMMENT '철회 일시',
    withdrawal_reason VARCHAR(200) NULL    COMMENT '철회 사유',
    created_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_consent_record PRIMARY KEY (record_id),
    CONSTRAINT fk_consent_record_user
        FOREIGN KEY (qim_user_id) REFERENCES qim_user (qim_user_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='사용자 개인정보 동의 이력 (이력 보존 — UPDATE 없이 INSERT 전용)';

CREATE INDEX idx_consent_record_user_type    ON consent_record (qim_user_id, consent_type);
CREATE INDEX idx_consent_record_user_version ON consent_record (qim_user_id, version_id);
CREATE INDEX idx_consent_record_agreed_at    ON consent_record (agreed_at);


-- ─────────────────────────────────────────────────────────────────────────────
-- 4. conversion_session — 통합계정 전환 세션 상태 기계
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS conversion_session (
    session_id                  VARCHAR(36)   NOT NULL COMMENT 'UUIDv7 PK',
    qim_user_id                 VARCHAR(36)   NOT NULL COMMENT '세션 소유 사용자 (qim_user FK)',
    status                      VARCHAR(30)   NOT NULL DEFAULT 'INITIATED'
                                    COMMENT '세션 상태: INITIATED | MEMBERS_FETCHED | ACCOUNT_SELECTED | LINKING | COMPLETED | CANCELLED | EXPIRED',
    candidate_members_json      JSON          NULL     COMMENT '유관 시스템 후보 회원 목록 JSON',
    selected_agency_codes_json  JSON          NULL     COMMENT '사용자 선택 기관 코드 목록 JSON',
    linked_agency_codes_json    JSON          NULL     COMMENT '연결 완료 기관 코드 목록 JSON',
    cancel_reason               VARCHAR(200)  NULL     COMMENT '취소/만료 사유',
    expires_at                  TIMESTAMP(6)  NOT NULL COMMENT '세션 만료 일시 (기본 +30분)',
    created_at                  TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                  TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                    ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_conversion_session PRIMARY KEY (session_id),
    CONSTRAINT fk_conv_session_user
        FOREIGN KEY (qim_user_id) REFERENCES qim_user (qim_user_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='통합계정 전환 세션 상태 기계 (TTL 30분)';

CREATE INDEX idx_conv_session_qim_user ON conversion_session (qim_user_id);
CREATE INDEX idx_conv_session_status   ON conversion_session (status);
CREATE INDEX idx_conv_session_expires  ON conversion_session (expires_at);


-- ─────────────────────────────────────────────────────────────────────────────
-- [검증 쿼리 — 마이그레이션 후 확인용]
-- ─────────────────────────────────────────────────────────────────────────────
-- SHOW COLUMNS FROM qim_user LIKE 'withdrawal%';
--   → withdrawal_scheduled_at, withdrawal_type, withdrawal_reason 컬럼 존재 확인
-- SHOW TABLES LIKE 'consent%';
--   → consent_version, consent_record 테이블 존재 확인
-- SHOW TABLES LIKE 'conversion%';
--   → conversion_session 테이블 존재 확인

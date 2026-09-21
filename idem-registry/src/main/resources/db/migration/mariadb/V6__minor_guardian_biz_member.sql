-- ============================================================
-- V6: 14세 미만 보호자 인증 + 기업회원 전환 지원
-- 설계서 P3-05 / P3-06 참조
-- ============================================================

-- ── 1. user_profile에 미성년자/보호자 컬럼 추가 ─────────────────
--   is_minor            : 14세 미만 여부 (등록 시 birth_year 기준 자동 판정)
--   guardian_qim_user_id: 보호자 qim_user_id (is_minor=TRUE인 경우)
--   guardian_consent_at : 보호자 동의 완료 시각

ALTER TABLE user_profile
    ADD COLUMN is_minor             TINYINT(1)   NOT NULL DEFAULT 0
        COMMENT '14세 미만 여부 — birth_year 기준 자동 판정 (P3-05)',
    ADD COLUMN guardian_qim_user_id VARCHAR(36)  NULL DEFAULT NULL
        COMMENT '보호자 qim_user_id (is_minor=TRUE인 경우)',
    ADD COLUMN guardian_consent_at  DATETIME(6)  NULL DEFAULT NULL
        COMMENT '보호자 동의 완료 시각';

-- 보호자 FK (보호자 본인도 qim_user이어야 함)
ALTER TABLE user_profile
    ADD CONSTRAINT fk_profile_guardian
        FOREIGN KEY (guardian_qim_user_id)
        REFERENCES qim_user (qim_user_id)
        ON DELETE SET NULL ON UPDATE CASCADE;

-- ── 2. biz_member 테이블 (기업회원 전환 — P3-06) ────────────────
--   사업자등록번호를 기반으로 기업회원 속성을 관리.
--   qim_user와 1:1 (개인→기업 전환) 또는 1:N (여러 멤버) 모두 지원하기 위해
--   별도 테이블로 분리하고 qim_user_id를 PK로 사용.

CREATE TABLE IF NOT EXISTS biz_member (
    qim_user_id         VARCHAR(36)   NOT NULL
        COMMENT '기업회원 qim_user_id (PK = FK)',
    biz_reg_no          VARCHAR(20)   NOT NULL
        COMMENT '사업자등록번호 (숫자만, 10자리 — 예: 1234567890)',
    company_name        VARCHAR(200)  NOT NULL
        COMMENT '법인/상호명',
    rep_name_masked     VARCHAR(100)  NULL DEFAULT NULL
        COMMENT '대표자명 마스킹 (예: 홍*동)',
    biz_type            VARCHAR(50)   NULL DEFAULT NULL
        COMMENT '업태 (예: 도소매, 제조)',
    biz_status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE'
        COMMENT 'ACTIVE / SUSPENDED / CLOSED',
    verified_at         DATETIME(6)   NULL DEFAULT NULL
        COMMENT '사업자등록번호 인증 완료 시각',
    converted_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        COMMENT '기업회원 전환 시각',
    updated_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                      ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_biz_member PRIMARY KEY (qim_user_id),
    CONSTRAINT fk_biz_member_qim_user FOREIGN KEY (qim_user_id)
        REFERENCES qim_user (qim_user_id)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT uq_biz_reg_no UNIQUE (biz_reg_no)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='P3-06 기업회원 전환 정보 — 사업자등록번호 기반';

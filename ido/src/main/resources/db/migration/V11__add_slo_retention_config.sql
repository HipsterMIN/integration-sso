-- =============================================================================
-- IdO V11: SLO + 개인정보 파기 스케줄러 지원 설정
-- =============================================================================
-- 목적:
--   1. agency_webhook_config — USER_LOGOUT 이벤트 타입 지원
--      (기존 event_type_filter JSON 컬럼에 USER_LOGOUT 추가 가이드)
--   2. personal_data_retention_log — 개인정보 파기 이력 테이블 신규 생성
--      (Sprint 2 P1-04: PersonalDataRetentionScheduler 실행 이력 감사)
--   3. inst_mbr_id_mapping — purged_at 컬럼 추가
--      (개인정보 파기 완료 시각 기록)
--
-- 기존 호환성:
--   - ADD COLUMN IF NOT EXISTS 사용 — 재실행 안전
--   - CREATE TABLE IF NOT EXISTS 사용 — 멱등성 보장
--
-- 작성일: 2026-05-09
-- =============================================================================

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. inst_mbr_id_mapping — purged_at 컬럼 추가 (개인정보 파기 완료 시각)
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE ido.inst_mbr_id_mapping
    ADD COLUMN IF NOT EXISTS purged_at TIMESTAMP WITH TIME ZONE
        COMMENT '개인정보 파기 완료 시각 (PersonalDataRetentionScheduler 처리 후 설정)';

COMMENT ON COLUMN ido.inst_mbr_id_mapping.purged_at
    IS '개인정보 파기 완료 시각 (NULL = 미파기)';

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. personal_data_retention_log — 파기 이력 감사 테이블
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS ido.personal_data_retention_log (
    log_id          VARCHAR(36)    NOT NULL    COMMENT '파기 이력 ID (UUIDv4)',
    inst_mbr_id     VARCHAR(36)    NOT NULL    COMMENT '파기된 기관 회원 ID',
    qim_user_id     VARCHAR(36)                COMMENT 'Q-IM 사용자 ID (파기 시점 기록)',
    purge_status    VARCHAR(20)    NOT NULL    COMMENT 'SUCCESS / FAILED',
    purge_detail    TEXT                       COMMENT '파기 상세 내용 또는 실패 원인',
    retention_cutoff TIMESTAMP WITH TIME ZONE  NOT NULL COMMENT '적용된 보존 기간 만료 기준 시각',
    purged_at       TIMESTAMP WITH TIME ZONE   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id  VARCHAR(36)                COMMENT '스케줄 실행 추적 ID',

    CONSTRAINT pk_pdr_log PRIMARY KEY (log_id)
);

CREATE INDEX IF NOT EXISTS idx_pdr_log_inst_mbr_id
    ON ido.personal_data_retention_log (inst_mbr_id);

CREATE INDEX IF NOT EXISTS idx_pdr_log_purged_at
    ON ido.personal_data_retention_log (purged_at DESC);

COMMENT ON TABLE ido.personal_data_retention_log
    IS '개인정보 보존 기간 만료 파기 이력 (§15.2 개인정보 보호 / Sprint 2 P1-04)';

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. slo_audit_log — SLO 실행 감사 테이블
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS ido.slo_audit_log (
    slo_id          VARCHAR(36)    NOT NULL    COMMENT 'SLO 이벤트 ID (UUIDv4)',
    qim_user_id     VARCHAR(36)    NOT NULL    COMMENT '로그아웃한 사용자 Q-IM ID',
    fe_session_id   VARCHAR(64)                COMMENT '만료된 FE 세션 ID',
    keycloak_status VARCHAR(20)                COMMENT 'SUCCESS / FAILED / SKIPPED',
    webhook_status  VARCHAR(20)                COMMENT 'ENQUEUED / SKIPPED / FAILED',
    correlation_id  VARCHAR(36)                COMMENT '추적 ID',
    occurred_at     TIMESTAMP WITH TIME ZONE   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_slo_audit PRIMARY KEY (slo_id)
);

CREATE INDEX IF NOT EXISTS idx_slo_audit_qim_user_id
    ON ido.slo_audit_log (qim_user_id, occurred_at DESC);

COMMENT ON TABLE ido.slo_audit_log
    IS 'SLO(Single Logout) 실행 감사 로그 (§13.3 / Sprint 2 P1-01~03)';

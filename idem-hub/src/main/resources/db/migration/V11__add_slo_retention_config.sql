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

ALTER TABLE idem_hub.inst_mbr_id_mapping
    ADD COLUMN IF NOT EXISTS purged_at TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN idem_hub.inst_mbr_id_mapping.purged_at
    IS '개인정보 파기 완료 시각 (NULL = 미파기 / PersonalDataRetentionScheduler 처리 후 설정)';

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. personal_data_retention_log — 파기 이력 감사 테이블
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS idem_hub.personal_data_retention_log (
    log_id           VARCHAR(36)              NOT NULL,
    inst_mbr_id      VARCHAR(36)              NOT NULL,
    qim_user_id      VARCHAR(36),
    purge_status     VARCHAR(20)              NOT NULL,
    purge_detail     TEXT,
    retention_cutoff TIMESTAMP WITH TIME ZONE NOT NULL,
    purged_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id   VARCHAR(36),

    CONSTRAINT pk_pdr_log PRIMARY KEY (log_id)
);

COMMENT ON TABLE  idem_hub.personal_data_retention_log                  IS '개인정보 보존 기간 만료 파기 이력 (§15.2 개인정보 보호 / Sprint 2 P1-04)';
COMMENT ON COLUMN idem_hub.personal_data_retention_log.log_id           IS '파기 이력 ID (UUIDv4)';
COMMENT ON COLUMN idem_hub.personal_data_retention_log.inst_mbr_id      IS '파기된 기관 회원 ID';
COMMENT ON COLUMN idem_hub.personal_data_retention_log.qim_user_id      IS 'Q-IM 사용자 ID (파기 시점 기록)';
COMMENT ON COLUMN idem_hub.personal_data_retention_log.purge_status     IS 'SUCCESS / FAILED';
COMMENT ON COLUMN idem_hub.personal_data_retention_log.purge_detail     IS '파기 상세 내용 또는 실패 원인';
COMMENT ON COLUMN idem_hub.personal_data_retention_log.retention_cutoff IS '적용된 보존 기간 만료 기준 시각';
COMMENT ON COLUMN idem_hub.personal_data_retention_log.correlation_id   IS '스케줄 실행 추적 ID';

CREATE INDEX IF NOT EXISTS idx_pdr_log_inst_mbr_id
    ON idem_hub.personal_data_retention_log (inst_mbr_id);

CREATE INDEX IF NOT EXISTS idx_pdr_log_purged_at
    ON idem_hub.personal_data_retention_log (purged_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. slo_audit_log — SLO 실행 감사 테이블
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS idem_hub.slo_audit_log (
    slo_id          VARCHAR(36)              NOT NULL,
    qim_user_id     VARCHAR(36)              NOT NULL,
    fe_session_id   VARCHAR(64),
    keycloak_status VARCHAR(20),
    webhook_status  VARCHAR(20),
    correlation_id  VARCHAR(36),
    occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_slo_audit PRIMARY KEY (slo_id)
);

COMMENT ON TABLE  idem_hub.slo_audit_log                  IS 'SLO(Single Logout) 실행 감사 로그 (§13.3 / Sprint 2 P1-01~03)';
COMMENT ON COLUMN idem_hub.slo_audit_log.slo_id           IS 'SLO 이벤트 ID (UUIDv4)';
COMMENT ON COLUMN idem_hub.slo_audit_log.qim_user_id      IS '로그아웃한 사용자 Q-IM ID';
COMMENT ON COLUMN idem_hub.slo_audit_log.fe_session_id    IS '만료된 FE 세션 ID';
COMMENT ON COLUMN idem_hub.slo_audit_log.keycloak_status  IS 'SUCCESS / FAILED / SKIPPED';
COMMENT ON COLUMN idem_hub.slo_audit_log.webhook_status   IS 'ENQUEUED / SKIPPED / FAILED';
COMMENT ON COLUMN idem_hub.slo_audit_log.correlation_id   IS '추적 ID';

CREATE INDEX IF NOT EXISTS idx_slo_audit_qim_user_id
    ON idem_hub.slo_audit_log (qim_user_id, occurred_at DESC);

-- ============================================================
-- IdO Schema V5 : Broker 감사 로그 + provider_type 컬럼 추가
-- 설계서 §18.5.4 IDO_PROVIDER_REGISTRY provider_type 필드
-- 설계서 §18.5.5 IDO_BROKER_AUDIT_LOG
-- ============================================================

-- ──────────────────────────────────────────────────────────────
-- 1. ido.provider_config에 provider_type 컬럼 추가 (§18.5.4)
--    설계 §24.4.1: providerType = STANDARD_OIDC / SEMI_STANDARD_OIDC / NON_STANDARD 고정 집합
-- ──────────────────────────────────────────────────────────────
ALTER TABLE ido.provider_config
    ADD COLUMN IF NOT EXISTS provider_type VARCHAR(30) NOT NULL DEFAULT 'STANDARD_OIDC';

ALTER TABLE ido.provider_config
    ADD CONSTRAINT chk_ido_provider_type
        CHECK (provider_type IN ('STANDARD_OIDC', 'SEMI_STANDARD_OIDC', 'NON_STANDARD'));

COMMENT ON COLUMN ido.provider_config.provider_type
    IS '§18.5.4 STANDARD_OIDC / SEMI_STANDARD_OIDC / NON_STANDARD — 브로커 어댑터 런타임 분기 기준';

-- 기존 데이터 provider_type 업데이트
UPDATE ido.provider_config SET provider_type = 'STANDARD_OIDC'
WHERE broker_mode = 'keycloak' AND provider_type = 'STANDARD_OIDC';

UPDATE ido.provider_config SET provider_type = 'NON_STANDARD'
WHERE broker_mode = 'direct' AND provider_code IN ('PASS', 'FINANCIAL_CERT', 'GPKI', 'JOINT_CERT');

-- ──────────────────────────────────────────────────────────────
-- 2. Broker 감사 로그 (ido.broker_audit_log) (§18.5.5)
--    설계서 §18.5.5 필수 필드: correlationId, providerCode, providerTxId, errorCode
--    브로커 구간(IdO ↔ IDP) 전 주기 감사
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ido.broker_audit_log (
    log_id              VARCHAR(36)   NOT NULL,
    correlation_id      VARCHAR(36)   NOT NULL,
    provider_code       VARCHAR(50)   NOT NULL,
    provider_type       VARCHAR(30)   NOT NULL,           -- STANDARD_OIDC / SEMI_STANDARD_OIDC / NON_STANDARD
    provider_tx_id      VARCHAR(200),                     -- IDP 트랜잭션 ID (Keycloak sub / PASS tx 등)
    broker_mode         VARCHAR(20)   NOT NULL,           -- keycloak / qsign / direct
    action              VARCHAR(50)   NOT NULL,           -- REDIRECT / CALLBACK / COMPLETE / FAIL
    identifier_hash     VARCHAR(64),                      -- SHA-256(sub) — PII 비노출
    auth_level          VARCHAR(10),
    error_code          VARCHAR(50),                      -- PlatformErrorCode (E-IDP-401 등)
    error_detail        VARCHAR(500),
    client_ip           VARCHAR(45),
    fe_session_id       VARCHAR(36),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_broker_audit_log PRIMARY KEY (log_id),
    CONSTRAINT chk_broker_audit_action
        CHECK (action IN ('REDIRECT','CALLBACK','COMPLETE','FAIL','TIMEOUT')),
    CONSTRAINT chk_broker_audit_level
        CHECK (auth_level IS NULL OR auth_level IN ('L1','L2','L3'))
);

CREATE INDEX idx_broker_audit_correlation ON ido.broker_audit_log (correlation_id);
CREATE INDEX idx_broker_audit_provider    ON ido.broker_audit_log (provider_code, created_at DESC);
CREATE INDEX idx_broker_audit_created     ON ido.broker_audit_log (created_at DESC);
CREATE INDEX idx_broker_audit_error       ON ido.broker_audit_log (error_code, created_at DESC)
    WHERE error_code IS NOT NULL;

COMMENT ON TABLE  ido.broker_audit_log                IS '§18.5.5 브로커 구간 전 주기 감사 로그';
COMMENT ON COLUMN ido.broker_audit_log.correlation_id IS '§17.5 X-Correlation-Id — 전 구간 추적 키';
COMMENT ON COLUMN ido.broker_audit_log.provider_tx_id IS 'IDP 측 트랜잭션 ID (Keycloak sub / PASS tx 등)';
COMMENT ON COLUMN ido.broker_audit_log.error_code     IS 'PlatformErrorCode 코드 문자열 (E-IDP-401 등)';

-- ──────────────────────────────────────────────────────────────
-- 3. Q-Sign processed_event 테이블 (§16.3 Q-Sign 멱등 컨슈머)
--    GAP-QS-03: Q-Sign에도 멱등 컨슈머 계약 구현 필요
--    → qsign 스키마에 생성 (Q-Sign DB)
-- ──────────────────────────────────────────────────────────────
-- 참고: 이 파일은 ido 스키마용이므로 qsign.processed_event 는
--       q-sign 모듈의 별도 migration 파일에서 관리해야 합니다.
--       (q-sign/src/main/resources/db/migration/ 경로)

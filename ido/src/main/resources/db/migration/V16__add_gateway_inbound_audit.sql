-- ============================================================
-- V16: 양방향 Agency Gateway — 인바운드 감사 테이블
-- Sprint 15 — 설계서 §15.4 스키마 정의
-- ============================================================
-- 목적:
--   기관(Agency) → OnePass(IdO) 인바운드 이벤트 감사 이력 저장.
--   X-Idempotency-Key 기반 중복 수신 방지 (UNIQUE 제약).
--   아웃바운드 수동 발송 이력도 함께 관리.
--
-- 흐름:
--   [기관 HTTP POST] → AgencyGatewayController
--                    → X-Api-Key 검증 (agency_meta.api_key_hash)
--                    → X-Idempotency-Key 중복 체크 (Redis + DB)
--                    → 이벤트 라우팅 → gateway_inbound_audit INSERT
-- ============================================================

-- ── 1. 인바운드 이벤트 감사 테이블 ───────────────────────────────────────────
-- 기관이 OnePass로 전달하는 모든 인바운드 이벤트 감사 이력
CREATE TABLE IF NOT EXISTS ido.gateway_inbound_audit (
    id                  VARCHAR(36)   NOT NULL DEFAULT gen_random_uuid(),
    agency_code         VARCHAR(50)   NOT NULL,                  -- 송신 기관 코드
    event_type          VARCHAR(50)   NOT NULL,                  -- AGENCY_USER_UPDATED / AGENCY_USER_WITHDRAWN / CUSTOM
    idempotency_key     VARCHAR(36)   NOT NULL,                  -- X-Idempotency-Key (UUID v7, UNIQUE)
    payload             JSONB,                                   -- 수신 페이로드 (PII 최소화)
    status              VARCHAR(20)   NOT NULL DEFAULT 'RECEIVED', -- RECEIVED / PROCESSED / REJECTED / DUPLICATE
    source_ip           VARCHAR(45),                             -- 송신 IP (감사용)
    correlation_id      VARCHAR(36),                             -- X-Correlation-ID 전파
    received_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),    -- 수신 시각
    processed_at        TIMESTAMPTZ,                             -- 처리 완료 시각
    error_message       TEXT,                                    -- 처리 실패 메시지 (REJECTED 시)

    CONSTRAINT pk_gateway_inbound_audit PRIMARY KEY (id),
    CONSTRAINT uq_gateway_inbound_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_gateway_inbound_status
        CHECK (status IN ('RECEIVED','PROCESSED','REJECTED','DUPLICATE')),
    CONSTRAINT chk_gateway_inbound_event_type
        CHECK (event_type IN (
            'AGENCY_USER_UPDATED',
            'AGENCY_USER_WITHDRAWN',
            'AGENCY_USER_REGISTERED',
            'AGENCY_BIZ_CONVERTED',
            'CUSTOM'
        ))
);

-- 인덱스: 기관별 이벤트 이력 조회
CREATE INDEX idx_gateway_inbound_agency_code
    ON ido.gateway_inbound_audit(agency_code, received_at DESC);

-- 인덱스: RECEIVED 상태 미처리 이벤트 조회 (모니터링/재처리용)
CREATE INDEX idx_gateway_inbound_pending
    ON ido.gateway_inbound_audit(received_at ASC)
    WHERE status = 'RECEIVED';

-- 인덱스: idempotency_key 빠른 중복 체크 (UNIQUE가 자동으로 만들지만 명시)
COMMENT ON INDEX ido.uq_gateway_inbound_idempotency IS 'X-Idempotency-Key 중복 수신 방지 — UNIQUE 인덱스';

COMMENT ON TABLE  ido.gateway_inbound_audit                        IS '기관→OnePass 인바운드 이벤트 감사 이력 (Sprint 15)';
COMMENT ON COLUMN ido.gateway_inbound_audit.idempotency_key        IS 'UUID v7 — UNIQUE 제약으로 동일 키 재수신 차단 (24h Redis + DB 이중 방어)';
COMMENT ON COLUMN ido.gateway_inbound_audit.status                 IS 'RECEIVED: 수신완료, PROCESSED: 처리완료, REJECTED: 거부, DUPLICATE: 중복';
COMMENT ON COLUMN ido.gateway_inbound_audit.payload                IS 'PII 최소화: 실명/전화 평문 금지, agencyUserId + 해시만 허용';

-- ── 2. 아웃바운드 발송 이력 테이블 ───────────────────────────────────────────
-- OnePass → 기관 수동/자동 아웃바운드 발송 이력
CREATE TABLE IF NOT EXISTS ido.gateway_outbound_audit (
    id                  VARCHAR(36)   NOT NULL DEFAULT gen_random_uuid(),
    agency_code         VARCHAR(50)   NOT NULL,                  -- 수신 기관 코드
    event_type          VARCHAR(50)   NOT NULL,                  -- NOTIFY_USER / CAST_ISSUED / PROVISIONING
    idempotency_key     VARCHAR(36)   NOT NULL,                  -- 발송 멱등성 키 (UUID v7)
    endpoint_url        VARCHAR(500)  NOT NULL,                  -- 실제 발송 URL
    http_status         INTEGER,                                 -- 기관 응답 HTTP 상태 코드
    status              VARCHAR(20)   NOT NULL DEFAULT 'SENT',   -- SENT / DELIVERED / FAILED
    payload_hash        VARCHAR(64),                             -- SHA-256(payload) — 감사용 (평문 저장 금지)
    correlation_id      VARCHAR(36),
    sent_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    delivered_at        TIMESTAMPTZ,
    error_message       TEXT,

    CONSTRAINT pk_gateway_outbound_audit PRIMARY KEY (id),
    CONSTRAINT uq_gateway_outbound_idempotency UNIQUE (idempotency_key, agency_code),
    CONSTRAINT chk_gateway_outbound_status
        CHECK (status IN ('SENT','DELIVERED','FAILED'))
);

-- 인덱스: 기관별 아웃바운드 이력
CREATE INDEX idx_gateway_outbound_agency_code
    ON ido.gateway_outbound_audit(agency_code, sent_at DESC);

-- 인덱스: FAILED 발송 모니터링
CREATE INDEX idx_gateway_outbound_failed
    ON ido.gateway_outbound_audit(sent_at DESC)
    WHERE status = 'FAILED';

COMMENT ON TABLE  ido.gateway_outbound_audit                       IS 'OnePass→기관 아웃바운드 발송 감사 이력 (Sprint 15)';
COMMENT ON COLUMN ido.gateway_outbound_audit.payload_hash          IS 'SHA-256(payload) — 평문 페이로드 저장 금지, 감사용 해시만 보관';

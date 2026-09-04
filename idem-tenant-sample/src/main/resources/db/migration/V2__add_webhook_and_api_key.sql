-- =============================================================================
-- V2: Agency-Stub Webhook 수신 / API Key 관리 / 이벤트 큐 테이블
-- =============================================================================
-- 설계서 §14.7 Webhook 수신 / §15.3 API Key 관리 / §16.5 이벤트 큐
-- 작성일: 2026-05-08
-- =============================================================================

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. agency_api_key — 기관 API Key 관리 (SHA-256 해시 저장)
-- ─────────────────────────────────────────────────────────────────────────────
-- 목적: AgencyApiKeyInterceptor 가 X-Agency-Key 헤더를 검증할 때 참조.
--       rawKey 원문은 저장하지 않고 SHA-256(rawKey) 만 보관.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS agency_stub.agency_api_key (
    key_id          TEXT        NOT NULL DEFAULT gen_random_uuid()::text,
    agency_code     TEXT        NOT NULL,                           -- 기관 코드 (예: AGENCY_STUB_001)
    api_key_hash    TEXT        NOT NULL,                           -- SHA-256(rawApiKey) hex
    key_label       TEXT,                                           -- 용도 레이블 (예: 'poc-dev', 'prod-01')
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ,                                    -- NULL = 영구
    revoked_at      TIMESTAMPTZ,
    revoke_reason   TEXT,

    CONSTRAINT pk_agency_api_key PRIMARY KEY (key_id),
    CONSTRAINT uq_agency_api_key_hash UNIQUE (agency_code, api_key_hash)
);

COMMENT ON TABLE  agency_stub.agency_api_key IS 'IdO→기관 API Key 관리 (SHA-256 해시 저장, 원문 미보관)';
COMMENT ON COLUMN agency_stub.agency_api_key.api_key_hash IS 'SHA-256(rawApiKey) — HEX 소문자 64자';
COMMENT ON COLUMN agency_stub.agency_api_key.active IS 'FALSE 이면 즉시 인증 거부';
COMMENT ON COLUMN agency_stub.agency_api_key.expires_at IS 'NULL이면 영구 유효';

CREATE INDEX IF NOT EXISTS idx_agency_api_key_lookup
    ON agency_stub.agency_api_key (agency_code, api_key_hash)
    WHERE active = TRUE AND revoked_at IS NULL;

-- PoC 기본 API Key 삽입
-- rawKey = "stub-api-key-dev-001"
-- SHA-256 = echo -n "stub-api-key-dev-001" | sha256sum
INSERT INTO agency_stub.agency_api_key
    (agency_code, api_key_hash, key_label, active)
VALUES
    ('AGENCY_STUB_001',
     'f3a4b2c1d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2',
     'poc-dev-key',
     TRUE)
ON CONFLICT (agency_code, api_key_hash) DO NOTHING;


-- ─────────────────────────────────────────────────────────────────────────────
-- 2. webhook_inbound — IdO → 기관 Webhook 수신 기록
-- ─────────────────────────────────────────────────────────────────────────────
-- 목적: WebhookInboundController 가 수신한 모든 Webhook 을 영속화.
--       HMAC 검증 결과, 처리 상태, 원본 payload 보관.
--       source_event_id + agency_code UNIQUE → 중복 이벤트 방지.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS agency_stub.webhook_inbound (
    inbound_id          TEXT        NOT NULL DEFAULT gen_random_uuid()::text,
    source_event_id     TEXT        NOT NULL,                       -- IdO 측 eventId (중복 방지 키)
    source_event_type   TEXT        NOT NULL,                       -- HANDOFF_ISSUED / HANDOFF_REVOKED / ...
    agency_code         TEXT        NOT NULL DEFAULT 'AGENCY_STUB_001',
    correlation_id      TEXT,
    payload_json        JSONB,                                      -- 원본 request body
    signature_valid     BOOLEAN     NOT NULL DEFAULT FALSE,         -- HMAC 검증 결과
    signature_header    TEXT,                                       -- X-Webhook-Signature 원본 헤더
    timestamp_header    TEXT,                                       -- X-Webhook-Timestamp 원본 헤더
    processing_status   TEXT        NOT NULL DEFAULT 'PENDING'
                            CHECK (processing_status IN ('PENDING','PROCESSED','SKIPPED','FAILED')),
    processing_error    TEXT,
    received_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at        TIMESTAMPTZ,

    CONSTRAINT pk_webhook_inbound   PRIMARY KEY (inbound_id),
    CONSTRAINT uq_webhook_source_id UNIQUE (source_event_id, agency_code)
);

COMMENT ON TABLE  agency_stub.webhook_inbound IS 'IdO WebhookDispatchOutboxRelay 로부터 수신된 Webhook 이벤트 기록';
COMMENT ON COLUMN agency_stub.webhook_inbound.source_event_id  IS 'IdO webhook_dispatch_outbox.event_id — 중복 방지 키';
COMMENT ON COLUMN agency_stub.webhook_inbound.signature_valid   IS 'HMAC-SHA256 검증 성공 여부';
COMMENT ON COLUMN agency_stub.webhook_inbound.processing_status IS 'PENDING→PROCESSED|SKIPPED|FAILED';

CREATE INDEX IF NOT EXISTS idx_webhook_inbound_status
    ON agency_stub.webhook_inbound (processing_status, received_at DESC);

CREATE INDEX IF NOT EXISTS idx_webhook_inbound_correlation
    ON agency_stub.webhook_inbound (correlation_id)
    WHERE correlation_id IS NOT NULL;


-- ─────────────────────────────────────────────────────────────────────────────
-- 3. agency_event_queue — 폴링 API 이벤트 큐
-- ─────────────────────────────────────────────────────────────────────────────
-- 목적: WebhookInboundController 처리 후 → AgencyEventPollingController 제공용.
--       Priority(낮을수록 높음) 기반 정렬, delivered=FALSE 미배달 관리.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS agency_stub.agency_event_queue (
    event_queue_id  TEXT        NOT NULL DEFAULT gen_random_uuid()::text,
    inbound_id      TEXT,                                           -- webhook_inbound.inbound_id (FK)
    agency_code     TEXT        NOT NULL DEFAULT 'AGENCY_STUB_001',
    event_type      TEXT        NOT NULL,
    correlation_id  TEXT,
    event_payload   JSONB,
    priority        SMALLINT    NOT NULL DEFAULT 5                  -- 1=최고 / 8=낮음
                        CHECK (priority BETWEEN 1 AND 10),
    delivered       BOOLEAN     NOT NULL DEFAULT FALSE,
    delivered_at    TIMESTAMPTZ,
    deliver_before  TIMESTAMPTZ,                                    -- NULL = TTL 없음
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_agency_event_queue PRIMARY KEY (event_queue_id)
);

COMMENT ON TABLE  agency_stub.agency_event_queue IS '기관 이벤트 폴링 큐 — WebhookInbound 처리 후 적재, GET /api/v1/events/poll 제공';
COMMENT ON COLUMN agency_stub.agency_event_queue.priority IS '1=CRITICAL(REVOKED/MANDATORY) .. 8=INFO — ORDER BY ASC';
COMMENT ON COLUMN agency_stub.agency_event_queue.delivered IS 'TRUE 이면 폴링 API 에서 이미 반환됨';
COMMENT ON COLUMN agency_stub.agency_event_queue.deliver_before IS 'TTL 초과 이벤트는 폴링 제외';

CREATE INDEX IF NOT EXISTS idx_agency_event_queue_poll
    ON agency_stub.agency_event_queue (agency_code, delivered, priority ASC, created_at ASC)
    WHERE delivered = FALSE;

CREATE INDEX IF NOT EXISTS idx_agency_event_queue_inbound
    ON agency_stub.agency_event_queue (inbound_id)
    WHERE inbound_id IS NOT NULL;

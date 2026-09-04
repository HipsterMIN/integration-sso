-- ============================================================================
-- V2: 인가 이벤트 트랜잭셔널 아웃박스 (회수 전파 기반)
--   · 부여 변경(GRANTED/REVOKED/EXPIRED)을 비즈니스 TX와 원자적으로 적재
--   · outbox-relay-batch(AuthzKafkaRelayJob)가 PENDING을 polling하여
--     authz.assignment.events 토픽으로 발행(ShedLock 다중 Pod 보호)
--   · payload는 text — 아웃박스는 상태/생성순으로만 조회하는 전송 큐이므로
--     jsonb 질의·색인이 불필요(String 바인딩 단순화)
-- ============================================================================

CREATE TABLE IF NOT EXISTS authz.authz_outbox (
    event_id       VARCHAR(36)   NOT NULL,                  -- UUIDv7 (멱등 키)
    event_type     VARCHAR(80)   NOT NULL,                  -- AUTHZ_GRANTED / REVOKED / EXPIRED
    partition_key  VARCHAR(64)   NOT NULL,                  -- qimUserId (Kafka 파티션 키)
    aggregate_id   VARCHAR(128)  NOT NULL,                  -- agency_code:role_code
    event_version  BIGINT,                                  -- authz는 optimistic-lock 버전 없음(NULL 허용)
    payload        TEXT          NOT NULL,                  -- 직렬화 이벤트(JSON 문자열)
    topic          VARCHAR(200)  NOT NULL DEFAULT 'authz.assignment.events',
    status         VARCHAR(20)   NOT NULL DEFAULT 'PENDING',-- PENDING / PUBLISHED / FAILED
    retry_count    SMALLINT      NOT NULL DEFAULT 0,
    error_message  TEXT,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMPTZ,

    CONSTRAINT pk_authz_outbox PRIMARY KEY (event_id)
);

CREATE INDEX IF NOT EXISTS idx_authz_outbox_status
    ON authz.authz_outbox (status, created_at);

COMMENT ON TABLE  authz.authz_outbox            IS '인가 이벤트 트랜잭셔널 아웃박스 — relay-batch가 authz.assignment.events로 발행.';
COMMENT ON COLUMN authz.authz_outbox.partition_key IS 'qimUserId — 사용자 단위 이벤트 순서 보장(Kafka 파티션 키).';
COMMENT ON COLUMN authz.authz_outbox.aggregate_id  IS 'agency_code:role_code — 부여 대상 식별.';

-- 아웃박스는 RLS 비대상(시스템 릴레이가 전 테넌트 폴링). 명시적으로 비활성 유지.

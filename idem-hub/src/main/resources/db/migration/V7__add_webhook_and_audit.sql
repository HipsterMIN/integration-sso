-- ============================================================
-- IdO Schema V7 : Webhook Dispatcher + Audit Log 인프라
-- ============================================================
--
-- 설계 배경:
--   유관기관은 내부 Kafka에 직접 접근할 수 없다.
--   IdO가 내부 Kafka를 구독한 뒤 기관 webhook endpoint로
--   HTTPS POST를 보내는 "Webhook Dispatcher" 패턴을 채택.
--
--   [Kafka] ido.handoff.events  ─► [IdO HandoffEventConsumer]
--                                       │
--                                       ▼
--                              ido.webhook_dispatch_outbox  (이 파일)
--                                       │
--                                       ▼
--                              [WebhookDispatchOutboxRelay]
--                                       │  HTTPS POST
--                                       ▼
--                              외부 기관 webhook endpoint
--
-- 변경 내용:
--   1. ido.agency_webhook_config  : 기관별 webhook 설정 (endpoint, secret, retry 정책)
--   2. ido.webhook_dispatch_outbox: webhook 발송 outbox (at-least-once 보장)
--   3. ido.auth_result_burst_cache: 인증 완료 이벤트 Redis pre-warming 보조 감사
--   4. ido.audit_log              : platform.audit.log 로컬 저장 (감사 요건 2년)
--   5. ido.agency_meta 컬럼 추가  : webhook_enabled, webhook_endpoint
-- ============================================================

-- ──────────────────────────────────────────────────────────────
-- 1. ido.agency_meta 에 webhook 관련 컬럼 추가
--    기존 테이블에 webhook 활성화 여부와 기본 endpoint URL 추가.
--    세부 설정은 agency_webhook_config 에 별도 관리.
-- ──────────────────────────────────────────────────────────────
ALTER TABLE ido.agency_meta
    ADD COLUMN IF NOT EXISTS webhook_enabled   BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS webhook_endpoint  VARCHAR(500);

COMMENT ON COLUMN ido.agency_meta.webhook_enabled  IS 'true = Handoff/Advisory 이벤트를 webhook으로 push';
COMMENT ON COLUMN ido.agency_meta.webhook_endpoint IS '기관 webhook 수신 endpoint URL (HTTPS 필수 — 운영)';

-- PoC: agency-stub webhook 활성화 (개발용 HTTP 허용)
UPDATE ido.agency_meta
SET    webhook_enabled  = TRUE,
       webhook_endpoint = 'http://localhost:8084/webhook/handoff'
WHERE  agency_code = 'AGENCY_STUB_001';

-- ──────────────────────────────────────────────────────────────
-- 2. 기관별 Webhook 상세 설정 (agency_webhook_config)
--    기관마다 서명 비밀키·재시도 정책·타임아웃을 독립 관리.
--    agency_meta.webhook_endpoint 는 기본값,
--    이 테이블에 row 가 있으면 여기 설정이 우선한다.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ido.agency_webhook_config (
    agency_code             VARCHAR(50)   NOT NULL,

    -- Webhook endpoint (agency_meta.webhook_endpoint 오버라이드 가능)
    endpoint_url            VARCHAR(500)  NOT NULL,

    -- HMAC-SHA256 서명 비밀키 (Base64 인코딩, 최소 32바이트)
    -- IdO → 기관 webhook 요청 헤더 X-Webhook-Signature 에 사용
    -- 운영: Vault / KMS 에서 주입; PoC는 설정값
    signing_secret_hash     VARCHAR(500)  NOT NULL,  -- SHA-256(raw_secret) 저장, 원본 금지

    -- HTTP 연결/읽기 타임아웃 (ms)
    connect_timeout_ms      INT           NOT NULL DEFAULT 3000,
    read_timeout_ms         INT           NOT NULL DEFAULT 8000,

    -- 재시도 정책
    max_retry_count         SMALLINT      NOT NULL DEFAULT 3,
    retry_backoff_ms        INT           NOT NULL DEFAULT 1000,  -- 초기 백오프, 지수 증가

    -- 이벤트 타입 필터 (JSONB 배열, NULL = 전체)
    -- e.g. ["HANDOFF_ISSUED","HANDOFF_REVOKED","MEMBER_LOOKUP"]
    event_type_filter       JSONB,

    -- 활성 여부
    active                  BOOLEAN       NOT NULL DEFAULT TRUE,

    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_agency_webhook_config PRIMARY KEY (agency_code),
    CONSTRAINT fk_webhook_agency
        FOREIGN KEY (agency_code) REFERENCES ido.agency_meta (agency_code)
);

CREATE INDEX IF NOT EXISTS idx_agency_webhook_active
    ON ido.agency_webhook_config (active);

COMMENT ON TABLE  ido.agency_webhook_config                  IS '기관별 webhook 발송 상세 설정';
COMMENT ON COLUMN ido.agency_webhook_config.signing_secret_hash IS 'SHA-256(raw_secret) — 평문 저장 금지; 발송 시 원본 사용 (환경변수/Vault)';
COMMENT ON COLUMN ido.agency_webhook_config.event_type_filter   IS 'NULL이면 모든 이벤트 발송; 배열 지정 시 해당 타입만 발송';

-- PoC stub 기관 webhook 설정
INSERT INTO ido.agency_webhook_config
    (agency_code, endpoint_url, signing_secret_hash,
     connect_timeout_ms, read_timeout_ms, max_retry_count, active)
VALUES
    ('AGENCY_STUB_001',
     'http://localhost:8084/webhook/handoff',
     'poc-signing-secret-sha256-placeholder',  -- 운영: 실제 SHA-256 해시로 교체
     3000, 8000, 3, TRUE)
ON CONFLICT (agency_code) DO NOTHING;

-- ──────────────────────────────────────────────────────────────
-- 3. Webhook 발송 Outbox (webhook_dispatch_outbox)
--    Transactional Outbox 패턴으로 webhook at-least-once 보장.
--    WebhookDispatchOutboxRelay 가 PENDING 레코드를 읽어 HTTP POST.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ido.webhook_dispatch_outbox (
    dispatch_id             VARCHAR(36)   NOT NULL,   -- UUIDv4

    -- 발송 대상
    agency_code             VARCHAR(50)   NOT NULL,
    endpoint_url            VARCHAR(500)  NOT NULL,

    -- 이벤트 출처 (추적용)
    source_event_id         VARCHAR(36)   NOT NULL,   -- 원본 Kafka 이벤트 eventId
    source_event_type       VARCHAR(80)   NOT NULL,   -- HANDOFF_ISSUED / MEMBER_LOOKUP_REQUEST 등
    source_topic            VARCHAR(200)  NOT NULL,   -- 원본 Kafka 토픽
    correlation_id          VARCHAR(36),

    -- 발송 payload (JSON)
    payload                 JSONB         NOT NULL,

    -- 상태 관리
    status                  VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    -- PENDING: 미발송
    -- DISPATCHED: HTTP 200~299 응답 수신
    -- FAILED: 최대 재시도 초과
    -- SKIPPED: 기관 webhook 비활성 또는 이벤트 필터 미매칭

    retry_count             SMALLINT      NOT NULL DEFAULT 0,
    max_retry               SMALLINT      NOT NULL DEFAULT 3,
    next_retry_at           TIMESTAMPTZ,               -- 지수 백오프 적용 다음 시도 시각
    last_http_status        SMALLINT,                  -- 마지막 HTTP 응답 코드
    last_error_message      TEXT,

    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    dispatched_at           TIMESTAMPTZ,               -- 최초 성공 응답 시각

    CONSTRAINT pk_webhook_dispatch_outbox PRIMARY KEY (dispatch_id),
    CONSTRAINT chk_webhook_status
        CHECK (status IN ('PENDING','DISPATCHED','FAILED','SKIPPED'))
);

-- 발송 대기 조회 인덱스 (FOR UPDATE SKIP LOCKED 최적화)
CREATE INDEX IF NOT EXISTS idx_webhook_outbox_pending
    ON ido.webhook_dispatch_outbox (status, next_retry_at ASC)
    WHERE status = 'PENDING';

-- 기관별 이력 조회
CREATE INDEX IF NOT EXISTS idx_webhook_outbox_agency
    ON ido.webhook_dispatch_outbox (agency_code, created_at DESC);

-- 원본 이벤트 중복 방지 (source_event_id + agency_code 유니크)
CREATE UNIQUE INDEX IF NOT EXISTS uq_webhook_outbox_source_event
    ON ido.webhook_dispatch_outbox (source_event_id, agency_code);

COMMENT ON TABLE  ido.webhook_dispatch_outbox                  IS 'Webhook 발송 Outbox — at-least-once HTTPS POST 보장';
COMMENT ON COLUMN ido.webhook_dispatch_outbox.source_event_id  IS '원본 Kafka eventId — 중복 발행 방지 unique 제약';
COMMENT ON COLUMN ido.webhook_dispatch_outbox.next_retry_at    IS '지수 백오프 적용 다음 재시도 시각 (NULL = 즉시)';
COMMENT ON COLUMN ido.webhook_dispatch_outbox.last_http_status IS '마지막 HTTP 응답 코드 (200/404/500 등)';

-- ──────────────────────────────────────────────────────────────
-- 4. 플랫폼 감사 로그 (audit_log)
--    platform.audit.log Kafka 토픽 발행 + 로컬 DB 이중 저장.
--    법적 보존 요건: 2년.
--    삭제/수정 금지 (INSERT ONLY).
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ido.audit_log (
    audit_id                VARCHAR(36)   NOT NULL,   -- UUIDv4

    -- 이벤트 분류
    event_category          VARCHAR(50)   NOT NULL,   -- AUTH / HANDOFF / MEMBER / SESSION / WEBHOOK / SYSTEM
    event_action            VARCHAR(80)   NOT NULL,   -- AUTH_COMPLETED / HANDOFF_ISSUED / WEBHOOK_DISPATCHED 등

    -- 주체
    actor_type              VARCHAR(20)   NOT NULL,   -- USER / SYSTEM / AGENCY
    actor_id                VARCHAR(100),             -- qimUserId / serviceId / agencyCode

    -- 대상
    resource_type           VARCHAR(50),              -- TICKET / SESSION / MEMBER / WEBHOOK
    resource_id             VARCHAR(100),             -- ticketId / sessionId / instMbrId

    -- 컨텍스트
    agency_code             VARCHAR(50),
    correlation_id          VARCHAR(36),
    source_system           VARCHAR(50)   NOT NULL,   -- q-sign / q-im / ido
    source_ip               VARCHAR(45),

    -- 결과
    outcome                 VARCHAR(20)   NOT NULL,   -- SUCCESS / FAILURE / PARTIAL
    outcome_detail          VARCHAR(500),

    -- 상세 데이터 (개인정보 마스킹 필수)
    metadata                JSONB,

    -- Kafka 발행 상태 (async 발행 실패 시 재시도)
    kafka_published         BOOLEAN       NOT NULL DEFAULT FALSE,
    kafka_published_at      TIMESTAMPTZ,

    occurred_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_audit_log PRIMARY KEY (audit_id),
    CONSTRAINT chk_audit_event_category
        CHECK (event_category IN ('AUTH','HANDOFF','MEMBER','SESSION','WEBHOOK','SYSTEM')),
    CONSTRAINT chk_audit_actor_type
        CHECK (actor_type IN ('USER','SYSTEM','AGENCY')),
    CONSTRAINT chk_audit_outcome
        CHECK (outcome IN ('SUCCESS','FAILURE','PARTIAL'))
);

-- 감사 조회용 인덱스
CREATE INDEX IF NOT EXISTS idx_audit_log_category_action
    ON ido.audit_log (event_category, event_action, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_log_actor
    ON ido.audit_log (actor_id, occurred_at DESC)
    WHERE actor_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_audit_log_agency
    ON ido.audit_log (agency_code, occurred_at DESC)
    WHERE agency_code IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_audit_log_correlation
    ON ido.audit_log (correlation_id)
    WHERE correlation_id IS NOT NULL;

-- Kafka 미발행 재처리 인덱스
CREATE INDEX IF NOT EXISTS idx_audit_log_kafka_pending
    ON ido.audit_log (kafka_published, occurred_at ASC)
    WHERE kafka_published = FALSE;

COMMENT ON TABLE  ido.audit_log                    IS '플랫폼 전역 감사 로그 — INSERT ONLY, 2년 보존';
COMMENT ON COLUMN ido.audit_log.kafka_published    IS 'platform.audit.log Kafka 발행 완료 여부 (false = 재시도 대상)';
COMMENT ON COLUMN ido.audit_log.metadata           IS '개인정보 포함 금지; CI/DN 대신 identifierHash 사용';

-- ──────────────────────────────────────────────────────────────
-- 5. 회원 조회 요청 이벤트 (member_lookup_request)
--    유관기관으로부터 CI/DN 기반 회원 가입 여부 조회 요청을
--    Kafka 를 통해 비동기 처리하기 위한 추적 테이블.
--
--    흐름:
--      기관 → HTTPS POST /api/v1/member/lookup
--               → MemberLookupService (즉시 응답 or 비동기)
--               → Kafka: ido.member.lookup.requests 발행
--               → (내부) 응답 캐시 후 webhook push 또는 polling
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ido.member_lookup_request (
    request_id              VARCHAR(36)   NOT NULL,   -- UUIDv4
    agency_code             VARCHAR(50)   NOT NULL,
    correlation_id          VARCHAR(36)   NOT NULL,

    -- 조회 식별자 (원본 CI/DN은 저장 금지 — identifierHash만 저장)
    identifier_type         VARCHAR(20)   NOT NULL,   -- CI / DN / BRNO
    identifier_hash         VARCHAR(64)   NOT NULL,   -- SHA-256(identifier)

    -- 처리 상태
    status                  VARCHAR(20)   NOT NULL DEFAULT 'RECEIVED',
    -- RECEIVED: 수신
    -- PROCESSING: Kafka 이벤트 처리 중
    -- COMPLETED: 결과 도출 완료
    -- WEBHOOK_SENT: 기관에 webhook push 완료
    -- FAILED: 처리 실패

    -- 결과 (privacy-safe)
    result_exists           BOOLEAN,                  -- true = 회원 있음
    result_inst_mbr_id      VARCHAR(36),              -- 있으면 instMbrId

    -- Kafka 발행 여부
    kafka_event_id          VARCHAR(36),

    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    completed_at            TIMESTAMPTZ,

    CONSTRAINT pk_member_lookup_request PRIMARY KEY (request_id),
    CONSTRAINT chk_lookup_identifier_type
        CHECK (identifier_type IN ('CI','DN','BRNO')),
    CONSTRAINT chk_lookup_status
        CHECK (status IN ('RECEIVED','PROCESSING','COMPLETED','WEBHOOK_SENT','FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_member_lookup_agency
    ON ido.member_lookup_request (agency_code, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_member_lookup_hash
    ON ido.member_lookup_request (identifier_hash);

COMMENT ON TABLE  ido.member_lookup_request              IS '유관기관 CI/DN 회원 조회 요청 추적';
COMMENT ON COLUMN ido.member_lookup_request.identifier_hash IS 'SHA-256(CI|DN|BRNO) — 원본 식별자 저장 금지';

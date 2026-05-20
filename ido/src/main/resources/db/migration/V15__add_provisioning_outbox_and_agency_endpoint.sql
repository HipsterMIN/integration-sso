-- ============================================================
-- V15: 전 기관 프로비저닝 — agency_endpoint_registry + provisioning_outbox
-- Sprint 14 — 설계서 §14.4 스키마 정의
-- ============================================================
-- 목적:
--   회원가입(USER_REGISTERED) / 기업회원 전환(BIZ_CONVERTED) 발생 시
--   등록된 68개 기관 전체에 프로비저닝 이벤트를 at-least-once 보장으로 전달한다.
--
-- 흐름:
--   [Q-IM Kafka Event] → QimEventConsumer → ProvisioningService
--                        → 68개 기관 병렬 HTTP (Virtual Thread)
--                        → 성공: COMPLETED / 실패: PENDING(재시도)
--   ProvisioningOutboxRelay (@Scheduled, 지수 백오프)
--                        → PENDING 재시도 → 3회 초과: DEAD_LETTER
-- ============================================================

-- ── 1. 기관 API 엔드포인트 레지스트리 ───────────────────────────────────────
-- 68개 유관기관의 실제 API 엔드포인트를 관리하는 SoR
CREATE TABLE IF NOT EXISTS ido.agency_endpoint_registry (
    agency_code          VARCHAR(50)   NOT NULL,                  -- FK → agency_meta.agency_code
    endpoint_type        VARCHAR(30)   NOT NULL,                  -- PROVISIONING / CAST_VERIFY / WEBHOOK / STATUS
    endpoint_url         VARCHAR(500)  NOT NULL,                  -- 기관 API URL (HTTPS 필수 — 운영)
    http_method          VARCHAR(10)   NOT NULL DEFAULT 'POST',   -- POST / PUT / PATCH / GET
    auth_type            VARCHAR(20)   NOT NULL DEFAULT 'API_KEY', -- API_KEY / MTLS / HMAC / NONE
    auth_credential_ref  VARCHAR(200),                            -- K8s Secret 경로 (평문 저장 금지)
    timeout_ms           INTEGER       NOT NULL DEFAULT 5000,     -- 요청 타임아웃(ms)
    is_active            BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    note                 VARCHAR(300),                            -- 운영 메모

    CONSTRAINT pk_agency_endpoint_registry PRIMARY KEY (agency_code, endpoint_type),
    CONSTRAINT fk_aer_agency_code FOREIGN KEY (agency_code)
        REFERENCES ido.agency_meta(agency_code) ON DELETE CASCADE,
    CONSTRAINT chk_aer_endpoint_type
        CHECK (endpoint_type IN ('PROVISIONING','CAST_VERIFY','WEBHOOK','STATUS','GATEWAY_INBOUND')),
    CONSTRAINT chk_aer_auth_type
        CHECK (auth_type IN ('API_KEY','MTLS','HMAC','NONE'))
);

CREATE INDEX IF NOT EXISTS idx_aer_active_type
    ON ido.agency_endpoint_registry(endpoint_type, is_active)
    WHERE is_active = TRUE;

COMMENT ON TABLE  ido.agency_endpoint_registry                  IS '68개 유관기관 API 엔드포인트 레지스트리 (Sprint 14)';
COMMENT ON COLUMN ido.agency_endpoint_registry.endpoint_type   IS 'PROVISIONING: 가입/전환 알림, CAST_VERIFY: CAST 검증, WEBHOOK: 이벤트 수신, STATUS: 헬스체크';
COMMENT ON COLUMN ido.agency_endpoint_registry.auth_credential_ref IS 'K8s Secret 이름 (평문 자격증명 저장 금지)';

-- ── 2. 프로비저닝 아웃박스 ───────────────────────────────────────────────────
-- 회원가입/전환 시 68개 기관으로 전송해야 하는 프로비저닝 이벤트 큐
-- at-least-once 보장: 실패 시 지수 백오프(1분→5분→30분) 재시도, 3회 초과 시 DEAD_LETTER
CREATE TABLE IF NOT EXISTS ido.provisioning_outbox (
    id                   VARCHAR(36)   NOT NULL DEFAULT gen_random_uuid(),
    qim_user_id          VARCHAR(36)   NOT NULL,                  -- 대상 사용자 ID
    agency_code          VARCHAR(50)   NOT NULL,                  -- 전송 대상 기관
    event_type           VARCHAR(50)   NOT NULL,                  -- USER_REGISTERED / BIZ_CONVERTED / USER_UPDATED / USER_WITHDRAWN
    payload              JSONB         NOT NULL,                  -- 전송할 데이터 (PII 최소화: 해시만)
    idempotency_key      VARCHAR(36)   NOT NULL,                  -- UUID v7 — 중복 방지
    status               VARCHAR(20)   NOT NULL DEFAULT 'PENDING', -- PENDING / COMPLETED / DEAD_LETTER
    retry_count          SMALLINT      NOT NULL DEFAULT 0,        -- 현재 재시도 횟수
    max_retry            SMALLINT      NOT NULL DEFAULT 3,        -- 최대 재시도 횟수
    next_retry_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),    -- 다음 재시도 가능 시각
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    last_attempted_at    TIMESTAMPTZ,                             -- 마지막 시도 시각
    completed_at         TIMESTAMPTZ,                             -- 성공 완료 시각
    error_message        TEXT,                                    -- 마지막 실패 메시지
    correlation_id       VARCHAR(36),                             -- 흐름 추적 키
    source_event_id      VARCHAR(36),                             -- 트리거한 Q-IM 이벤트 ID

    CONSTRAINT pk_provisioning_outbox PRIMARY KEY (id),
    CONSTRAINT uq_prov_idempotency UNIQUE (idempotency_key, agency_code),
    CONSTRAINT chk_prov_event_type
        CHECK (event_type IN ('USER_REGISTERED','BIZ_CONVERTED','USER_UPDATED','USER_WITHDRAWN')),
    CONSTRAINT chk_prov_status
        CHECK (status IN ('PENDING','COMPLETED','DEAD_LETTER'))
);

-- 인덱스: 재시도 스케줄러가 사용하는 핵심 인덱스
--   PENDING 상태이고 next_retry_at이 현재 시각 이전인 레코드를 효율적으로 조회
CREATE INDEX IF NOT EXISTS idx_prov_outbox_pending
    ON ido.provisioning_outbox(next_retry_at ASC, agency_code)
    WHERE status = 'PENDING';

-- 인덱스: 사용자별 프로비저닝 이력 조회
CREATE INDEX IF NOT EXISTS idx_prov_outbox_qim_user_id
    ON ido.provisioning_outbox(qim_user_id, created_at DESC);

-- 인덱스: 기관별 DEAD_LETTER 모니터링
CREATE INDEX IF NOT EXISTS idx_prov_outbox_dead_letter
    ON ido.provisioning_outbox(agency_code, created_at DESC)
    WHERE status = 'DEAD_LETTER';

-- 인덱스: 소스 이벤트 기반 중복 체크
CREATE INDEX IF NOT EXISTS idx_prov_outbox_source_event
    ON ido.provisioning_outbox(source_event_id)
    WHERE source_event_id IS NOT NULL;

COMMENT ON TABLE  ido.provisioning_outbox                      IS '전 기관 프로비저닝 아웃박스 — at-least-once 보장 (Sprint 14)';
COMMENT ON COLUMN ido.provisioning_outbox.idempotency_key      IS 'UUID v7 — (idempotency_key, agency_code) 유니크 제약으로 중복 삽입 방지';
COMMENT ON COLUMN ido.provisioning_outbox.next_retry_at        IS '지수 백오프: 1분→5분→30분. NULL이면 즉시 재시도 가능';
COMMENT ON COLUMN ido.provisioning_outbox.payload              IS 'PII 최소화: 실명/전화 평문 금지. qimUserId + 해시 + 가입일만 포함';
COMMENT ON COLUMN ido.provisioning_outbox.status               IS 'PENDING: 대기/재시도, COMPLETED: 성공, DEAD_LETTER: 최대 재시도 초과';

-- ── 3. AGENCY_STUB_001 엔드포인트 시드 (개발/테스트용) ──────────────────────
INSERT INTO ido.agency_endpoint_registry
    (agency_code, endpoint_type, endpoint_url, http_method, auth_type, timeout_ms, is_active, note)
VALUES
    ('AGENCY_STUB_001', 'PROVISIONING',    'http://localhost:8084/api/provisioning/users',  'POST', 'API_KEY', 5000, TRUE, '개발용 stub 기관 프로비저닝 엔드포인트'),
    ('AGENCY_STUB_001', 'CAST_VERIFY',     'http://localhost:8084/api/cast/verify',         'POST', 'API_KEY', 3000, TRUE, '개발용 stub CAST 검증'),
    ('AGENCY_STUB_001', 'WEBHOOK',         'http://localhost:8084/webhook/handoff',          'POST', 'HMAC',    5000, TRUE, '개발용 stub webhook'),
    ('AGENCY_STUB_001', 'STATUS',          'http://localhost:8084/health',                  'GET',  'NONE',    2000, TRUE, '개발용 stub 헬스체크')
ON CONFLICT (agency_code, endpoint_type) DO NOTHING;

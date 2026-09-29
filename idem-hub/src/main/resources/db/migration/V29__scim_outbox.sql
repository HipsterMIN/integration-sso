-- ═══════════════════════════════════════════════════════════════════════════
-- 1.1 PR-5: SCIM 2.0 아웃바운드(Idem → 기관 프로비저닝) 아웃박스 + 감사 분류 보정
--
-- 웹훅 아웃박스(webhook_dispatch_outbox)와 표를 나눈 이유: 그 표는 hub 릴레이·relay 릴레이·기관 이벤트 피드 셋이 읽어
-- 행을 넣으면 곧바로 웹훅으로 POST 되고 기관 피드에 노출된다. SCIM 은 메서드·경로·자원이 다르고 기관 SCIM 서버로만 간다.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS idem_hub.scim_outbox (
    scim_id            VARCHAR(36)   NOT NULL,
    agency_code        VARCHAR(50)   NOT NULL,
    op                 VARCHAR(32)   NOT NULL,   -- ENSURE_USER / DEACTIVATE_USER / DELETE_USER / ADD_GROUP_MEMBER / REMOVE_GROUP_MEMBER
    agency_subject_id  VARCHAR(200)  NOT NULL,   -- 기관향 식별자 (externalId·userName). qimUserId 는 싣지 않는다
    role_code          VARCHAR(64),              -- 그룹 op 전용
    payload            JSONB,                    -- 추가 속성 (active 등)
    source_event_id    VARCHAR(36)   NOT NULL,
    source_event_type  VARCHAR(80)   NOT NULL,
    correlation_id     VARCHAR(36),
    status             VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    retry_count        INTEGER       NOT NULL DEFAULT 0,
    max_retry          INTEGER       NOT NULL DEFAULT 5,
    next_retry_at      TIMESTAMPTZ,
    last_http_status   INTEGER,
    last_error_message VARCHAR(500),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    dispatched_at      TIMESTAMPTZ,
    CONSTRAINT pk_scim_outbox PRIMARY KEY (scim_id),
    CONSTRAINT chk_scim_outbox_status CHECK (status IN ('PENDING','DISPATCHED','FAILED','SKIPPED')),
    CONSTRAINT chk_scim_outbox_op CHECK (op IN ('ENSURE_USER','DEACTIVATE_USER','DELETE_USER','ADD_GROUP_MEMBER','REMOVE_GROUP_MEMBER')),
    CONSTRAINT uq_scim_outbox_source UNIQUE NULLS NOT DISTINCT (source_event_id, agency_code, op, role_code)  -- role_code NULL(사용자 op) 도 중복으로 본다 (PG15+)
);
CREATE INDEX IF NOT EXISTS idx_scim_outbox_pending
    ON idem_hub.scim_outbox (created_at) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_scim_outbox_agency
    ON idem_hub.scim_outbox (agency_code, created_at DESC);
COMMENT ON TABLE idem_hub.scim_outbox IS '1.1 SCIM 아웃바운드 아웃박스 — ScimOutboxRelay 가 기관 SCIM 서버(/Users·/Groups)에 반영한다';

-- 1.1 PR-1 의 ASSIGNMENT_CHANGED 감사가 쓰는 'AUTHZ' 분류가 CHECK 에 없어 INSERT 가 조용히 실패하고 있었다(WAL 도입 뒤에는 재시도 반복).
-- 'AUTHZ'·'SCIM' 을 허용한다. (audit.lost.total 로 유실을 셈)
ALTER TABLE idem_hub.audit_log DROP CONSTRAINT IF EXISTS chk_audit_event_category;
ALTER TABLE idem_hub.audit_log ADD CONSTRAINT chk_audit_event_category
    CHECK (event_category IN ('AUTH','HANDOFF','MEMBER','SESSION','WEBHOOK','SYSTEM','ADMIN','AUTHZ','SCIM'));

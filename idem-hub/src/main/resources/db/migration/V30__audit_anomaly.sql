-- ═══════════════════════════════════════════════════════════════════════════
-- 1.1 PR-8: 감사 로그 이상 탐지 — 관찰 모드 (플랜 §5 #7)
--
-- audit_log 는 손대지 않는다(불변). 비동기 점수기(AuditAnomalyScorer)가 커서 뒤의 감사 행을 읽어 규칙을 평가하고
-- 여기에 플래그만 남긴다. 경보는 없다 — 3개월 기준선 뒤 규칙별 정밀도(검토 결과)로 경보 승격을 결정한다(docs/audit-anomaly.md).
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS idem_hub.audit_anomaly_flag (
    flag_id         VARCHAR(36)   NOT NULL,
    audit_id        VARCHAR(36)   NOT NULL,            -- 방아쇠가 된 감사 행 (FK 없음 — 감사 보존·파기와 독립)
    rule            VARCHAR(40)   NOT NULL,            -- ADMIN_LOGIN_FAILURE_BURST / ADMIN_NEW_SOURCE_IP / ADMIN_OFF_HOURS_WRITE / AGENCY_FAILURE_BURST / TICKET_REPLAY
    score           SMALLINT      NOT NULL,            -- 0~100
    severity        VARCHAR(10)   NOT NULL,            -- LOW(<40) / MEDIUM(<70) / HIGH
    subject_type    VARCHAR(20)   NOT NULL,            -- ACTOR / AGENCY / SOURCE_IP — 규칙이 묶은 축
    subject         VARCHAR(160)  NOT NULL,
    details         JSONB,                             -- 건수·창·기준선 등 설명 가능한 근거 (개인정보 없음)
    -- 감사 행 요약 (조인 없이 목록·테넌트 범위 필터)
    event_category  VARCHAR(50)   NOT NULL,
    event_action    VARCHAR(80)   NOT NULL,
    agency_code     VARCHAR(50),
    actor_id        VARCHAR(100),
    source_ip       VARCHAR(45),
    correlation_id  VARCHAR(36),
    occurred_at     TIMESTAMPTZ   NOT NULL,
    -- 검토 (관찰 모드의 산출물 — 규칙별 정밀도의 근거)
    review          VARCHAR(16),                       -- NULL / TRUE_POSITIVE / FALSE_POSITIVE / UNSURE
    reviewed_by     VARCHAR(100),
    reviewed_at     TIMESTAMPTZ,
    review_note     VARCHAR(500),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_audit_anomaly_flag PRIMARY KEY (flag_id),
    CONSTRAINT uq_audit_anomaly_flag UNIQUE (audit_id, rule),
    CONSTRAINT chk_audit_anomaly_severity CHECK (severity IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT chk_audit_anomaly_review CHECK (review IS NULL OR review IN ('TRUE_POSITIVE','FALSE_POSITIVE','UNSURE'))
);
CREATE INDEX IF NOT EXISTS idx_audit_anomaly_occurred ON idem_hub.audit_anomaly_flag (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_anomaly_rule     ON idem_hub.audit_anomaly_flag (rule, subject_type, subject, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_anomaly_agency   ON idem_hub.audit_anomaly_flag (agency_code, occurred_at DESC) WHERE agency_code IS NOT NULL;
COMMENT ON TABLE idem_hub.audit_anomaly_flag IS '1.1 감사 이상 탐지 플래그(관찰 모드) — AuditAnomalyScorer 가 쓰고 관리자가 검토한다. audit_log 는 불변';

-- 점수기 커서 — 단일 행. 여러 hub 복제본은 FOR UPDATE SKIP LOCKED 로 한 번에 하나만 점수를 낸다
CREATE TABLE IF NOT EXISTS idem_hub.audit_anomaly_cursor (
    id              SMALLINT      NOT NULL,
    last_audit_id   VARCHAR(36),                       -- NULL = 아직 시작 전 (첫 실행이 현재 최대 audit_id 로 맞춘다 — 과거는 소급하지 않는다)
    last_occurred_at TIMESTAMPTZ,
    scanned_total   BIGINT        NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_audit_anomaly_cursor PRIMARY KEY (id),
    CONSTRAINT chk_audit_anomaly_cursor_single CHECK (id = 1)
);
INSERT INTO idem_hub.audit_anomaly_cursor (id) VALUES (1) ON CONFLICT DO NOTHING;

-- 점수기가 창(window) 안 건수를 셀 때 쓰는 인덱스 — 결과·시각 (기관·행위자 인덱스는 V7 에 있다)
CREATE INDEX IF NOT EXISTS idx_audit_log_outcome_time ON idem_hub.audit_log (outcome, occurred_at DESC) WHERE outcome <> 'SUCCESS';
CREATE INDEX IF NOT EXISTS idx_audit_log_ip_actor ON idem_hub.audit_log (actor_id, source_ip, occurred_at DESC) WHERE source_ip IS NOT NULL;

-- ============================================================
-- Agency-Stub Schema : 기관 로컬 세션 / 권한 관리
-- 설계서 §14 Agency 책임 범위 기반
-- ============================================================

CREATE SCHEMA IF NOT EXISTS agency_stub;

-- ──────────────────────────────────────────────────────────────
-- 1. 기관 사용자 (AgencyUser)
--    §14.3 기관향 사용자 식별자(agencySubjectId) 관리.
--    qimUserId 는 Q-IM SoR 참조용이며 기관 SoR 이 아님.
--    agencySubjectId = SHA-256(qimUserId ‖ agencyCode ‖ salt)
-- ──────────────────────────────────────────────────────────────
CREATE TABLE agency_stub.agency_user (
    agency_user_id          VARCHAR(36)   NOT NULL,           -- UUIDv4 (기관 내부 PK)
    agency_subject_id       VARCHAR(300)  NOT NULL,           -- SHA-256 파생 기관 식별자
    qim_user_id             VARCHAR(36)   NOT NULL,           -- Q-IM 참조 (SoR 아님)
    agency_code             VARCHAR(50)   NOT NULL DEFAULT 'AGENCY_STUB_001',
    status                  VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    first_login_at          TIMESTAMPTZ,
    last_login_at           TIMESTAMPTZ,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_agency_user PRIMARY KEY (agency_user_id),
    CONSTRAINT uq_agency_subject_id UNIQUE (agency_subject_id),
    CONSTRAINT chk_agency_user_status
        CHECK (status IN ('ACTIVE','SUSPENDED','WITHDRAWN'))
);

CREATE INDEX idx_agency_user_qim_user ON agency_stub.agency_user (qim_user_id);
CREATE INDEX idx_agency_user_status   ON agency_stub.agency_user (status);

COMMENT ON TABLE  agency_stub.agency_user                  IS '§14.3 기관 사용자 – agencySubjectId 기반 식별';
COMMENT ON COLUMN agency_stub.agency_user.agency_subject_id IS 'SHA-256(qimUserId‖agencyCode‖salt) – Q-IM DI 개념';
COMMENT ON COLUMN agency_stub.agency_user.qim_user_id       IS 'Q-IM 정본 참조 (기관 SoR 아님, 권한 SoR은 기관)';

-- ──────────────────────────────────────────────────────────────
-- 2. 기관 권한 (AgencyPermission)
--    §14.2 기관 권한 SoR. 기관이 독립적으로 관리.
--    permission_key 는 기관 서비스별 접근 권한 코드.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE agency_stub.agency_permission (
    permission_id           VARCHAR(36)   NOT NULL,
    agency_user_id          VARCHAR(36)   NOT NULL,
    permission_key          VARCHAR(100)  NOT NULL,           -- 예: SERVICE_A_READ, ADMIN_MENU
    granted                 BOOLEAN       NOT NULL DEFAULT TRUE,
    granted_by              VARCHAR(100),
    granted_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    expires_at              TIMESTAMPTZ,
    revoked_at              TIMESTAMPTZ,
    revoke_reason           VARCHAR(200),
    CONSTRAINT pk_agency_permission PRIMARY KEY (permission_id),
    CONSTRAINT fk_perm_agency_user
        FOREIGN KEY (agency_user_id) REFERENCES agency_stub.agency_user (agency_user_id)
);

CREATE INDEX idx_agency_perm_user    ON agency_stub.agency_permission (agency_user_id, granted);
CREATE INDEX idx_agency_perm_expires ON agency_stub.agency_permission (expires_at)
    WHERE expires_at IS NOT NULL AND granted = TRUE;

COMMENT ON TABLE  agency_stub.agency_permission              IS '§14.2 기관 권한 SoR – 플랫폼 비관여';
COMMENT ON COLUMN agency_stub.agency_permission.permission_key IS '기관 서비스 접근 권한 코드 (기관 자체 정의)';

-- ──────────────────────────────────────────────────────────────
-- 3. 기관 로컬 세션 (AgencyLocalSession)
--    §14.6 기관 로컬 세션 SoR.
--    AGSID ≥ 128-bit 보안 랜덤, Secure/HttpOnly/SameSite=Strict.
--    IdO Verify 성공 후 기관이 직접 발급.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE agency_stub.agency_local_session (
    session_id              VARCHAR(36)   NOT NULL,           -- 내부 PK (UUIDv4)
    agsid                   VARCHAR(300)  NOT NULL,           -- AGSID ≥128-bit 쿠키값 해시
    agency_user_id          VARCHAR(36)   NOT NULL,
    ticket_id               VARCHAR(36)   NOT NULL,           -- 소비한 Handoff ticketId (감사용)
    correlation_id          VARCHAR(36)   NOT NULL,
    auth_level              VARCHAR(10)   NOT NULL,           -- 인증 시점 수준 (세션 생존 중 불변)
    ip_address              VARCHAR(45),
    user_agent              VARCHAR(500),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    last_accessed_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    idle_expires_at         TIMESTAMPTZ   NOT NULL,           -- TTL = last_accessed_at + idleTimeout
    absolute_expires_at     TIMESTAMPTZ   NOT NULL,           -- TTL = created_at + absoluteTimeout
    invalidated_at          TIMESTAMPTZ,
    invalidate_reason       VARCHAR(100),                     -- LOGOUT / TIMEOUT / ADMIN / SESSION_FIXATION
    CONSTRAINT pk_agency_local_session PRIMARY KEY (session_id),
    CONSTRAINT uq_agsid UNIQUE (agsid),
    CONSTRAINT fk_session_agency_user
        FOREIGN KEY (agency_user_id) REFERENCES agency_stub.agency_user (agency_user_id),
    CONSTRAINT chk_session_auth_level
        CHECK (auth_level IN ('L1','L2','L3'))
);

CREATE INDEX idx_agency_session_agsid    ON agency_stub.agency_local_session (agsid)
    WHERE invalidated_at IS NULL;
CREATE INDEX idx_agency_session_user     ON agency_stub.agency_local_session (agency_user_id, created_at DESC);
CREATE INDEX idx_agency_session_expires  ON agency_stub.agency_local_session (idle_expires_at)
    WHERE invalidated_at IS NULL;
CREATE INDEX idx_agency_session_ticket   ON agency_stub.agency_local_session (ticket_id);

COMMENT ON TABLE  agency_stub.agency_local_session                IS '§14.6 기관 로컬 세션 SoR – AGSID 기반';
COMMENT ON COLUMN agency_stub.agency_local_session.agsid          IS 'SHA-256(rawAGSID) 저장 – 쿠키 원문 저장 금지';
COMMENT ON COLUMN agency_stub.agency_local_session.ticket_id      IS '소비된 Handoff ticketId – 재사용 방지 감사';
COMMENT ON COLUMN agency_stub.agency_local_session.auth_level     IS '세션 발급 시점 인증 수준 – 세션 생존 중 변경 불가';

-- ──────────────────────────────────────────────────────────────
-- 4. 세션 이벤트 로그 (SessionEventLog)
--    §14.9 세션 필수 보안 이벤트 감사.
--    SESSION_CREATED / SESSION_RESUMED / SESSION_INVALIDATED /
--    AUTH_LEVEL_ELEVATED / PERMISSION_DENIED
-- ──────────────────────────────────────────────────────────────
CREATE TABLE agency_stub.session_event_log (
    log_id                  VARCHAR(36)   NOT NULL,
    session_id              VARCHAR(36),                      -- nullable (생성 실패 케이스)
    agency_user_id          VARCHAR(36),
    event_type              VARCHAR(50)   NOT NULL,
    correlation_id          VARCHAR(36),
    ip_address              VARCHAR(45),
    detail                  JSONB,
    occurred_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_session_event_log PRIMARY KEY (log_id),
    CONSTRAINT chk_session_event_type
        CHECK (event_type IN (
            'SESSION_CREATED','SESSION_RESUMED','SESSION_INVALIDATED',
            'AUTH_LEVEL_ELEVATED','PERMISSION_DENIED','HANDOFF_VERIFY_FAILED',
            'SESSION_FIXATION_PREVENTED','CONCURRENT_SESSION_BLOCKED'
        ))
);

CREATE INDEX idx_session_event_session ON agency_stub.session_event_log (session_id, occurred_at DESC)
    WHERE session_id IS NOT NULL;
CREATE INDEX idx_session_event_user    ON agency_stub.session_event_log (agency_user_id, occurred_at DESC)
    WHERE agency_user_id IS NOT NULL;
CREATE INDEX idx_session_event_type    ON agency_stub.session_event_log (event_type, occurred_at DESC);

COMMENT ON TABLE agency_stub.session_event_log IS '§14.9 세션 필수 보안 이벤트 감사 – DELETE/UPDATE 금지';

-- ──────────────────────────────────────────────────────────────
-- 5. 멱등 컨슈머 (ProcessedEvent)
--    §16.3 Kafka 이벤트 중복 처리 방지.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE agency_stub.processed_event (
    event_id                VARCHAR(36)   NOT NULL,
    consumer_group          VARCHAR(100)  NOT NULL,
    event_type              VARCHAR(80),
    result_code             VARCHAR(50),
    processed_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_agency_processed_event PRIMARY KEY (event_id, consumer_group)
);

COMMENT ON TABLE agency_stub.processed_event IS '§16.3 기관 Kafka 컨슈머 멱등 처리 – at-least-once 차단';

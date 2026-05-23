-- CS backoffice ticketing schema

CREATE TABLE IF NOT EXISTS support_ticket (
    ticket_id             UUID PRIMARY KEY,
    channel_cd            VARCHAR(30) NOT NULL,
    ticket_stts_cd        VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    priority_cd           VARCHAR(30) NOT NULL DEFAULT 'NORMAL',
    category_cd           VARCHAR(80),
    tenant_id             VARCHAR(64),
    agency_id             VARCHAR(64),
    ticket_ttl            VARCHAR(200) NOT NULL,
    requester_nm          VARCHAR(80),
    requester_phone       VARCHAR(120),
    requester_email       VARCHAR(120),
    assigned_agent_id     VARCHAR(64),
    linked_qna_id         UUID,
    callback_required_yn  CHAR(1) NOT NULL DEFAULT 'N',
    callback_due_at       TIMESTAMPTZ,
    first_response_due_at TIMESTAMPTZ,
    resolution_due_at     TIMESTAMPTZ,
    closed_at             TIMESTAMPTZ,
    use_yn                CHAR(1) NOT NULL DEFAULT 'Y',
    frst_regist_pnttm     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    frst_register_id      VARCHAR(64) NOT NULL,
    last_updt_pnttm       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_updusr_id        VARCHAR(64) NOT NULL,
    CONSTRAINT ck_support_ticket_channel CHECK (channel_cd IN ('QNA', 'PHONE', 'EMAIL', 'CHAT', 'MANUAL')),
    CONSTRAINT ck_support_ticket_status CHECK (ticket_stts_cd IN ('OPEN', 'PENDING', 'ANSWERED', 'ESCALATED', 'CLOSED')),
    CONSTRAINT ck_support_ticket_priority CHECK (priority_cd IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    CONSTRAINT ck_support_ticket_callback CHECK (callback_required_yn IN ('Y', 'N')),
    CONSTRAINT ck_support_ticket_use_yn CHECK (use_yn IN ('Y', 'N'))
);

CREATE TABLE IF NOT EXISTS support_ticket_event (
    ticket_event_id    UUID PRIMARY KEY,
    ticket_id          UUID NOT NULL REFERENCES support_ticket(ticket_id),
    event_type_cd      VARCHAR(40) NOT NULL,
    visibility_cd      VARCHAR(20) NOT NULL,
    event_cn           TEXT,
    actor_id           VARCHAR(64) NOT NULL,
    use_yn             CHAR(1) NOT NULL DEFAULT 'Y',
    frst_regist_pnttm  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    frst_register_id   VARCHAR(64) NOT NULL,
    CONSTRAINT ck_support_ticket_event_visibility CHECK (visibility_cd IN ('CUSTOMER', 'INTERNAL')),
    CONSTRAINT ck_support_ticket_event_use_yn CHECK (use_yn IN ('Y', 'N'))
);

CREATE TABLE IF NOT EXISTS support_phone_consultation (
    phone_consultation_id UUID PRIMARY KEY,
    ticket_id             UUID NOT NULL REFERENCES support_ticket(ticket_id),
    call_direction_cd     VARCHAR(20) NOT NULL DEFAULT 'INBOUND',
    call_started_at       TIMESTAMPTZ,
    call_ended_at         TIMESTAMPTZ,
    caller_phone          VARCHAR(120),
    identity_verified_yn  CHAR(1) NOT NULL DEFAULT 'N',
    call_summary_cn       VARCHAR(1000) NOT NULL,
    call_detail_cn        TEXT,
    requested_action_cn   VARCHAR(1000),
    callback_required_yn  CHAR(1) NOT NULL DEFAULT 'N',
    callback_due_at       TIMESTAMPTZ,
    frst_regist_pnttm     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    frst_register_id      VARCHAR(64) NOT NULL,
    CONSTRAINT ck_support_phone_direction CHECK (call_direction_cd IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT ck_support_phone_identity CHECK (identity_verified_yn IN ('Y', 'N')),
    CONSTRAINT ck_support_phone_callback CHECK (callback_required_yn IN ('Y', 'N'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_support_ticket_linked_qna
    ON support_ticket(linked_qna_id)
    WHERE linked_qna_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_support_ticket_queue
    ON support_ticket(ticket_stts_cd, channel_cd, assigned_agent_id, last_updt_pnttm DESC);

CREATE INDEX IF NOT EXISTS idx_support_ticket_tenant_agency
    ON support_ticket(tenant_id, agency_id, ticket_stts_cd, last_updt_pnttm DESC);

CREATE INDEX IF NOT EXISTS idx_support_ticket_callback
    ON support_ticket(callback_required_yn, callback_due_at);

CREATE INDEX IF NOT EXISTS idx_support_ticket_event_ticket
    ON support_ticket_event(ticket_id, frst_regist_pnttm);

CREATE INDEX IF NOT EXISTS idx_support_phone_ticket
    ON support_phone_consultation(ticket_id, frst_regist_pnttm DESC);

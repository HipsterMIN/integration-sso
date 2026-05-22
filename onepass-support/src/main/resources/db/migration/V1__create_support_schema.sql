CREATE TABLE IF NOT EXISTS faq_category (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64),
    name VARCHAR(120) NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS faq (
    id UUID PRIMARY KEY,
    category_id UUID REFERENCES faq_category(id),
    tenant_id VARCHAR(64),
    agency_id VARCHAR(64),
    question VARCHAR(500) NOT NULL,
    answer TEXT NOT NULL,
    visibility VARCHAR(32) NOT NULL DEFAULT 'PUBLIC',
    display_order INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(128),
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS qna (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64),
    agency_id VARCHAR(64),
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    visibility VARCHAR(32) NOT NULL DEFAULT 'PRIVATE',
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(128),
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS qna_answer (
    id UUID PRIMARY KEY,
    qna_id UUID NOT NULL REFERENCES qna(id),
    content TEXT NOT NULL,
    answered_by VARCHAR(128) NOT NULL,
    answered_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(128),
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS attachment (
    id UUID PRIMARY KEY,
    owner_type VARCHAR(32) NOT NULL,
    owner_id UUID NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(120),
    size_bytes BIGINT NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    checksum_sha256 VARCHAR(64),
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS support_audit_log (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64),
    actor_id VARCHAR(128),
    action VARCHAR(80) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_id UUID,
    ip_address VARCHAR(64),
    user_agent VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_faq_category_tenant ON faq_category(tenant_id);
CREATE INDEX IF NOT EXISTS idx_faq_tenant_agency ON faq(tenant_id, agency_id);
CREATE INDEX IF NOT EXISTS idx_faq_enabled_order ON faq(enabled, display_order);
CREATE INDEX IF NOT EXISTS idx_qna_tenant_agency_status ON qna(tenant_id, agency_id, status);
CREATE INDEX IF NOT EXISTS idx_qna_created_by ON qna(created_by);
CREATE INDEX IF NOT EXISTS idx_qna_answer_qna_id ON qna_answer(qna_id);
CREATE INDEX IF NOT EXISTS idx_attachment_owner ON attachment(owner_type, owner_id);
CREATE INDEX IF NOT EXISTS idx_support_audit_target ON support_audit_log(target_type, target_id);

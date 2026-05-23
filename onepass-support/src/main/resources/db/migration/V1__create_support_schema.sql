-- onepass-support schema
-- 공공기관 DB 표준화 지침의 공통 메타 컬럼 패턴(등록/수정 시각, 등록/수정자, 사용여부) 준용

CREATE TABLE IF NOT EXISTS faq_group (
    faq_group_id       UUID PRIMARY KEY,
    tenant_id          VARCHAR(64),
    faq_group_cd       VARCHAR(50) NOT NULL,
    faq_group_nm       VARCHAR(120) NOT NULL,
    faq_group_desc     VARCHAR(500),
    sort_sn            INTEGER NOT NULL DEFAULT 0,
    use_yn             CHAR(1) NOT NULL DEFAULT 'Y',
    frst_regist_pnttm  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    frst_register_id   VARCHAR(64) NOT NULL,
    last_updt_pnttm    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_updusr_id     VARCHAR(64) NOT NULL,
    CONSTRAINT uq_faq_group_cd UNIQUE (faq_group_cd),
    CONSTRAINT ck_faq_group_use_yn CHECK (use_yn IN ('Y', 'N'))
);

CREATE TABLE IF NOT EXISTS faq (
    faq_id             UUID PRIMARY KEY,
    faq_group_id       UUID NOT NULL REFERENCES faq_group(faq_group_id),
    tenant_id          VARCHAR(64),
    agency_id          VARCHAR(64),
    faq_qstn_cn        VARCHAR(500) NOT NULL,
    faq_ans_cn         TEXT NOT NULL,
    expsr_yn           CHAR(1) NOT NULL DEFAULT 'Y',
    sort_sn            INTEGER NOT NULL DEFAULT 0,
    use_yn             CHAR(1) NOT NULL DEFAULT 'Y',
    frst_regist_pnttm  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    frst_register_id   VARCHAR(64) NOT NULL,
    last_updt_pnttm    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_updusr_id     VARCHAR(64) NOT NULL,
    CONSTRAINT ck_faq_expsr_yn CHECK (expsr_yn IN ('Y', 'N')),
    CONSTRAINT ck_faq_use_yn CHECK (use_yn IN ('Y', 'N'))
);

CREATE TABLE IF NOT EXISTS qna_post (
    qna_id                  UUID PRIMARY KEY,
    tenant_id               VARCHAR(64),
    agency_id               VARCHAR(64),
    qna_ttl                 VARCHAR(200) NOT NULL,
    qna_cn                  TEXT NOT NULL,
    qna_stts_cd             VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    secret_yn               CHAR(1) NOT NULL DEFAULT 'N',
    anonymous_yn            CHAR(1) NOT NULL DEFAULT 'N',
    writer_user_id          VARCHAR(64),
    anonymous_display_name  VARCHAR(80),
    anonymous_contact_email VARCHAR(120),
    use_yn                  CHAR(1) NOT NULL DEFAULT 'Y',
    frst_regist_pnttm       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    frst_register_id        VARCHAR(64) NOT NULL,
    last_updt_pnttm         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_updusr_id          VARCHAR(64) NOT NULL,
    CONSTRAINT ck_qna_stts_cd CHECK (qna_stts_cd IN ('OPEN', 'ANSWERED', 'CLOSED')),
    CONSTRAINT ck_qna_secret_yn CHECK (secret_yn IN ('Y', 'N')),
    CONSTRAINT ck_qna_anonymous_yn CHECK (anonymous_yn IN ('Y', 'N')),
    CONSTRAINT ck_qna_use_yn CHECK (use_yn IN ('Y', 'N'))
);

CREATE TABLE IF NOT EXISTS qna_answer (
    qna_answer_id        UUID PRIMARY KEY,
    qna_id               UUID NOT NULL REFERENCES qna_post(qna_id),
    answer_cn            TEXT NOT NULL,
    secret_yn            CHAR(1) NOT NULL DEFAULT 'N',
    answered_by_user_id  VARCHAR(64) NOT NULL,
    use_yn               CHAR(1) NOT NULL DEFAULT 'Y',
    frst_regist_pnttm    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    frst_register_id     VARCHAR(64) NOT NULL,
    last_updt_pnttm      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_updusr_id       VARCHAR(64) NOT NULL,
    CONSTRAINT ck_qna_answer_secret_yn CHECK (secret_yn IN ('Y', 'N')),
    CONSTRAINT ck_qna_answer_use_yn CHECK (use_yn IN ('Y', 'N'))
);

CREATE TABLE IF NOT EXISTS support_audit_log (
    audit_id            UUID PRIMARY KEY,
    tenant_id           VARCHAR(64),
    actor_user_id       VARCHAR(64),
    action_cd           VARCHAR(80) NOT NULL,
    target_type_cd      VARCHAR(80) NOT NULL,
    target_id           UUID,
    ip_addr             VARCHAR(64),
    user_agent_cn       VARCHAR(500),
    frst_regist_pnttm   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    frst_register_id    VARCHAR(64) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_faq_group_tenant ON faq_group(tenant_id, use_yn, sort_sn);
CREATE INDEX IF NOT EXISTS idx_faq_tenant_agency ON faq(tenant_id, agency_id, use_yn, sort_sn);
CREATE INDEX IF NOT EXISTS idx_qna_post_tenant_agency ON qna_post(tenant_id, agency_id, qna_stts_cd, frst_regist_pnttm DESC);
CREATE INDEX IF NOT EXISTS idx_qna_post_writer ON qna_post(writer_user_id, frst_regist_pnttm DESC);
CREATE INDEX IF NOT EXISTS idx_qna_answer_qna_id ON qna_answer(qna_id, frst_regist_pnttm);
CREATE INDEX IF NOT EXISTS idx_support_audit_target ON support_audit_log(target_type_cd, target_id, frst_regist_pnttm DESC);

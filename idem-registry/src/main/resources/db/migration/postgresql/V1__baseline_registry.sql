-- ============================================================================
-- idem-registry (Q-IM) PostgreSQL 기준선 — 범용화 D1 (docs/generalization-plan.md v0.3)
--
-- MariaDB 이력 V1~V9 (db/migration/mariadb/) 의 최종 스키마를 PostgreSQL 로 옮긴 단일 기준선이다.
-- 새 설치는 이 파일부터 시작하고, 기존 MariaDB 설치는 scripts/registry-db-migrate/ 로 데이터를 옮긴다.
--
-- 매핑 원칙 (Hibernate 6 검증(ddl-auto=validate)과 엔티티 타입에 맞춘다):
--   DATETIME(6)/TIMESTAMP(6) → TIMESTAMPTZ(6)  (Instant ↔ timestamp with time zone, UTC)
--   JSON                     → JSONB            (@JdbcTypeCode(SqlTypes.JSON) String 필드)
--   TINYINT(1)               → BOOLEAN
--   ON UPDATE CURRENT_TIMESTAMP → 없음 (엔티티 @PreUpdate 가 updated_at 을 세운다)
--   COMMENT '...'            → COMMENT ON
--   UUID()                   → gen_random_uuid() (PostgreSQL 13+)
--   스키마: qim (spring.flyway.schemas / jdbc currentSchema=qim). 스키마 생성은 Flyway create-schemas 가 한다.
-- ============================================================================

-- ── 1. qim_user — 사용자 SoR ────────────────────────────────────────────────
CREATE TABLE qim_user (
    qim_user_id             VARCHAR(36)     NOT NULL,
    tenant_code             VARCHAR(50)     NOT NULL DEFAULT 'DEFAULT',
    status                  VARCHAR(30)     NOT NULL DEFAULT 'ACTIVE',
    withdrawal_reason       VARCHAR(200),
    withdrawal_type         VARCHAR(30),
    withdrawal_scheduled_at TIMESTAMPTZ(6),
    event_version           BIGINT          NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ(6)  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ(6)  NOT NULL DEFAULT NOW(),
    withdrawn_at            TIMESTAMPTZ(6),
    CONSTRAINT pk_qim_user PRIMARY KEY (qim_user_id)
);
COMMENT ON TABLE  qim_user                         IS '사용자 오브젝트 SoR — 플랫폼 전역 qimUserId 기준';
COMMENT ON COLUMN qim_user.qim_user_id             IS 'UUIDv4, 플랫폼 내 불변 식별자';
COMMENT ON COLUMN qim_user.tenant_code             IS '소속 Tenant(Realm) — hub ido.tenant.tenant_code';
COMMENT ON COLUMN qim_user.status                  IS 'ACTIVE | SUSPENDED | WITHDRAWAL_SCHEDULED | WITHDRAWN';
COMMENT ON COLUMN qim_user.withdrawal_type         IS 'IMMEDIATE | SCHEDULED | AGENCY_REQUESTED | ADMIN_FORCED';
COMMENT ON COLUMN qim_user.withdrawal_scheduled_at IS '예약 탈퇴 처리 예정 일시 (SCHEDULED 전용)';
COMMENT ON COLUMN qim_user.event_version           IS '이벤트 순서 기준 + 낙관적 락 버전';

CREATE INDEX idx_qim_user_status               ON qim_user (status);
CREATE INDEX idx_qim_user_updated_at           ON qim_user (updated_at DESC);
CREATE INDEX idx_qim_user_tenant               ON qim_user (tenant_code);
CREATE INDEX idx_qim_user_withdrawal_scheduled ON qim_user (status, withdrawal_scheduled_at);

-- ── 2. auth_mean_mapping — identifierHash → qimUserId ─────────────────────────
CREATE TABLE auth_mean_mapping (
    mapping_id      VARCHAR(36)     NOT NULL,
    qim_user_id     VARCHAR(36)     NOT NULL,
    provider_code   VARCHAR(50)     NOT NULL,
    identifier_hash VARCHAR(300)    NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    linked_at       TIMESTAMPTZ(6)  NOT NULL DEFAULT NOW(),
    revoked_at      TIMESTAMPTZ(6),
    revoke_reason   VARCHAR(200),
    CONSTRAINT pk_auth_mean_mapping         PRIMARY KEY (mapping_id),
    CONSTRAINT uq_identifier_hash_provider  UNIQUE (identifier_hash, provider_code),
    CONSTRAINT fk_mapping_qim_user          FOREIGN KEY (qim_user_id) REFERENCES qim_user (qim_user_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
);
COMMENT ON TABLE  auth_mean_mapping                 IS 'identifierHash → qimUserId 단방향 매핑 SoR (동일 제공자 내 유일)';
COMMENT ON COLUMN auth_mean_mapping.identifier_hash IS 'SubjectScheme.identifierHash — CI/EXTERNAL_SUB 원문 SHA-256, EMAIL/PHONE 은 스킴 접두 후 SHA-256';
COMMENT ON COLUMN auth_mean_mapping.status          IS 'ACTIVE | REVOKED';

CREATE INDEX idx_mapping_qim_user_id          ON auth_mean_mapping (qim_user_id);
CREATE INDEX idx_mapping_identifier_hash      ON auth_mean_mapping (identifier_hash);
CREATE INDEX idx_mapping_provider             ON auth_mean_mapping (provider_code, status);
CREATE INDEX idx_auth_mean_hash_status        ON auth_mean_mapping (identifier_hash, status);
CREATE INDEX idx_mapping_hash_provider_status ON auth_mean_mapping (identifier_hash, provider_code, status);

-- ── 3. user_profile — 속성 (PII 마스킹·암호화) ──────────────────────────────
CREATE TABLE user_profile (
    qim_user_id          VARCHAR(36)     NOT NULL,
    name_masked          VARCHAR(100),
    mobile_masked        VARCHAR(20),
    nationality_type     VARCHAR(10),
    ci                   VARCHAR(512),
    subject_scheme       VARCHAR(20),
    subject_key          VARCHAR(512),
    di_map               JSONB,
    birth_year           SMALLINT,
    gender               VARCHAR(10),
    extra_attributes     JSONB,
    is_minor             BOOLEAN         NOT NULL DEFAULT FALSE,
    guardian_qim_user_id VARCHAR(36),
    guardian_consent_at  TIMESTAMPTZ(6),
    updated_at           TIMESTAMPTZ(6)  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_user_profile      PRIMARY KEY (qim_user_id),
    CONSTRAINT fk_profile_qim_user  FOREIGN KEY (qim_user_id) REFERENCES qim_user (qim_user_id)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_profile_guardian  FOREIGN KEY (guardian_qim_user_id) REFERENCES qim_user (qim_user_id)
        ON DELETE SET NULL ON UPDATE CASCADE
);
COMMENT ON TABLE  user_profile                      IS '사용자 속성 — PII 는 마스킹(name/mobile) 또는 암호화(ci/subject_key) 저장';
COMMENT ON COLUMN user_profile.ci                   IS '암호화된 CI: v{n}.{base64url(iv)}.{base64url(ciphertext+tag)} (KR 에디션)';
COMMENT ON COLUMN user_profile.subject_scheme       IS 'CI | EMAIL | PHONE | EXTERNAL_SUB';
COMMENT ON COLUMN user_profile.subject_key          IS '주체 키 — 암호화 저장 v{n}.{iv}.{ct}';
COMMENT ON COLUMN user_profile.di_map               IS '{serviceCode: DI} 서비스별 개별 식별자';
COMMENT ON COLUMN user_profile.extra_attributes     IS '확장 속성 (카탈로그 밖, 에디션 확장)';
COMMENT ON COLUMN user_profile.is_minor             IS '14세 미만 여부';
COMMENT ON COLUMN user_profile.guardian_qim_user_id IS '보호자 qim_user_id (is_minor 일 때)';

CREATE INDEX idx_user_profile_subject_scheme ON user_profile (subject_scheme);

-- ── 4. user_status_history — 상태 전이 이력 (INSERT 전용) ────────────────────
CREATE TABLE user_status_history (
    history_id     VARCHAR(36)     NOT NULL DEFAULT gen_random_uuid()::text,
    qim_user_id    VARCHAR(36)     NOT NULL,
    status_before  VARCHAR(30)     NOT NULL,
    status_after   VARCHAR(30)     NOT NULL,
    changed_by     VARCHAR(100)    NOT NULL,
    change_reason  VARCHAR(300),
    correlation_id VARCHAR(36),
    occurred_at    TIMESTAMPTZ(6)  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_user_status_history PRIMARY KEY (history_id),
    CONSTRAINT fk_status_history_user FOREIGN KEY (qim_user_id) REFERENCES qim_user (qim_user_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
);
COMMENT ON TABLE user_status_history IS '상태 전이 감사 이력 — DELETE/UPDATE 금지';

CREATE INDEX idx_status_history_user  ON user_status_history (qim_user_id, occurred_at DESC);
CREATE INDEX idx_status_history_after ON user_status_history (status_after, occurred_at DESC);

-- ── 5. outbox — Transactional Outbox ────────────────────────────────────────
CREATE TABLE outbox (
    event_id      VARCHAR(36)     NOT NULL,
    event_type    VARCHAR(80)     NOT NULL,
    partition_key VARCHAR(36)     NOT NULL,
    aggregate_id  VARCHAR(36)     NOT NULL,
    event_version BIGINT          NOT NULL,
    payload       JSONB           NOT NULL,
    topic         VARCHAR(200)    NOT NULL,
    status        VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    retry_count   SMALLINT        NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at    TIMESTAMPTZ(6)  NOT NULL DEFAULT NOW(),
    published_at  TIMESTAMPTZ(6),
    CONSTRAINT pk_qim_outbox PRIMARY KEY (event_id)
);
COMMENT ON TABLE  outbox        IS 'Transactional Outbox — 이벤트 릴레이 큐 (Kafka 는 선택 의존)';
COMMENT ON COLUMN outbox.status IS 'PENDING | PUBLISHED | FAILED';

-- 릴레이 조회(status='PENDING' ORDER BY created_at)는 부분 인덱스로 충분하다
CREATE INDEX idx_qim_outbox_pending   ON outbox (created_at) WHERE status = 'PENDING';
CREATE INDEX idx_qim_outbox_status    ON outbox (status, created_at);
CREATE INDEX idx_qim_outbox_partition ON outbox (partition_key, event_version);

-- ── 6. 멱등 컨슈머 / 스냅샷 ───────────────────────────────────────────────────
CREATE TABLE last_event_version (
    consumer_group VARCHAR(100)    NOT NULL,
    aggregate_id   VARCHAR(36)     NOT NULL,
    last_version   BIGINT          NOT NULL,
    last_event_id  VARCHAR(36)     NOT NULL,
    updated_at     TIMESTAMPTZ(6)  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_last_event_version PRIMARY KEY (consumer_group, aggregate_id)
);
CREATE INDEX idx_last_event_version_updated ON last_event_version (updated_at DESC);

CREATE TABLE processed_event (
    event_id       VARCHAR(36)     NOT NULL,
    consumer_group VARCHAR(100)    NOT NULL,
    event_type     VARCHAR(80),
    result_code    VARCHAR(50),
    processed_at   TIMESTAMPTZ(6)  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id, consumer_group)
);
CREATE INDEX idx_processed_event_group ON processed_event (consumer_group, processed_at DESC);

CREATE TABLE snapshot_meta (
    snapshot_id      VARCHAR(36)     NOT NULL,
    qim_user_id      VARCHAR(36)     NOT NULL,
    snapshot_version BIGINT          NOT NULL,
    topic            VARCHAR(200)    NOT NULL DEFAULT 'qim.user.snapshot',
    status           VARCHAR(20)     NOT NULL DEFAULT 'PUBLISHED',
    created_at       TIMESTAMPTZ(6)  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_snapshot_meta         PRIMARY KEY (snapshot_id),
    CONSTRAINT uq_snapshot_user_version UNIQUE (qim_user_id, snapshot_version),
    CONSTRAINT fk_snapshot_user         FOREIGN KEY (qim_user_id) REFERENCES qim_user (qim_user_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
);
CREATE INDEX idx_snapshot_meta_user ON snapshot_meta (qim_user_id, snapshot_version DESC);

-- ── 7. crypto_key_version — CI/DI 키 버전 메타 (키 재료는 환경변수/KMS) ─────
CREATE TABLE crypto_key_version (
    key_id          VARCHAR(36)     NOT NULL,
    key_type        VARCHAR(50)     NOT NULL,
    key_version     VARCHAR(10)     NOT NULL,
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    grace_until     TIMESTAMPTZ(6),
    rotation_reason VARCHAR(200),
    created_by      VARCHAR(100)    NOT NULL DEFAULT 'SYSTEM',
    created_at      TIMESTAMPTZ(6)  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_crypto_key_version      PRIMARY KEY (key_id),
    CONSTRAINT uq_crypto_key_type_version UNIQUE (key_type, key_version)
);
CREATE INDEX idx_crypto_key_active ON crypto_key_version (key_type, active, created_at DESC);

INSERT INTO crypto_key_version (key_id, key_type, key_version, active, rotation_reason, created_by)
VALUES
    (gen_random_uuid()::text, 'CI_AES',  'v1', TRUE, 'INITIAL_SETUP', 'SYSTEM'),
    (gen_random_uuid()::text, 'DI_HMAC', 'v1', TRUE, 'INITIAL_SETUP', 'SYSTEM')
ON CONFLICT (key_type, key_version) DO NOTHING;

-- ── 8. 동의 ─────────────────────────────────────────────────────────────────
CREATE TABLE consent_version (
    version_id    VARCHAR(36)     NOT NULL,
    consent_type  VARCHAR(50)     NOT NULL,
    version_tag   VARCHAR(50)     NOT NULL,
    title         VARCHAR(200)    NOT NULL,
    content_url   VARCHAR(500),
    required      BOOLEAN         NOT NULL DEFAULT TRUE,
    status        VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    effective_at  TIMESTAMPTZ(6)  NOT NULL,
    superseded_at TIMESTAMPTZ(6),
    created_at    TIMESTAMPTZ(6)  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_consent_version PRIMARY KEY (version_id)
);
COMMENT ON COLUMN consent_version.consent_type IS 'TERMS_OF_SERVICE | PRIVACY_POLICY | THIRD_PARTY_SHARE | MARKETING';
COMMENT ON COLUMN consent_version.status       IS 'DRAFT | ACTIVE | SUPERSEDED';
CREATE INDEX idx_consent_ver_type_status ON consent_version (consent_type, status);
CREATE INDEX idx_consent_ver_effective   ON consent_version (effective_at);

CREATE TABLE consent_record (
    record_id         VARCHAR(36)     NOT NULL,
    qim_user_id       VARCHAR(36)     NOT NULL,
    version_id        VARCHAR(36)     NOT NULL,
    consent_type      VARCHAR(50)     NOT NULL,
    consent_status    VARCHAR(20)     NOT NULL DEFAULT 'AGREED',
    agreed_via        VARCHAR(50),
    client_ip         VARCHAR(45),
    agreed_at         TIMESTAMPTZ(6)  NOT NULL DEFAULT NOW(),
    withdrawn_at      TIMESTAMPTZ(6),
    withdrawal_reason VARCHAR(200),
    created_at        TIMESTAMPTZ(6)  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_consent_record      PRIMARY KEY (record_id),
    CONSTRAINT fk_consent_record_user FOREIGN KEY (qim_user_id) REFERENCES qim_user (qim_user_id)
        ON DELETE CASCADE
);
COMMENT ON TABLE consent_record IS '사용자 동의 이력 — INSERT 전용';
CREATE INDEX idx_consent_record_user_type    ON consent_record (qim_user_id, consent_type);
CREATE INDEX idx_consent_record_user_version ON consent_record (qim_user_id, version_id);
CREATE INDEX idx_consent_record_agreed_at    ON consent_record (agreed_at);

-- ── 9. biz_member — 기업회원 (KR 에디션 확장 후보, S8 에서 이동) ───────────────
CREATE TABLE biz_member (
    qim_user_id     VARCHAR(36)     NOT NULL,
    biz_reg_no      VARCHAR(20)     NOT NULL,
    company_name    VARCHAR(200)    NOT NULL,
    rep_name_masked VARCHAR(100),
    biz_type        VARCHAR(50),
    biz_status      VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    verified_at     TIMESTAMPTZ(6),
    converted_at    TIMESTAMPTZ(6)  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ(6)  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_biz_member          PRIMARY KEY (qim_user_id),
    CONSTRAINT uq_biz_reg_no          UNIQUE (biz_reg_no),
    CONSTRAINT fk_biz_member_qim_user FOREIGN KEY (qim_user_id) REFERENCES qim_user (qim_user_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
);
COMMENT ON TABLE biz_member IS '기업회원 전환 정보 — 사업자등록번호 기반 (S8 에서 KR 에디션 확장으로 이동 예정)';
